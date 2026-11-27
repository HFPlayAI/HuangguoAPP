package com.huangguo.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the app on the freshest working site automatically.
 *
 * The official publish page (huangguoai.pages.dev) lists the current site
 * base domains inside publish.js ("urls"), combined with a pool of wildcard
 * subdomains ("subdomains"). Discovery fetches that list, probes candidates
 * with the fast native stack, and — because the front doors drop non-browser
 * TLS handshakes — falls back to resolving the real host behind a front door
 * through the embedded WebView. The fastest natively reachable origin wins
 * and is persisted; HotUpdateManager uses it whenever the user has not set a
 * manual override.
 *
 * Every step reports a human-readable progress line through the status
 * listener, so the settings page can show exactly what is happening live.
 */
final class SiteDiscovery {
    /** Both official publish pages; either one may carry the freshest fronts. */
    private static final String[] PUBLISH_JS = {
            "https://huangguoai.pages.dev/publish.js",
            "https://huang-guo.pages.dev/publish.js",
            "https://huangguo88.pages.dev/publish.js",
    };
    /** HTML publish mirrors: no publish.js — the usable lines are hardcoded in the
     *  root page as data-url="https://xxx.base.cc". hgai15.com uses the exact same
     *  template as huangguoai.ai (verified 2026-11-15). */
    private static final String[] PUBLISH_HTML = {
            "https://huangguoai.ai/",
            "https://hgai15.com/",
    };
    /** Link-scan mirrors: the addresses are plain https links inside a custom file
     *  (GitHub Pages ships a bespoke publish.js with PERMANENT_DOMAINS/SPEED_LINES
     *  arrays; GitLab publishes a README). Neither uses the classic urls/subdomains
     *  format, so they go through {@link #contentLinks}. */
    private static final String[] PUBLISH_LINKS = {
            "https://huangguotv.github.io/publish.js",
            "https://gitlab.com/huangguotv/huangguotv/-/raw/main/README.md",
    };
    private static final String CHROME_UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";
    private static final Pattern URLS_BLOCK = Pattern.compile("urls\\s*=\\s*\\[([^\\]]*)\\]");
    private static final Pattern SUBS_BLOCK = Pattern.compile("subdomains\\s*=\\s*\\[([^\\]]*)\\]");
    private static final Pattern QUOTED = Pattern.compile("'([^']+)'");
    /** Re-check at most this often; site owners rotate domains every few days. */
    static final long MIN_INTERVAL_MS = 4 * 3600_000L;
    static long minIntervalMs() { return MIN_INTERVAL_MS; }
    /** Try up to 6 base domains with the browser channel — enough to cover both
     *  publish pages' fronts in one sweep. */
    private static final int MAX_RESOLVE_BASES = 6;
    /** Subdomains are wildcard-resolved; 3 per base triples the dice rolls. */
    private static final int SUBS_PER_BASE = 3;
    /** Hard cap for a whole discovery run, so the UI never hangs silently.
     *  Raised for the full-sweep design: ~12 candidates × (native 14s worst +
     *  Chromium 10s worst) can exceed the old 240s budget and would cut the
     *  browser fallback off entirely. Progress lines stream, so the wait is visible. */
    private static final long DEADLINE_MS = 360_000L;

    // ---- Latency budget (v0.5.0) ----
    /** Connect/read ceiling for publish pages. The old 10s/15s pair meant a dead
     *  host cost 25s per attempt and 76s per source; every source in the sweep is
     *  a domain that either answers in <1s or is blocked outright. */
    private static final int PUBLISH_CONNECT_MS = 3_000;
    private static final int PUBLISH_READ_MS = 6_000;
    /** Origin probes: header-only, so both ceilings stay tight. */
    private static final int PROBE_CONNECT_MS = 3_000;
    private static final int PROBE_READ_MS = 6_000;
    /** Stage A only probes this many GitHub candidates, 2 at a time. */
    private static final int QUICK_CANDIDATES = 4;
    private static final int QUICK_THREADS = 2;
    /** Phase 1 probe concurrency. Above ~6 the fronts start answering RST. */
    private static final int PROBE_THREADS = 6;
    /** Stage A total budget. A stale GitHub list must not delay the full sweep. */
    private static final long QUICK_BUDGET_MS = 12_000L;
    /** Silent upgrade gate: must be ≥20% faster, sub-600ms, and the current origin
     *  must have been in use for a while — otherwise we would flap between hosts
     *  that differ by a few milliseconds. */
    private static final double UPGRADE_RATIO = 0.8d;
    private static final long UPGRADE_MAX_MS = 600L;
    private static final long MIN_DWELL_MS = 120_000L;
    /** The GitHub mirror is the preferred fast source, but its DNS is poisoned on
     *  some networks — never let it block Stage A. */
    private static final String GITHUB_SOURCE = "https://huangguotv.github.io/publish.js";

    /** How long the origin the user was actually using had been in place when this
     *  run started. Captured before Stage 0 refreshes autoCheckedAt — otherwise the
     *  dwell gate could never be satisfied in the same run. */
    private static volatile long baselineAgeMs = Long.MAX_VALUE;
    private static volatile boolean silentPhase = false;
    private static volatile boolean LAST_SILENT = false;
    /** Set for exactly one status push when a rescue result must be applied at
     *  once, even though the run (and its silent optimisation pass) continues. */
    private static volatile boolean APPLY_NOW = false;
    private static volatile String stage = "";

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "site-discovery");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile String CURRENT_PROGRESS = "";
    private static final java.util.Random RANDOM = new java.util.Random();

    // ---- Structured snapshot for the settings page (5 fixed log lines) ----
    // The UI must not have to parse the human progress string: it renders
    //   1) sources connected + total effective sites
    //   2) per-publish-site counts
    //   3) site currently being probed + its publish site + i/N
    //   4) site currently in use + its publish site
    //   5) the raw diagnostic log
    private static volatile int SNAP_CONNECTED = 0;
    private static volatile int SNAP_TOTAL = 0;
    private static volatile org.json.JSONArray SNAP_STATS = new org.json.JSONArray();
    private static volatile String SNAP_PROBE_URL = "";
    private static volatile String SNAP_PROBE_SOURCE = "";
    private static volatile int SNAP_PROBE_INDEX = 0;
    private static volatile int SNAP_PROBE_TOTAL = 0;

    interface Listener { void onResult(JSONObject status); }

    static JSONObject status(Context context) {
        JSONObject json = new JSONObject();
        SharedPreferences prefs = prefs(context);
        try {
            json.put("checking", RUNNING.get());
            json.put("progress", CURRENT_PROGRESS);
            json.put("origin", prefs.getString("autoOrigin", null));
            json.put("latency", prefs.getLong("autoLatency", 0));
            json.put("checkedAt", prefs.getLong("autoCheckedAt", 0));
            // Structured lines for the settings page.
            json.put("sourcesConnected", SNAP_CONNECTED);
            json.put("sourcesTotal", PUBLISH_JS.length + PUBLISH_HTML.length + PUBLISH_LINKS.length);
            json.put("stage", stage);
            json.put("silent", LAST_SILENT);
            json.put("applyNow", APPLY_NOW);
            json.put("totalSites", SNAP_TOTAL);
            json.put("sourceStats", SNAP_STATS);
            json.put("probeUrl", SNAP_PROBE_URL);
            json.put("probeSource", SNAP_PROBE_SOURCE);
            json.put("probeIndex", SNAP_PROBE_INDEX);
            json.put("probeTotal", SNAP_PROBE_TOTAL);
            json.put("usingSource", prefs.getString("autoOriginSource", ""));
        } catch (Exception ignored) { }
        return json;
    }

    /** The persisted automatic origin, or "" when discovery never succeeded. */
    static String autoOrigin(Context context) {
        return prefs(context).getString("autoOrigin", "");
    }

    static boolean start(Context context, Listener listener) {
        if (!RUNNING.compareAndSet(false, true)) return false;
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            try {
                run(app, listener);
            } catch (Exception ignored) {
            } finally {
                // Clear the flag BEFORE the final push, or the UI would keep
                // showing the "checking" state forever.
                RUNNING.set(false);
                if (listener != null) listener.onResult(status(app));
            }
        });
        return true;
    }

    /** Silent startup refresh, throttled to {@link #MIN_INTERVAL_MS}. Always
     *  reports the current status so the UI can show a persisted result. */
    static void startIfStale(Context context, Listener listener) {
        Context app = context.getApplicationContext();
        long checkedAt = prefs(app).getLong("autoCheckedAt", 0);
        if (System.currentTimeMillis() - checkedAt >= MIN_INTERVAL_MS) {
            start(app, listener);
        } else if (listener != null) {
            listener.onResult(status(app));
        }
    }

    /** Manual / forced refresh: ignore the 4-hour throttle entirely. Use when the
     *  user taps "检测最新地址" or when a content fetch just failed — the site may
     *  have rotated its domains and the cached autoOrigin is stale. */
    static boolean startForced(Context context, Listener listener) {
        return start(context.getApplicationContext(), listener);
    }

    /** How long ago the last successful check ran, in milliseconds. */
    static long lastSuccessAge(Context context) {
        long checkedAt = prefs(context).getLong("autoCheckedAt", 0);
        if (checkedAt == 0) return 0;
        return Math.max(0, System.currentTimeMillis() - checkedAt);
    }

    private static void run(Context context, Listener listener) {
        stage = "verify";
        LAST_SILENT = false;
        String priorOrigin = autoOrigin(context);
        baselineAgeMs = priorOrigin.isEmpty() ? Long.MAX_VALUE
                : Math.max(0, System.currentTimeMillis() - prefs(context).getLong("autoCheckedAt", 0));

        // ---- Stage 0: is the origin we already have still alive? ----
        // Most manual checks are "can I still watch right now?". Answer that with
        // ONE request instead of a seven-source, 25-candidate sweep, and keep the
        // current origin when it answers — switching would cost a needless reload.
        String current = autoOrigin(context);
        if (!current.isEmpty()) {
            push(listener, context, "快速验证当前地址（3s 上限）：" + current);
            try {
                long ms = probe(current, PROBE_CONNECT_MS, 4_000);
                if (!verifyContent(current)) throw new IllegalStateException("内容校验未通过");
                push(listener, context, "✓ 当前地址可用（" + ms + "ms），立即可用；后台静默优选…");
                save(context, current, ms, prefs(context).getString("autoOriginSource", ""));
                note(context, "扫描✓当前地址可用 " + host(current) + " " + ms + "ms（转入后台静默优选）");
                silentSweep(context, listener);
                return;
            } catch (Exception error) {
                push(listener, context, "✗ 当前地址不可用（" + reason(error) + "），进入快速接管");
                note(context, "扫描✗当前地址 " + host(current) + "：" + reason(error));
            }
        }

        // ---- Stage A: GitHub-first quick takeover ----
        stage = "quick";
        String quick = quickFromGithub(context, listener);
        if (quick != null) {
            silentSweep(context, listener);
            return;
        }

        // ---- Stage C: full sweep — the old behaviour, kept as the safety net ----
        stage = "sweep";
        fullSweep(context, listener, System.currentTimeMillis() + DEADLINE_MS);
        stage = "";
    }

    /** Stage B: re-sweep every source in the background, silently. The result only
     *  replaces the active origin when it is decisively faster (see UPGRADE_RATIO),
     *  and MainActivity defers the reload while the UI is in the foreground. */
    private static void silentSweep(Context context, Listener listener) {
        stage = "silent";
        silentPhase = true;
        LAST_SILENT = true;
        try {
            fullSweep(context, listener, System.currentTimeMillis() + DEADLINE_MS);
        } finally {
            silentPhase = false;
            stage = "";
            CURRENT_PROGRESS = "";
        }
    }

    /** Stage A: only the GitHub mirror, only the first few candidates, two at a
     *  time. Returns the adopted origin or null when the source is unreachable. */
    private static String quickFromGithub(Context context, Listener listener) {
        push(listener, context, "优先抓取 GitHub 发布源…");
        String body = get(GITHUB_SOURCE);
        if (body == null || body.isEmpty()) {
            note(context, "GitHub 发布源不可达，降级为全量扫描");
            push(listener, context, "GitHub 发布源不可达，降级为全量扫描…");
            return null;
        }
        List<String> candidates = contentLinks(body, "huangguotv.github.io");
        if (candidates.isEmpty()) {
            note(context, "GitHub 发布源未解析出候选，降级为全量扫描");
            return null;
        }
        if (candidates.size() > QUICK_CANDIDATES) candidates = new ArrayList<>(candidates.subList(0, QUICK_CANDIDATES));
        push(listener, context, "GitHub 源解析出 " + candidates.size() + " 个候选，并发探测…");

        long quickDeadline = System.currentTimeMillis() + QUICK_BUDGET_MS;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(QUICK_THREADS, candidates.size()));
        CompletionService<String> completion = new ExecutorCompletionService<>(pool);
        try {
            for (String candidate : candidates) {
                completion.submit(() -> {
                    long ms = probe(candidate);
                    return candidate + " " + ms;
                });
            }
            for (int i = 0; i < candidates.size(); i++) {
                long left = quickDeadline - System.currentTimeMillis();
                if (left <= 0) break;
                Future<String> done = completion.poll(Math.min(left, 5_000L), TimeUnit.MILLISECONDS);
                if (done == null) break;
                String result;
                try { result = done.get(); } catch (Exception error) { continue; }
                String origin = result.substring(0, result.lastIndexOf(' '));
                long ms = Long.parseLong(result.substring(result.lastIndexOf(' ') + 1));
                if (!verifyContent(origin)) {
                    note(context, "扫描✗快速接管 " + host(origin) + "：内容校验未通过");
                    continue;
                }
                save(context, origin, ms, "huangguotv.github.io");
                note(context, "扫描✓快速接管 " + origin + " " + ms + "ms 来源 huangguotv.github.io");
                // Rescue result: the user is staring at an error page, so this one
                // push must be applied immediately — the silent pass that follows
                // may still upgrade it later, but never at the cost of this wait.
                APPLY_NOW = true;
                push(listener, context, "✓ 已接管可用地址：" + origin + "（" + ms + "ms），后台继续优选…");
                APPLY_NOW = false;
                return origin;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
        push(listener, context, "GitHub 源候选均不可用，降级为全量扫描…");
        return null;
    }

    /** Silent decision: keep the running origin unless the new one is decisively
     *  faster and has been evaluated against a warmed-up baseline. */
    private static void considerUpgrade(Context context, Listener listener, String origin,
                                        long latency, String source) {
        String current = autoOrigin(context);
        long currentMs = prefs(context).getLong("autoLatency", 0);
        long age = baselineAgeMs;
        if (normalizeOrigin(origin).equals(normalizeOrigin(current))) {
            save(context, origin, latency, source);
            push(listener, context, "静默优选：当前地址仍是最快（" + latency + "ms）");
            return;
        }
        boolean faster = latency > 0 && latency <= UPGRADE_MAX_MS
                && (currentMs <= 0 || latency < currentMs * UPGRADE_RATIO);
        if (!faster || age < MIN_DWELL_MS) {
            note(context, "静默评估：保留 " + current + "（" + currentMs + "ms），候选 " + origin + "（" + latency + "ms）");
            push(listener, context, "静默优选结束：保留当前地址 " + current);
            return;
        }
        save(context, origin, latency, source);
        note(context, "静默升级 " + current + " → " + origin + "（" + currentMs + "ms → " + latency + "ms）");
        push(listener, context, "静默优选：已切换到更快地址 " + origin + "（" + latency + "ms）");
    }

    private static String normalizeOrigin(String origin) {
        return origin == null ? "" : origin.replaceAll("(?i)^https?://", "").replaceAll("/+$", "").trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static void fullSweep(Context context, Listener listener, long deadline) {
        // ---- Per-source candidate collection with explicit provenance ----
        // 每个候选都记录它来自哪个发布站；后续日志前缀 + 进度都从这里取。
        Map<String, List<String>> bySource = new LinkedHashMap<>();
        for (String key : PUBLISH_JS) {
            String label = shortLabel(key);
            List<String> items = new ArrayList<>();
            String content = get(key);
            if (content != null && !content.isEmpty()) {
                List<String> localBases = new ArrayList<>();
                List<String> localSubs = new ArrayList<>();
                Matcher urls = URLS_BLOCK.matcher(content);
                if (urls.find()) collect(urls.group(1), localBases, false);
                Matcher subBlocks = SUBS_BLOCK.matcher(content);
                if (subBlocks.find()) collect(subBlocks.group(1), localSubs, true);
                // The publish file repeats a base on purpose: the page renders
                // `urls.length * 3` lines with DISTINCT subdomains, so two identical
                // rlhjeyuvw.cc entries mean six different hosts, not one. Keep the
                // duplicates and mirror the page's arithmetic, otherwise we probe
                // half the hosts the page advertises.
                List<String> chosenSubs = pickSubdomains(localSubs,
                        localBases.size() * SUBS_PER_BASE);
                for (int i = 0; i < chosenSubs.size() && !localBases.isEmpty(); i++) {
                    String base = localBases.get(i % localBases.size());
                    items.add("https://" + chosenSubs.get(i) + "." + base + "/");
                }
            }
            bySource.put(label, items);
        }

        // HTML publish mirrors (huangguoai.ai / hgai15.com) — usable lines are
        // hardcoded in the root page as data-url="https://xxx.base.cc". No
        // subdomain pool needed. Both mirrors share one template.
        List<String> htmlLabels = new ArrayList<>();
        List<String> htmlMissed = new ArrayList<>();
        int htmlConnected = 0;
        for (String source : PUBLISH_HTML) {
            String label = shortLabel(source);
            String page = get(source);
            page = page == null ? "" : page;
            if (page.isEmpty()) htmlMissed.add(label); else htmlConnected++;
            List<String> items = new ArrayList<>();
            if (!page.isEmpty()) {
                // Lines are emitted as data-url="https://sub.base.cc" — note there is
                // NO trailing slash on most of them. Requiring "/" here silently
                // matched ZERO lines, which is why this source once looked dead.
                Matcher m = Pattern.compile("data-url=\"(https://[a-zA-Z0-9.-]+(?::\\d+)?(?:/[^\"]*)?)\"")
                        .matcher(page);
                while (m.find()) {
                    String line = m.group(1).trim();
                    if (!line.contains("pages.dev") && !line.contains("huangguoai.com")
                            && !line.contains("huangguoai.ai") && !line.contains("hgai15.com")
                            && !items.contains(line)) {
                        items.add(line.endsWith("/") ? line : line + "/");
                    }
                }
                // Fallback: the page also renders each line as a plain <a href>. If
                // the data-url markup ever changes, mine href links instead.
                if (items.isEmpty()) {
                    Matcher links = Pattern.compile("href=\"(https://[^\"/]+)\"").matcher(page);
                    while (links.find()) {
                        String url = links.group(1) + "/";
                        String h = host(url).toLowerCase(java.util.Locale.ROOT);
                        if (h.endsWith("pages.dev") || h.contains("huangguoai.com")
                                || h.contains("huangguoai.ai") || h.contains("hgai15.com")
                                || h.endsWith("x.com") || h.endsWith("t.me")
                                || h.contains("tc41p53k")) continue;
                        if (!items.contains(url)) items.add(url);
                        if (items.size() >= 6) break;
                    }
                }
            }
            bySource.put(label, items);
            htmlLabels.add(label);
        }

        // Link-scan mirrors (GitHub Pages publish.js / GitLab README): addresses are
        // plain https links, so mine every host and keep only real content fronts —
        // publish pages, social channels and bare landing domains are rejected.
        List<String> linkLabels = new ArrayList<>();
        List<String> linkMissed = new ArrayList<>();
        int linkConnected = 0;
        for (String source : PUBLISH_LINKS) {
            String label = linkLabel(source);
            String body = get(source);
            body = body == null ? "" : body;
            if (body.isEmpty()) linkMissed.add(label); else linkConnected++;
            List<String> items = new ArrayList<>();
            if (!body.isEmpty()) items.addAll(contentLinks(body, label));
            bySource.put(label, items);
            linkLabels.add(label);
        }

        // ---- Cross-source dedup with full provenance ----
        // Both pages.dev files publish identical url lists, so one URL is often
        // delivered by more than one publish site. Keep EVERY contributing label
        // (ordered .ai first) so the logs can prove which publish sites actually
        // produced each candidate — collapsing to a single label made the second
        // pages.dev source look like it was never fetched.
        List<String> preferred = new ArrayList<>(htmlLabels);
        preferred.addAll(linkLabels);
        List<String> sourceOrder = new ArrayList<>();
        for (String label : preferred) if (bySource.containsKey(label)) sourceOrder.add(label);
        List<String> otherSources = new ArrayList<>(bySource.keySet());
        otherSources.removeAll(preferred);
        otherSources.sort(null);
        sourceOrder.addAll(otherSources);

        Map<String, List<String>> provenance = new LinkedHashMap<>(); // url -> source labels
        for (String label : sourceOrder) {
            for (String url : bySource.get(label)) {
                List<String> labels = provenance.get(url);
                if (labels == null) { labels = new ArrayList<>(); provenance.put(url, labels); }
                if (!labels.contains(label)) labels.add(label);
            }
        }

        // 排序：huangguoai.ai 线路优先（新、稳、直连），其余按标签字典序稳定排列。
        List<String> ordered = new ArrayList<>();
        List<String> aiFirst = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (String url : provenance.keySet()) {
            boolean fromHtml = false;
            for (String label : provenance.get(url)) {
                if (preferred.contains(label)) { fromHtml = true; break; }
            }
            if (fromHtml) aiFirst.add(url); else rest.add(url);
        }
        rest.sort(null);
        ordered.addAll(aiFirst);
        ordered.addAll(rest);

        int totalSites = ordered.size();
        // 各发布站去重后贡献的站点数；同一 URL 可计入多个发布站（内容相同的双源）。
        Map<String, Integer> perSourceCount = new LinkedHashMap<>();
        for (String label : sourceOrder) perSourceCount.put(label, 0);
        for (String url : ordered) {
            for (String label : provenance.get(url)) {
                if (perSourceCount.containsKey(label)) {
                    perSourceCount.put(label, perSourceCount.get(label) + 1);
                }
            }
        }

        // ---- 日志头部汇总 ----
        // "已成功抓取" 以该发布站是否取到非空响应体判（连接成功即计入，
        // 与是否解析出候选无关）。pages.dev 两源独立计数。
        String jsA = get(PUBLISH_JS[0]), jsB = get(PUBLISH_JS[1]);
        int publishSources = PUBLISH_JS.length + PUBLISH_HTML.length + PUBLISH_LINKS.length;
        int connectedSources = 0;
        if (jsA != null && !jsA.isEmpty()) connectedSources++;
        if (jsB != null && !jsB.isEmpty()) connectedSources++;
        connectedSources += htmlConnected + linkConnected;
        // Publish the structured summary the settings page renders as line 1/2.
        SNAP_CONNECTED = connectedSources;
        SNAP_TOTAL = totalSites;
        org.json.JSONArray stats = new org.json.JSONArray();
        for (String label : perSourceCount.keySet()) {
            try {
                stats.put(new JSONObject().put("label", label).put("count", perSourceCount.get(label)));
            } catch (Exception ignored) { }
        }
        SNAP_STATS = stats;
        SNAP_PROBE_URL = "";
        SNAP_PROBE_SOURCE = "";
        SNAP_PROBE_INDEX = 0;
        SNAP_PROBE_TOTAL = 0;

        push(listener, context, "前门日志 — 当前已成功抓取 " + connectedSources + " / " + publishSources + " 个发布站点；"
                + "有效站点总数（去重后匹配抓取规则）：" + totalSites);
        note(context, "扫描开始：候选 " + totalSites + " 个，发布站 " + connectedSources + "/" + publishSources);
        StringBuilder attribution = new StringBuilder();
        int attributedSum = 0;
        for (String label : perSourceCount.keySet()) {
            int count = perSourceCount.get(label);
            attributedSum += count;
            attribution.append("[").append(label).append(" ").append(count).append(" 个] ");
        }
        if (attributedSum > totalSites) {
            attribution.append("（双 pages.dev 内容相同，去重后合计 ").append(totalSites).append(" 个）");
        }
        push(listener, context, "发布站归属：" + attribution);

        // Name the sources that stayed unreachable after retries, so a blocked
        // publish host is distinguishable from "no candidates in the data".
        StringBuilder missing = new StringBuilder();
        if (jsA == null || jsA.isEmpty()) missing.append("[huangguoai.pages.dev] ");
        if (jsB == null || jsB.isEmpty()) missing.append("[huang-guo.pages.dev] ");
        for (String label : htmlMissed) missing.append("[").append(label).append("] ");
        for (String label : linkMissed) missing.append("[").append(label).append("] ");
        if (missing.length() > 0) {
            push(listener, context, "⚠ 未连接发布站：" + missing
                    + "（已重试 3 次；常见原因是 DNS 污染或按域名 SNI 阻断，非站点下线）");
        }

        if (ordered.isEmpty()) {
            push(listener, context, "⚠ 所有发布页均未获取到可用地址，已保留上次的自动地址");
            return;
        }

        String bestOrigin = null;
        long bestLatency = Long.MAX_VALUE;
        String bestSource = ""; // publish site that delivered the winning candidate
        String webviewOnly = null; // front door usable, but only via the browser channel
        String webviewOnlySource = "";

        // Phase 1: native probes, six at a time, with an early stop.
        // Sequential probing cost up to 3s per dead candidate (23 × 3s ≈ 70s);
        // batching keeps the wall clock at roughly one timeout per batch and stops
        // as soon as a batch produces a usable origin.
        int nativeHits = 0;
        ExecutorService probePool = Executors.newFixedThreadPool(PROBE_THREADS);
        try {
            for (int base = 0; base < ordered.size() && bestOrigin == null; base += PROBE_THREADS) {
                if (System.currentTimeMillis() > deadline) {
                    push(listener, context, "⏱ 已到时限，提前结束测速");
                    break;
                }
                int end = Math.min(base + PROBE_THREADS, ordered.size());
                List<Future<String>> batch = new ArrayList<>();
                for (int i = base; i < end; i++) {
                    final String candidate = ordered.get(i);
                    final int index = i + 1;
                    if (i == base) {
                        SNAP_PROBE_URL = candidate;
                        SNAP_PROBE_SOURCE = labelsText(provenance.get(candidate));
                        SNAP_PROBE_INDEX = index;
                        SNAP_PROBE_TOTAL = ordered.size();
                    }
                    push(listener, context, "原生测速 " + index + "/" + ordered.size() + "：" + candidate + " …");
                    batch.add(probePool.submit(() -> {
                        long ms = probe(candidate);
                        return candidate + "\u0000" + ms;
                    }));
                }
                for (Future<String> future : batch) {
                    String result;
                    try {
                        result = future.get();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (java.util.concurrent.ExecutionException error) {
                        Throwable cause = error.getCause() == null ? error : error.getCause();
                        String why = reason(cause instanceof Exception ? (Exception) cause : new Exception(cause));
                        push(listener, context, "  ↳ ✗ " + why);
                        note(context, "扫描✗原生 " + why);
                        continue;
                    }
                    int split = result.lastIndexOf('\u0000');
                    String candidate = result.substring(0, split);
                    long ms = Long.parseLong(result.substring(split + 1));
                    nativeHits++;
                    String label = labelsText(provenance.get(candidate));
                    push(listener, context, "  ↳ ✓ " + host(candidate) + " 可用，" + ms + "ms");
                    if (ms < bestLatency) { bestLatency = ms; bestOrigin = candidate; bestSource = label; }
                }
            }
        } finally {
            probePool.shutdownNow();
        }
        push(listener, context, "原生通道结果：" + nativeHits + " / " + ordered.size() + " 个站点可用"
                + (nativeHits == 0 ? "，继续尝试其他通道…" : "，最快 " + bestOrigin));

        // Phase 1.5: Chromium (Cronet) channel. Cronet speaks genuine Chrome TLS,
        // so front doors that reset Java handshakes accept it natively — fast and
        // without a WebView. A followed redirect reveals the real host: probe it
        // natively (real hosts have no filtering) and record the mapping. When the
        // front door serves the page itself, the candidate wins and content flows
        // through the Chromium channel automatically (SiteRepository falls back).
        if (bestOrigin == null && System.currentTimeMillis() <= deadline) {
            push(listener, context, "原生通道全部被拒，改用 Chromium 网络栈测速…");
            // Full sweep: every candidate gets a Chromium chance so all publish
            // sites are actually covered. The loop used to stop at the first hit,
            // which made the run look like it only ever touched one publish site.
            for (int i = 0; i < ordered.size(); i++) {
                if (System.currentTimeMillis() > deadline) {
                    push(listener, context, "⏱ 已到时限，提前结束测速");
                    break;
                }
                String candidate = ordered.get(i);
                String label = labelsText(provenance.get(candidate));
                SNAP_PROBE_URL = candidate;
                SNAP_PROBE_SOURCE = label;
                SNAP_PROBE_INDEX = i + 1;
                SNAP_PROBE_TOTAL = ordered.size();
                push(listener, context, "Chromium 测速 [" + label + "] " + (i + 1) + "/" + ordered.size()
                        + "：" + candidate + " …");
                try {
                    long start = SystemClock.uptimeMillis();
                    // 10s cap so a full sweep still fits inside DEADLINE_MS.
                    CronetFetcher.Response response = CronetFetcher.get(context).fetch(candidate, null, 10_000);
                    long ms = SystemClock.uptimeMillis() - start;
                    if (response.status >= 300) throw new IllegalStateException("HTTP " + response.status);
                    String finalHost = host(response.finalUrl);
                    if (!finalHost.equals(host(candidate))) {
                        String realOrigin = response.finalUrl.replaceAll("(https://[^/]+).*", "$1/");
                        SiteRepository.noteResolution(context, candidate, response.finalUrl);
                        SiteRepository.noteKnownOrigin(context, realOrigin);
                        push(listener, context, "  ↳ 跳转到真实域名 " + realOrigin + "，原生测速中…");
                        try {
                            long nativeMs = probe(realOrigin);
                            push(listener, context, "  ↳ ✓ 可用（原生直连），" + nativeMs + "ms");
                            if (nativeMs < bestLatency) { bestLatency = nativeMs; bestOrigin = realOrigin; bestSource = label; }
                        } catch (Exception nativeError) {
                            push(listener, context, "  ↳ ✓ 真实域名走 Chromium 通道可用，" + ms + "ms");
                            if (ms < bestLatency) { bestLatency = ms; bestOrigin = candidate; bestSource = label; }
                        }
                    } else {
                        push(listener, context, "  ↳ ✓ 可用（Chromium 通道），" + ms + "ms");
                        if (ms < bestLatency) { bestLatency = ms; bestOrigin = candidate; bestSource = label; }
                    }
                } catch (Exception error) {
                    push(listener, context, "  ↳ ✗ " + reason(error));
                    note(context, "扫描✗Chromium " + host(candidate) + "：" + reason(error));
                }
            }
        }

        // Phase 2: nothing answered natively — walk the front doors with the
        // browser channel, in the same .ai-first order as Phase 1/1.5. A redirect
        // reveals the real host (verify it through the same browser channel); no
        // redirect means the page still loaded, so the front door itself is usable
        // through the WebView fallback.
        if (bestOrigin == null && System.currentTimeMillis() <= deadline) {
            push(listener, context, "原生与 Chromium 通道均被拒，改用内置浏览器通道解析前门…");
            int tries = 0;
            for (int i = 0; i < ordered.size(); i++) {
                if (bestOrigin != null || System.currentTimeMillis() > deadline) break;
                if (tries >= MAX_RESOLVE_BASES) break;
                String front = ordered.get(i);
                String label = labelsText(provenance.get(front));
                SNAP_PROBE_URL = front;
                SNAP_PROBE_SOURCE = label;
                SNAP_PROBE_INDEX = i + 1;
                SNAP_PROBE_TOTAL = ordered.size();
                tries++;
                push(listener, context, "解析前门 [" + label + "] " + (i + 1) + "/" + ordered.size()
                        + "：" + front + " …");
                try {
                    // Tight load timeout (6s, 2 attempts ≈ 12s worst case) so a single
                    // hung front door cannot eat the whole deadline. Front doors
                    // that need a second chance will still be revisited later in
                    // the ordered sweep; do NOT let one stalled WebView abort the
                    // remaining sites.
                    String finalUrl = WebViewFetcher.get(context).resolve(front, 2, 4000, 6000);
                    if (finalUrl == null || !finalUrl.startsWith("https://")) {
                        push(listener, context, "  ↳ ✗ 解析失败：" + WebViewFetcher.get(context).diagnostics());
                        continue;
                    }
                    String realOrigin = finalUrl.replaceAll("(https://[^/]+).*", "$1/");
                    if (!host(realOrigin).equals(host(front))) {
                        SiteRepository.noteResolution(context, front, finalUrl);
                        SiteRepository.noteKnownOrigin(context, realOrigin);
                        push(listener, context, "  ↳ 跳转到真实域名 " + realOrigin + "，用浏览器通道验证内容…");
                        // The real host's content channel is also CDN-filtered, so a
                        // native probe() is reset/empty. Verify through the same
                        // browser channel that just landed on finalUrl (same-origin
                        // fetch of the already-loaded page, browser TLS fingerprint)
                        // — this is what verifyOrigin does on manual save, and it
                        // works where native fails. Fetch finalUrl itself, not a
                        // guessed content path: it is the exact URL the WebView
                        // already loaded and that the front door permitted.
                        long start = SystemClock.uptimeMillis();
                        byte[] body = WebViewFetcher.get(context).fetch(finalUrl);
                        long ms = SystemClock.uptimeMillis() - start;
                        if (body.length < 200) throw new IllegalStateException("空页面");
                        push(listener, context, "  ↳ ✓ 可用（浏览器通道验证），" + ms + "ms");
                        if (ms < bestLatency) { bestLatency = ms; bestOrigin = realOrigin; bestSource = label; }
                    } else if (webviewOnly == null) {
                        webviewOnly = front;
                        webviewOnlySource = label;
                        push(listener, context, "  ↳ ✓ 前门未跳转但浏览器通道可达，内容将走浏览器通道（稍慢）");
                    } else {
                        push(listener, context, "  ↳ ✓ 前门未跳转但浏览器通道可达（已有备选）");
                    }
                } catch (Exception error) {
                    push(listener, context, "  ↳ ✗ " + reason(error));
                    note(context, "扫描✗浏览器 " + host(front) + "：" + reason(error));
                }
            }
            if (bestOrigin == null && webviewOnly != null) {
                bestOrigin = webviewOnly;
                bestSource = webviewOnlySource;
                bestLatency = 0; // unknown: shown as "?" in the UI
            }
        }

        if (bestOrigin != null) {
            if (silentPhase) {
                // Background pass: the active origin was already working, so this is
                // an optimisation, not a rescue. Replace only on a decisive win.
                considerUpgrade(context, listener, bestOrigin, bestLatency, bestSource);
            } else {
                save(context, bestOrigin, bestLatency, bestSource);
                note(context, "扫描✓采用 " + bestOrigin + (bestLatency > 0 ? " " + bestLatency + "ms" : " 浏览器通道")
                        + " 来源 " + bestSource);
                push(listener, context, "✓ 自动检测完成，最快地址：" + bestOrigin
                        + (bestLatency > 0 ? "（" + bestLatency + "ms）" : "（浏览器通道）"));
            }
        } else if (silentPhase) {
            note(context, "静默优选：本轮无可用候选，保留当前地址");
        } else {
            // Failed: clear the success timestamp so the NEXT startIfStale (next
            // app launch or 4-hour interval) will run a fresh full sweep instead
            // of short-circuiting on a stale "checked" stamp.
            prefs(context).edit().putLong("autoCheckedAt", 0).apply();
            push(listener, context, "✗ 自动检测结束，本次未找到可用地址（所有通道均被拒，下次启动将自动重试）");
        }
    }

    /** Persists the chosen origin as the automatic site (plus which publish site
     *  delivered it, so the settings page can attribute the line-4 entry). */
    private static void save(Context context, String origin, long latency, String source) {
        prefs(context).edit()
                .putString("autoOrigin", origin)
                .putString("autoOriginSource", source == null ? "" : source)
                .putLong("autoLatency", latency)
                .putLong("autoCheckedAt", System.currentTimeMillis())
                .apply();
    }

    private static void push(Listener listener, Context context, String message) {
        CURRENT_PROGRESS = message;
        if (listener != null) listener.onResult(status(context));
    }

    /** Mirrors a scan verdict into the black-box log, so the settings page's
     *  diagnostic line shows which candidates were dropped and why. */
    private static void note(Context context, String line) {
        try { MainActivity.noteDiagnostic(context, line); } catch (Exception ignored) { }
    }

    private static String host(String origin) {
        return origin.replaceFirst("^https?://", "").replaceFirst("/.*$", "");
    }

    /** Draws {@code count} DISTINCT subdomains from the publish page's pool.
     *  The page itself renders `urls.length * 3` lines with unique subdomains, so
     *  sampling fewer (or allowing repeats) probes a different, narrower set of
     *  hosts than the one the site advertises. When the pool is smaller than the
     *  request the draw wraps around with a fresh shuffle. */
    private static List<String> pickSubdomains(List<String> pool, int count) {
        if (pool == null || pool.isEmpty()) return List.of("www");
        List<String> out = new ArrayList<>();
        while (out.size() < count) {
            List<String> round = new ArrayList<>(pool);
            java.util.Collections.shuffle(round, RANDOM);
            for (String sub : round) {
                if (out.size() >= count) break;
                if (!out.contains(sub)) out.add(sub);
            }
            if (out.size() < count && out.size() >= pool.size()) break; // pool exhausted
        }
        if (out.isEmpty()) out.add("www");
        return out;
    }

    /** Short human label for a link-scan source, used as the log prefix. */
    private static String linkLabel(String sourceUrl) {
        if (sourceUrl.contains("gitlab.com")) return "gitlab.com/huangguotv";
        return host(sourceUrl);
    }

    /** Every https host mentioned in a publish document, filtered down to hosts that
     *  are plausibly a content front door. */
    private static List<String> contentLinks(String body, String label) {
        List<String> out = new ArrayList<>();
        Matcher links = Pattern.compile("https://([a-zA-Z0-9.-]+\\.[a-z]{2,})").matcher(body);
        while (links.find()) {
            String candidate = links.group(1).toLowerCase(java.util.Locale.ROOT);
            if (!isContentHost(candidate, label)) continue;
            String url = "https://" + candidate + "/";
            if (!out.contains(url)) out.add(url);
        }
        return out;
    }

    /** Publish pages, social channels and bare landing domains are not content
     *  origins: probing one returns the publish page itself, and the app would then
     *  show that page as the "site". Only sub.domain.tld fronts (plus the known
     *  content apex) are accepted. */
    private static boolean isContentHost(String candidate, String label) {
        if (candidate.equals(label) || candidate.endsWith("." + label)) return false;
        if (candidate.endsWith("pages.dev") || candidate.contains("gitlab")
                || candidate.contains("github") || candidate.equals("t.me")
                || candidate.endsWith(".t.me") || candidate.equals("x.com")
                || candidate.contains("twitter") || candidate.contains("tiktok")
                || candidate.contains("youtube") || candidate.contains("tgme")) return false;
        if (candidate.equals("huangguoai.ai") || candidate.equals("huangguoai.me")) return false;
        if (candidate.matches("hgai\\d+\\.(com|ai|me|cc)")) return false;
        if (candidate.equals("huangguoai.com")) return true;
        return candidate.split("\\.").length >= 3;
    }

    /** Short human label for a publish source URL, used as the log prefix. */
    private static String shortLabel(String sourceUrl) {
        String host = sourceUrl.replaceFirst("^https?://", "").replaceFirst("/.*$", "");
        if (host.contains("pages.dev")) {
            // The two Cloudflare publish pages both point at distinct hosts.
            return host; // e.g. huangguoai.pages.dev / huang-guo.pages.dev
        }
        return host; // e.g. huangguoai.ai
    }

    /**
     * Renders the publish-site labels that delivered a candidate. A URL published
     * by both pages.dev files shows both names so the log proves which sources
     * actually contributed it; the .ai line comes first when present.
     */
    private static String labelsText(List<String> labels) {
        if (labels == null || labels.isEmpty()) return "未知来源";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0) text.append("+");
            text.append(labels.get(i));
        }
        return text.toString();
    }

    /** Short human-readable failure reason for the progress line. */
    private static String reason(Exception error) {
        if (error instanceof SocketTimeoutException) return "连接超时";
        String message = error.getMessage();
        return message == null || message.isEmpty() ? "连接失败" : message;
    }

    /** Native reachability probe; returns round-trip milliseconds. Anything that
     *  is not a 2xx/3xx — including Cloudflare 403 challenges and TLS resets —
     *  counts as unusable for direct content fetching. */
    private static long probe(String origin) throws Exception {
        return probe(origin, PROBE_CONNECT_MS, PROBE_READ_MS);
    }

    private static long probe(String origin, int connectMs, int readMs) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(origin).openConnection();
        connection.setConnectTimeout(connectMs);
        connection.setReadTimeout(readMs);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", CHROME_UA);
        try {
            long start = SystemClock.uptimeMillis();
            int code = connection.getResponseCode();
            long ms = SystemClock.uptimeMillis() - start;
            if (code < 200 || code >= 400) throw new IllegalStateException("HTTP " + code);
            return ms;
        } finally {
            connection.disconnect();
        }
    }

    /** A 200 does not prove the host serves the drama app: publish pages, parking
     *  pages and half-migrated mirrors all answer 200. Require a real listing
     *  marker before an origin is adopted. */
    private static boolean verifyContent(String origin) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(origin).openConnection();
            connection.setConnectTimeout(PROBE_CONNECT_MS);
            connection.setReadTimeout(PROBE_READ_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", CHROME_UA);
            if (connection.getResponseCode() != 200) return false;
            try (InputStream stream = connection.getInputStream()) {
                byte[] buffer = new byte[16_384];
                int read = 0;
                while (read < buffer.length) {
                    int count = stream.read(buffer, read, buffer.length - read);
                    if (count < 0) break;
                    read += count;
                }
                String head = new String(buffer, 0, Math.max(read, 0), "UTF-8");
                return head.contains("hg-drama-card") || head.contains("/video/");
            }
        } catch (Exception error) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Extracts quoted entries from a publish.js array.
     *  Base domains are deliberately NOT deduplicated: the publish file repeats a
     *  base to multiply the number of distinct hosts it advertises (the page emits
     *  `urls.length * 3` lines). Collapsing duplicates halved our candidate list. */
    private static void collect(String block, List<String> into, boolean isSubdomain) {
        Matcher quoted = QUOTED.matcher(block);
        while (quoted.find()) {
            String value = quoted.group(1).trim();
            if (value.isEmpty() || value.startsWith("//") || value.startsWith("/*")) continue;
            if (isSubdomain) {
                if (value.matches("[a-z0-9]{2,12}")) into.add(value);
            } else {
                String base = value.replaceFirst("(?i)^https?://", "").replaceFirst("/+$", "");
                if (base.matches("[a-z0-9.-]+\\.[a-z]{2,}") && !base.contains("pages.dev")
                        && !base.contains("gitlab") && !base.contains("huangguoai.com")
                        && !base.contains("huangguoai.ai")) into.add(base);
            }
        }
    }

    /**
     * Fetches a publish source. Retries a few times: the reachable publish host
     * answers with a TCP reset when it is hit repeatedly (observed: several
     * requests in a row are reset, then a short cooldown restores service), so a
     * single attempt turns a perfectly usable source into "0/3 connected".
     */
    private static String get(String url) {
        // A connection-class failure (reset / unknown host / refused) is a property
        // of the network path, not a transient hiccup: retrying it three times cost
        // ~30s per dead source and never once succeeded. Only timeouts get a second
        // chance.
        for (int attempt = 1; attempt <= 2; attempt++) {
            Outcome outcome = getOnce(url);
            if (outcome.body != null && !outcome.body.isEmpty()) return outcome.body;
            if (outcome.connectFailure) return null;
            if (attempt < 2) {
                try { Thread.sleep(300L); } catch (InterruptedException stop) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    private static final class Outcome {
        final String body;
        final boolean connectFailure;
        Outcome(String body, boolean connectFailure) { this.body = body; this.connectFailure = connectFailure; }
    }

    private static Outcome getOnce(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(PUBLISH_CONNECT_MS);
            connection.setReadTimeout(PUBLISH_READ_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", CHROME_UA);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) return new Outcome(null, false);
            try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
                return new Outcome(bytes.toString("UTF-8"), false);
            }
        } catch (java.net.UnknownHostException | java.net.SocketException error) {
            // UnknownHostException / ConnectException / "Connection reset" all land
            // here (ConnectException extends SocketException): the path is blocked,
            // so a retry cannot help.
            return new Outcome(null, true);
        } catch (Exception error) {
            return new Outcome(null, false);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("site_discovery", Context.MODE_PRIVATE);
    }
}
