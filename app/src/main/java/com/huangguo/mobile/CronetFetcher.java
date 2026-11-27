package com.huangguo.mobile;

import android.content.Context;

import org.chromium.net.CronetEngine;
import org.chromium.net.CronetException;
import org.chromium.net.UrlRequest;
import org.chromium.net.UrlResponseInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Chromium (Cronet) HTTP client.
 *
 * Cronet IS the Chrome network stack (BoringSSL, HTTP/2, HPACK, QUIC): its TLS
 * handshake carries a genuine Chrome fingerprint and passes the CDN JA3
 * filtering that resets plain-Java connections — without spinning up a WebView
 * and with no CORS restrictions. Redirects are followed exactly like a browser,
 * and the URL a request finally landed on is exposed so front-door 30x hops to
 * the real content domain can be recorded for the fast native path.
 */
final class CronetFetcher {
    private static CronetFetcher instance;

    static synchronized CronetFetcher get(Context context) {
        if (instance == null) instance = new CronetFetcher(context.getApplicationContext());
        return instance;
    }

    /** Response body plus the URL the request finally landed on (after redirects). */
    static final class Response {
        final byte[] body;
        final String finalUrl;
        final int status;

        Response(byte[] body, String finalUrl, int status) {
            this.body = body;
            this.finalUrl = finalUrl;
            this.status = status;
        }
    }

    private final CronetEngine engine;
    /** Cronet guarantees callbacks of one request are never concurrent, so a small pool is safe. */
    private final ExecutorService callbacks = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "cronet-callbacks");
        thread.setDaemon(true);
        return thread;
    });

    private CronetFetcher(Context context) {
        CronetEngine.Builder builder = new CronetEngine.Builder(context)
                .enableHttp2(true)
                .enableQuic(true)
                .enableBrotli(true)
                .setUserAgent(chromeUserAgent());
        try {
            java.io.File dir = new java.io.File(context.getCacheDir(), "cronet");
            if (!dir.exists()) dir.mkdirs();
            builder.setStoragePath(dir.getAbsolutePath());
            // Persistent cookie jar without caching content: a cf_clearance cookie
            // once granted survives restarts and dramatically improves front-door odds.
            builder.enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK_NO_HTTP, 10L * 1024 * 1024);
        } catch (Exception ignored) { }
        engine = builder.build();
    }

    /**
     * Blocking GET through the Chromium stack; follows redirects like a browser.
     * Extra headers (site profile) are applied on the initial request.
     */
    Response fetch(String url, Map<String, String> headers, long timeoutMs) throws Exception {
        if (!url.startsWith("https://")) throw new IllegalArgumentException("仅支持 HTTPS");
        CountDownLatch done = new CountDownLatch(1);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AtomicReference<Response> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        UrlRequest.Builder builder = engine.newUrlRequestBuilder(url, new UrlRequest.Callback() {
            private final ByteBuffer buffer = ByteBuffer.allocateDirect(64 * 1024);

            @Override public void onRedirectReceived(UrlRequest request, UrlResponseInfo info, String newLocation) {
                request.followRedirect();
            }

            @Override public void onResponseStarted(UrlRequest request, UrlResponseInfo info) {
                request.read(buffer);
            }

            @Override public void onReadCompleted(UrlRequest request, UrlResponseInfo info, ByteBuffer buf) {
                try {
                    buf.flip();
                    byte[] chunk = new byte[buf.remaining()];
                    buf.get(chunk);
                    bytes.write(chunk, 0, chunk.length);
                    buf.clear();
                    request.read(buf);
                } catch (Exception error) {
                    request.cancel();
                    failure.set(new IOException("Chromium 通道读取失败", error));
                    done.countDown();
                }
            }

            @Override public void onSucceeded(UrlRequest request, UrlResponseInfo info) {
                result.set(new Response(bytes.toByteArray(), info.getUrl(), info.getHttpStatusCode()));
                done.countDown();
            }

            @Override public void onFailed(UrlRequest request, UrlResponseInfo info, CronetException error) {
                failure.set(new IOException("Chromium 通道: " + describe(error, info)));
                done.countDown();
            }

            @Override public void onCanceled(UrlRequest request, UrlResponseInfo info) {
                failure.set(new IOException("Chromium 通道: 请求已取消"));
                done.countDown();
            }
        }, callbacks);
        if (headers == null) headers = browserNavigationHeaders();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if ("User-Agent".equalsIgnoreCase(header.getKey())) continue; // keep the genuine Chrome UA
            try { builder.addHeader(header.getKey(), header.getValue()); } catch (Exception ignored) { }
        }
        UrlRequest request = builder.build();
        request.start();
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            request.cancel();
            throw new IOException("Chromium 通道请求超时");
        }
        if (failure.get() != null) throw failure.get();
        Response response = result.get();
        if (response == null) throw new IOException("Chromium 通道无响应");
        return response;
    }

    /** Human-readable reason for the progress line / error page. */
    private static String describe(CronetException error, UrlResponseInfo info) {
        String message = String.valueOf(error.getMessage());
        if (message.contains("ERR_CONNECTION_RESET")) return "连接被重置（CDN 拦截）";
        if (message.contains("ERR_EMPTY_RESPONSE")) return "服务器返回空响应（疑似限频拦截）";
        if (message.contains("ERR_NAME_NOT_RESOLVED")) return "域名解析失败";
        if (message.contains("ERR_TIMED_OUT") || message.contains("ERR_CONNECTION_TIMED_OUT")) return "连接超时";
        if (message.contains("ERR_CONNECTION_REFUSED")) return "连接被拒绝";
        if (message.contains("ERR_SSL_PROTOCOL_ERROR")) return "TLS 握手失败";
        if (message.contains("ERR_")) return message.replaceFirst(".*?(ERR_[A-Z_]+).*", "$1");
        if (!"null".equals(message) && !message.isEmpty()) return message;
        if (info != null) return "HTTP " + info.getHttpStatusCode();
        return "连接失败";
    }

    /** Plain Chrome-for-Android user agent; Cronet's stock UA carries an "Android-Cronet" marker. */
    private static String chromeUserAgent() {
        return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Mobile Safari/537.36";
    }

    /** What a real Chrome navigation sends. TLS alone does not pass the front
     *  door's bot scoring — the full client-hint / sec-fetch header set matters. */
    private static Map<String, String> browserNavigationHeaders() {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7");
        headers.put("Upgrade-Insecure-Requests", "1");
        headers.put("sec-ch-ua", "\"Chromium\";v=\"143\", \"Google Chrome\";v=\"143\", \"Not?BRand:24\";v=\"24\"");
        headers.put("sec-ch-ua-mobile", "?1");
        headers.put("sec-ch-ua-platform", "\"Android\"");
        headers.put("sec-fetch-dest", "document");
        headers.put("sec-fetch-mode", "navigate");
        headers.put("sec-fetch-site", "none");
        headers.put("sec-fetch-user", "?1");
        return headers;
    }
}
