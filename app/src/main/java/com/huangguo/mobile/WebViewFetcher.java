package com.huangguo.mobile;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless-WebView HTTP client.
 *
 * Some site CDNs drop non-browser TLS handshakes (JA3 fingerprinting):
 * HttpURLConnection is reset while browsers connect fine. Requests issued
 * from a Chromium WebView carry a browser TLS fingerprint and pass.
 *
 * Strategy: navigate the hidden WebView to the target URL itself. Whatever
 * redirects happen on the way, once the page has landed its origin matches
 * the response origin, so a follow-up same-origin fetch returns the raw body
 * without ever hitting CORS. Images inside pages are disabled to keep
 * navigations light. Calls are serialized and blocking.
 */
final class WebViewFetcher {
    private static WebViewFetcher instance;

    static synchronized WebViewFetcher get(Context context) {
        if (instance == null) instance = new WebViewFetcher(context.getApplicationContext());
        return instance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    /** 可超时获取的锁：回退通道本来就慢，其他请求排队比直接失败更糟。 */
    private final java.util.concurrent.locks.ReentrantLock traffic =
            new java.util.concurrent.locks.ReentrantLock(true);
    private static final long LOCK_WAIT_MS = 2500;
    /** 单次浏览器通道请求的总预算（包含导航、重试、取正文）。 */
    private static final long BUDGET_MS = 20_000;
    private final Context context;
    private WebView webView;
    private volatile String currentUrl;
    private volatile String pageTitle;
    private volatile String lastError;
    private volatile CountDownLatch pageLatch = new CountDownLatch(0);
    private volatile CountDownLatch fetchLatch = new CountDownLatch(0);
    private final AtomicReference<String> payload = new AtomicReference<>();

    private WebViewFetcher(Context context) { this.context = context; }

    /**
     * Blocking GET through the WebView engine; returns the response body bytes.
     * Prefer {@link #resolve(String)} first so heavy bodies can go over the fast native stack.
     */
    byte[] fetch(String url) throws Exception {
        java.net.URL parsed = new java.net.URL(url);
        if (!"https".equals(parsed.getProtocol())) throw new IllegalArgumentException("仅支持 HTTPS");
        long deadline = System.currentTimeMillis() + BUDGET_MS;
        if (!traffic.tryLock(LOCK_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            throw new IOException("浏览器通道忙，请稍后重试");
        }
        try {
            ensureCreated();
            // Reuse the loaded page when it already sits on the target's host;
            // a fresh same-origin fetch of the *requested* URL is then enough.
            String pageUrl = onSameHost(url) ? url : navigateLocked(url, 1, 1500, remaining(deadline, 12_000));
            String result = requestLocked(pageUrl, remaining(deadline, 15_000));
            if (result == null && System.currentTimeMillis() < deadline) {
                // The page may have been mid-redirect when we fetched; reload fresh and retry once.
                currentUrl = null;
                pageUrl = navigateLocked(url, 1, 1500, remaining(deadline, 12_000));
                result = requestLocked(pageUrl, remaining(deadline, 15_000));
                if (result == null) throw new IOException("站点连接失败（浏览器通道被拦截）");
            }
            JSONObject json = new JSONObject(result);
            if (json.has("error")) {
                String reason = json.optString("error", "");
                if (reason.contains("Failed to fetch")) throw new IOException("站点连接失败（浏览器通道被拦截）");
                throw new IOException("WebView 请求失败: " + reason);
            }
            int status = json.optInt("status", 0);
            if (status < 200 || status >= 300) throw new IllegalStateException("站点请求失败（" + status + "）");
            return Base64.decode(json.getString("body"), Base64.NO_WRAP);
        } finally {
            traffic.unlock();
        }
    }

    /** 剩余预算，最少 1.5 秒，避免把 0 传给 await 造成立即超时。 */
    private static long remaining(long deadline, long cap) {
        return Math.max(1_500, Math.min(cap, deadline - System.currentTimeMillis()));
    }

    /** Runs the same-origin fetch script; null payload means the page fetch failed (worth one retry). */
    private String requestLocked(String pageUrl, long timeoutMs) throws Exception {
        payload.set(null);
        fetchLatch = new CountDownLatch(1);
        String script = fetchScript(pageUrl);
        main.post(() -> webView.evaluateJavascript(script, null));
        if (!fetchLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw new IOException("WebView 请求超时");
        return payload.get();
    }

    /**
     * Navigates to the URL and returns the address actually landed on, i.e. after
     * any 30x or JS-driven redirects. Used to discover the real host behind
     * filtered front doors. The front door (Cloudflare bot rules) drops some
     * connections at random — probed: identical requests succeed and abort
     * intermittently — so navigation is retried several times, and the address
     * is watched patiently because challenge pages redirect seconds later.
     */
    String resolve(String url) throws Exception {
        java.net.URL parsed = new java.net.URL(url);
        if (!"https".equals(parsed.getProtocol())) throw new IllegalArgumentException("仅支持 HTTPS");
        if (!traffic.tryLock(LOCK_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            throw new IOException("浏览器通道忙，请稍后重试");
        }
        try {
            ensureCreated();
            // 原来 4 次 ×(30s 加载 + 8s 等待) 最长可到 152 秒，且全程占锁；改为 2 次短预算。
            return navigateLocked(url, 2, 3000, 12_000);
        } finally {
            traffic.unlock();
        }
    }

    /** Lighter variant used by batch discovery: fewer attempts, shorter settle. */
    String resolve(String url, int attempts, long settleMs) throws Exception {
        return resolve(url, attempts, settleMs, 30_000);
    }

    /** Discovery variant with a tight page-load timeout so one hanging front
     *  door cannot stall the whole scan. */
    String resolve(String url, int attempts, long settleMs, long loadMs) throws Exception {
        java.net.URL parsed = new java.net.URL(url);
        if (!"https".equals(parsed.getProtocol())) throw new IllegalArgumentException("仅支持 HTTPS");
        if (!traffic.tryLock(LOCK_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            throw new IOException("浏览器通道忙，请稍后重试");
        }
        try {
            ensureCreated();
            return navigateLocked(url, attempts, settleMs, loadMs);
        } finally {
            traffic.unlock();
        }
    }

    /** One-line summary of the most recent navigation failure, for the settings UI. */
    String diagnostics() {
        String error = lastError;
        String title = pageTitle;
        if (error != null && !error.isEmpty()) return error;
        if (title != null && !title.isEmpty()) return "页面:「" + title + "」";
        return "页面无内容";
    }

    /** Caller must hold {@code traffic}. Navigates, retrying up to {@code attempts}
     *  times (the front door aborts connections randomly); then watches the address
     *  for up to {@code settleMs} in case a challenge page redirects late. Returns
     *  the URL the page finally sits on. */
    private String navigateLocked(String url, int attempts, long settleMs, long loadMs) throws Exception {
        String requestedHost = hostOf(url);
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            lastError = null;
            payload.set(null);
            pageLatch = new CountDownLatch(1);
            main.post(() -> webView.loadUrl(url));
            if (!pageLatch.await(loadMs, TimeUnit.MILLISECONDS)) throw new IOException("WebView 加载站点超时");
            String seen = currentUrl;
            long deadline = System.currentTimeMillis() + settleMs;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(400);
                String now = currentUrl;
                if (now == null || !now.startsWith("https://")) break;
                String nowHost = hostOf(now);
                if (nowHost != null && !nowHost.equals(requestedHost)) break; // redirected away
            }
            if (lastError == null) {
                String landed = currentUrl;
                if (landed != null && landed.startsWith("https://")) return landed;
            }
            lastFailure = new IOException("WebView: " + (lastError == null ? "页面加载失败" : lastError));
            currentUrl = null;
            pageTitle = null;
            Thread.sleep(600); // breathe before retrying; each attempt re-picks the connection
        }
        throw lastFailure != null ? lastFailure : new IOException("WebView 无法打开站点");
    }

    private static String hostOf(String url) {
        try { return new java.net.URL(url).getHost(); } catch (Exception error) { return null; }
    }

    /** True when the currently loaded page already sits on the target's host, so a direct same-origin fetch works. */
    private boolean onSameHost(String url) {
        String page = currentUrl;
        if (page == null) return false;
        try {
            java.net.URL target = new java.net.URL(url);
            java.net.URL loaded = new java.net.URL(page);
            return "https".equals(loaded.getProtocol()) && target.getAuthority().equals(loaded.getAuthority());
        } catch (Exception error) {
            return false;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void ensureCreated() throws Exception {
        if (webView != null) return;
        CountDownLatch ready = new CountDownLatch(1);
        main.post(() -> {
            try {
                WebView created = new WebView(context);
                created.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView view, String url) {
                        currentUrl = url;
                        pageTitle = view.getTitle();
                        pageLatch.countDown();
                    }
                    @Override public void onReceivedError(WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceError error) {
                        if (!request.isForMainFrame()) return;
                        lastError = String.valueOf(error.getDescription());
                        pageLatch.countDown(); // unblock the waiter; this navigation is done (failed)
                    }
                    @Override public void onReceivedHttpError(WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceResponse response) {
                        if (!request.isForMainFrame()) return;
                        lastError = "HTTP " + response.getStatusCode();
                        pageLatch.countDown();
                    }
                });
                WebSettings settings = created.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setLoadsImagesAutomatically(false); // keep navigations light; fetch() is unaffected
                // Front-door CDNs block the WebView user agent outright (probed:
                // "Version/4.0" marker => connection aborted, plain Chrome UA => 301
                // to the real host). Present ourselves as regular Chrome for Android.
                settings.setUserAgentString(chromeUserAgent(settings.getUserAgentString()));
                created.addJavascriptInterface(new Bridge(), "HgFetchBridge");
                webView = created;
            } finally {
                ready.countDown();
            }
        });
        if (!ready.await(20, TimeUnit.SECONDS)) throw new IOException("WebView 初始化超时");
    }

    /** Strips the WebView markers ("Version/4.0", "; wv)") from the stock user agent,
     *  leaving a plain Chrome-for-Android UA that CDNs do not discriminate against. */
    private static String chromeUserAgent(String stock) {
        if (stock == null || stock.isEmpty()) {
            return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";
        }
        return stock.replace("; wv)", ")").replaceAll("\\s*Version/\\d+(\\.\\d+)*\\s*", " ").replaceAll("\\s{2,}", " ").trim();
    }

    /** Runs on the page that just landed at the target origin; same-origin by construction. */
    private static String fetchScript(String url) {
        return "(function(){var b=window.HgFetchBridge;if(!b){return;}" +
                "fetch(" + quote(url) + ",{method:'GET',credentials:'include',cache:'default'})" +
                ".then(function(r){return r.arrayBuffer().then(function(buf){" +
                "var u=new Uint8Array(buf),s='',step=0x8000;" +
                "for(var i=0;i<u.length;i+=step)s+=String.fromCharCode.apply(null,u.subarray(i,Math.min(i+step,u.length)));" +
                "b.complete(JSON.stringify({status:r.status,body:btoa(s)}));});})" +
                ".catch(function(e){b.complete(JSON.stringify({error:String(e)}));});})()";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private class Bridge {
        @JavascriptInterface public void complete(String json) {
            payload.set(json);
            CountDownLatch latch = fetchLatch;
            if (latch != null) latch.countDown();
        }
    }
}
