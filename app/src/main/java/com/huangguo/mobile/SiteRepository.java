package com.huangguo.mobile;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class SiteRepository {
    private final SiteProfile profile;
    private final File coverDir;
    private final Context appContext;

    SiteRepository(Context context) { this(context, HotUpdateManager.get(context).currentSite()); }

    SiteRepository(Context context, SiteProfile profile) {
        this.profile = profile;
        appContext = context.getApplicationContext();
        ensureMappingStore(appContext);
        // v2：封面统一下采样 + WebP 转码后落盘，旧目录里是原图（单张可达 3.6MB），直接丢弃。
        File legacyCovers = new File(context.getCacheDir(), "covers");
        if (legacyCovers.exists()) deleteTree(legacyCovers);
        coverDir = new File(context.getCacheDir(), "covers-v2");
        if (!coverDir.exists()) coverDir.mkdirs();
    }

    /** Active site origin, used by the player to send a matching Referer header. */
    String origin() { return profile.origin; }

    /** Hosts whose native stack failed recently go straight to the WebView channel for a while. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> NATIVE_COOLDOWN = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long NATIVE_COOLDOWN_MS = 300_000;

    /** Filtered front-door host -> real host discovered behind its redirects. */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> RESOLVED_HOSTS = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile android.content.SharedPreferences mappingStore;

    private static void ensureMappingStore(Context context) {
        if (mappingStore == null) {
            synchronized (SiteRepository.class) {
                if (mappingStore == null) {
                    android.content.SharedPreferences prefs =
                            context.getApplicationContext().getSharedPreferences("site_host_map", Context.MODE_PRIVATE);
                    for (java.util.Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                        RESOLVED_HOSTS.put(entry.getKey(), String.valueOf(entry.getValue()));
                    }
                    mappingStore = prefs;
                }
            }
        }
    }

    /** Records that {@code fromUrl} actually lands on {@code toUrl} after redirects. */
    static void noteResolution(Context context, String fromUrl, String toUrl) {
        try {
            String from = new URL(fromUrl).getHost();
            String to = new URL(toUrl).getHost();
            if (from != null && to != null && !from.equals(to)) {
                RESOLVED_HOSTS.put(from, to);
                ensureMappingStore(context);
                if (mappingStore != null) mappingStore.edit().putString(from, to).apply();
            }
        } catch (Exception ignored) { }
    }

    /** The real host discovered behind {@code url}'s host, or null when unresolved. */
    static String resolvedHostFor(String url) {
        try { return RESOLVED_HOSTS.get(new URL(url).getHost()); } catch (Exception error) { return null; }
    }

    /** All persisted front-door → real-host mappings, loading the store on first use. */
    static java.util.Map<String, String> persistedResolutions(Context context) {
        ensureMappingStore(context.getApplicationContext());
        return new java.util.LinkedHashMap<>(RESOLVED_HOSTS);
    }

    /** Origins that have actually served site content (verified or probed), newest first. */
    private static final java.util.List<String> KNOWN_ORIGINS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile android.content.SharedPreferences knownStore;

    private static void ensureKnownStore(Context context) {
        if (knownStore == null) {
            synchronized (SiteRepository.class) {
                if (knownStore == null) {
                    android.content.SharedPreferences prefs = context.getApplicationContext()
                            .getSharedPreferences("site_known_origins", Context.MODE_PRIVATE);
                    try {
                        org.json.JSONArray array = new org.json.JSONArray(prefs.getString("origins", "[]"));
                        for (int i = 0; i < array.length(); i++) {
                            String value = array.optString(i);
                            if (!value.isEmpty()) KNOWN_ORIGINS.add(value);
                        }
                    } catch (Exception ignored) { }
                    knownStore = prefs;
                }
            }
        }
    }

    /** Records that {@code origin} (a bare host or https://host) actually served content. */
    static void noteKnownOrigin(Context context, String origin) {
        try {
            String value = origin.replaceAll("(?i)^https?://", "").replaceFirst("/.*$", "")
                    .trim().toLowerCase(java.util.Locale.ROOT);
            if (!value.matches("[a-z0-9.-]+\\.[a-z]{2,}")) return;
            value = "https://" + value + "/";
            ensureKnownStore(context.getApplicationContext());
            if (!KNOWN_ORIGINS.isEmpty() && KNOWN_ORIGINS.get(0).equals(value)) return; // no-op write guard
            KNOWN_ORIGINS.remove(value);
            KNOWN_ORIGINS.add(0, value);
            while (KNOWN_ORIGINS.size() > 8) KNOWN_ORIGINS.remove(KNOWN_ORIGINS.size() - 1);
            org.json.JSONArray array = new org.json.JSONArray();
            for (String entry : KNOWN_ORIGINS) array.put(entry);
            if (knownStore != null) knownStore.edit().putString("origins", array.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** All origins known to have served site content, newest first. */
    static java.util.List<String> knownOrigins(Context context) {
        ensureKnownStore(context.getApplicationContext());
        return new java.util.ArrayList<>(KNOWN_ORIGINS);
    }

    /**
     * 整条链路的预算。原生、Cronet、WebView 三级回退各自的超时累加会到一两分钟，
     * 而 JS 那边只等 20~30 秒，于是必然报"请求超时"。这里统一用截止时间兜住。
     */
    private static final long FETCH_BUDGET_MS = 25_000;

    private byte[] fetch(String url) throws Exception {
        URL parsed = new URL(url);
        if (!"https".equals(parsed.getProtocol())) throw new IllegalArgumentException("仅支持 HTTPS");
        String host = parsed.getHost();
        long deadline = android.os.SystemClock.elapsedRealtime() + FETCH_BUDGET_MS;
        boolean nativeTried = false;
        // 1. A front door we already resolved: go straight to the real host natively.
        String mapped = RESOLVED_HOSTS.get(host);
        if (mapped != null && !mapped.equals(host)) {
            nativeTried = true;
            try { return nativeFetch(rewriteHost(parsed, mapped)); }
            catch (Exception error) {
                RESOLVED_HOSTS.remove(host, mapped);
                if (mappingStore != null) mappingStore.edit().remove(host).apply();
            }
        }
        // 2. Fast path: native stack. 上一步已经用同一台主机试过就不再重复，省掉一次 20 秒。
        long left = deadline - android.os.SystemClock.elapsedRealtime();
        try {
            if (!nativeTried && left > 2_000
                    && System.currentTimeMillis() >= NATIVE_COOLDOWN.getOrDefault(host, 0L)) {
                nativeTried = true;
                byte[] body = nativeFetch(parsed);
                NATIVE_COOLDOWN.remove(host);
                return body;
            }
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            // Some CDNs drop non-browser TLS handshakes; remember and retry later natively.
            NATIVE_COOLDOWN.put(host, System.currentTimeMillis() + NATIVE_COOLDOWN_MS);
        }
        // 3. Chromium (Cronet) channel: genuine Chrome TLS fingerprint passes the
        //    JA3 filtering natively — far lighter than spinning up a WebView. A
        //    redirect chain landing on a different host is recorded, so later
        //    requests go straight to the real host over the fast native stack.
        try {
            long cronetBudget = Math.max(3_000,
                    Math.min(15_000, deadline - android.os.SystemClock.elapsedRealtime()));
            CronetFetcher.Response response = CronetFetcher.get(appContext)
                    .fetch(url, profileHeaders(), cronetBudget);
            if (response.status < 200 || response.status >= 300) {
                throw new IllegalStateException("站点请求失败（" + response.status + "）");
            }
            String finalHost = new URL(response.finalUrl).getHost();
            if (finalHost != null && !finalHost.equals(host)) {
                noteResolution(appContext, url, response.finalUrl);
            }
            return response.body;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            if (error instanceof IllegalStateException) throw error;
            // fall through to the WebView
        }
        // 4. Last resort: discover the host behind the filtered front door with
        //    the browser engine, then serve everything else from the fast native stack.
        long remaining = deadline - android.os.SystemClock.elapsedRealtime();
        if (remaining < 8_000) {
            throw new IOException("站点连接超时（已尝试原生与浏览器通道）");
        }
        WebViewFetcher web = WebViewFetcher.get(appContext);
        try {
            // 只在剩余预算内导航一次，避免又用满 30 秒。
            String finalUrl = web.resolve(url, 1, 1_500, Math.min(8_000, remaining - 3_000));
            if (finalUrl != null && finalUrl.startsWith("https://")) {
                String finalHost = new URL(finalUrl).getHost();
                if (finalHost != null && !finalHost.equals(host)) {
                    RESOLVED_HOSTS.put(host, finalHost);
                    try { return nativeFetch(rewriteHost(parsed, finalHost)); }
                    catch (Exception ignored) { /* real host unreachable natively; pull via WebView */ }
                }
                return web.fetch(url);
            }
        } catch (Exception error) {
            if (error instanceof IOException) throw error;
        }
        throw new IOException("站点连接失败（原生与浏览器通道均未连通）");
    }

    private static URL rewriteHost(URL parsed, String newHost) throws Exception {
        return new URL("https", newHost, parsed.getFile());
    }

    /** Site profile request headers with the {@code {origin}} placeholder resolved. */
    private java.util.Map<String, String> profileHeaders() throws Exception {
        JSONObject source = profile.object("headers");
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        for (java.util.Iterator<String> keys = source.keys(); keys.hasNext();) {
            String key = keys.next();
            headers.put(key, source.getString(key).replace("{origin}", profile.origin));
        }
        return headers;
    }

    private byte[] nativeFetch(URL parsed) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) parsed.openConnection();
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(12_000);
        for (java.util.Map.Entry<String, String> header : profileHeaders().entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        connection.setUseCaches(false);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IllegalStateException("站点请求失败（" + status + "）");
            try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
                return bytes.toByteArray();
            }
        } catch (IOException error) {
            throw new IOException("站点连接失败", error);
        } finally {
            connection.disconnect();
        }
    }

    private Document page(String path) throws Exception {
        if (!path.startsWith("/") || path.startsWith("//")) throw new IllegalArgumentException("无效路径");
        byte[] body = fetch(profile.origin + path);
        if (body.length < 1000) throw new IllegalStateException("站点暂时返回空页面");
        // This origin just served real site content — bank it for discovery's
        // known-origin sweep. No user action (save/test) should be required.
        noteKnownOrigin(appContext, profile.origin);
        return Jsoup.parse(new String(body, StandardCharsets.UTF_8), profile.origin);
    }

    /** Absolute-URL variant of {@link #page(String)}; rejects foreign hosts. */
    private Document pageAbsolute(String url) throws Exception {
        URL parsed = new URL(url);
        String expected = new URL(profile.origin).getHost();
        if (parsed.getHost() == null || !parsed.getHost().equalsIgnoreCase(expected)) {
            throw new IllegalArgumentException("无效路径");
        }
        byte[] body = fetch(url);
        if (body.length < 1000) throw new IllegalStateException("站点暂时返回空页面");
        noteKnownOrigin(appContext, profile.origin);
        return Jsoup.parse(new String(body, StandardCharsets.UTF_8), profile.origin);
    }

    /**
     * POST JSON to the site API using the same browser-ish header set as page
     * fetches. The 2026-11 redesign moved playback behind
     * {@code POST /api/videos/{id}/episodes/{n}/playback}: the episode HTML now
     * ships an EMPTY videoSrc, so the signed manifest URL only exists in this
     * response.
     */
    private byte[] postJson(String path, String payload) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(profile.origin + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(12_000);
        connection.setDoOutput(true);
        connection.setUseCaches(false);
        for (java.util.Map.Entry<String, String> header : profileHeaders().entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        try {
            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream out = connection.getOutputStream()) { out.write(body); }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IllegalStateException("播放接口请求失败（" + status + "）");
            try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
                return bytes.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    /** Resolves the signed manifest for one episode. Returns the inner data object. */
    private JSONObject playbackPayload(String id, int number) throws Exception {
        byte[] body = postJson("/api/videos/" + id + "/episodes/" + number + "/playback", "{}");
        JSONObject root = new JSONObject(new String(body, StandardCharsets.UTF_8));
        JSONObject data = root.optJSONObject("data");
        if (data == null) {
            String message = root.optString("msg", "");
            throw new IllegalStateException(message.isEmpty() ? "播放接口返回异常" : message);
        }
        return data;
    }

    private static String text(Element element) {
        return element == null ? "" : element.text().replaceAll("\\s+", " ").trim();
    }

    /** selectFirst that tolerates a missing selector (older/hot-updated profile). */
    private static Element first(Element root, String selector) {
        return root == null || selector == null || selector.isEmpty() ? null : root.selectFirst(selector);
    }

    private static String attr(Element element, String name) {
        return element == null ? "" : element.attr(name);
    }

    private String cover(Element card) {
        Element image = card.selectFirst(profile.selector("cover"));
        String value = attr(image, profile.attribute("cover"));
        return value.isEmpty() ? attr(image, profile.attribute("coverFallback")) : value;
    }

    private JSONArray cards(Element document, String category) throws Exception {
        JSONArray items = new JSONArray();
        Set<String> ids = new HashSet<>();
        for (Element card : document.select(profile.selector("cards"))) {
            String id = card.attr(profile.attribute("id"));
            if (!id.matches("\\d+") || !ids.add(id)) continue;
            Element titleNode = card.selectFirst(profile.selector("title"));
            if (titleNode != null) titleNode = titleNode.clone();
            if (titleNode != null) titleNode.select(profile.selector("removeTitle")).remove();
            String title = text(titleNode);
            if (title.isEmpty()) title = card.attr(profile.attribute("title"));
            if (title.isEmpty()) title = "剧集 " + id;
            JSONObject item = new JSONObject();
            item.put("id", id);
            item.put("title", title);
            item.put("cover", cover(card));
            item.put("description", text(card.selectFirst(profile.selector("description"))));
            item.put("score", text(card.selectFirst(profile.selector("score"))));
            item.put("episodeLabel", text(card.selectFirst(profile.selector("episodeLabel"))));
            String mappedCategory = category;
            if (mappedCategory.isEmpty()) {
                mappedCategory = profile.object("categoryMap").optString(card.attr(profile.attribute("type")));
            }
            if (!mappedCategory.isEmpty()) item.put("category", mappedCategory);
            JSONArray tags = new JSONArray();
            for (Element tag : card.select(profile.selector("tags"))) {
                if (tags.length() >= 3) break;
                Element clean = tag.clone();
                clean.select(profile.selector("removeTag")).remove();
                String value = text(clean);
                if (!value.isEmpty()) tags.put(value);
            }
            item.put("tags", tags);
            items.put(item);
        }
        return items;
    }

    private JSONObject listing(Document document, int currentPage, String category) throws Exception {
        JSONObject result = new JSONObject();
        result.put("items", cards(document, category));
        // ".hg-page-header__extra" reads "共1697部": the site-wide total for this
        // listing, not the number of cards on this page.
        String totalText = text(first(document, profile.selector("pageTotal")));
        java.util.regex.Matcher totalMatch = java.util.regex.Pattern.compile("([0-9]+)").matcher(totalText);
        result.put("total", totalMatch.find() ? Integer.parseInt(totalMatch.group(1)) : 0);
        int next = Integer.MAX_VALUE;
        for (Element link : document.select(profile.selector("pager"))) {
            String href = link.attr("href");
            java.util.regex.Matcher match = profile.pattern("page", null).matcher(href);
            if (!match.find()) continue;
            int page = Integer.parseInt(match.group(1));
            if (page > currentPage && page < next) next = page;
        }
        result.put("nextPage", next == Integer.MAX_VALUE ? JSONObject.NULL : next);
        return result;
    }

    JSONObject home() throws Exception {
        Document root = page(profile.route("home"));
        JSONArray featured = new JSONArray();
        Element heroData = root.selectFirst(profile.selector("hero"));
        if (heroData != null) {
            try {
                JSONArray slides = new JSONArray(heroData.data().isEmpty() ? heroData.text() : heroData.data());
                for (int i = 0; i < slides.length(); i++) {
                    JSONObject slide = slides.optJSONObject(i);
                    if (slide == null) continue;
                    slide = profile.mapJson(slide, "hero");
                    if (slide.optBoolean("isAd") || slide.optInt("adId") != 0) continue;
                    String href = slide.optString("href");
                    java.util.regex.Matcher match = profile.pattern("heroId", null).matcher(profile.relative(href));
                    if (!match.find()) continue;
                    JSONObject item = new JSONObject();
                    item.put("id", match.group(1));
                    item.put("title", slide.optString("title"));
                    item.put("cover", slide.optString("cover"));
                    item.put("description", slide.optString("desc"));
                    item.put("score", slide.optString("score"));
                    item.put("episodeLabel", slide.optString("episode"));
                    featured.put(item);
                }
            } catch (Exception ignored) { /* 无轮播时展示推荐目录 */ }
        }
        JSONObject result = new JSONObject();
        result.put("featured", featured);
        result.put("items", cards(root, ""));
        for (Element section : root.select(profile.selector("sections"))) {
            String heading = text(section.selectFirst(profile.selector("sectionTitle")));
            if (profile.object("sectionNames").optString("recommend").equals(heading)) result.put("homepageRecommend", cards(section, ""));
            if (profile.object("sectionNames").optString("newest").equals(heading)) result.put("homepageNewest", cards(section, ""));
            if (profile.object("sectionNames").optString("hot").equals(heading)) result.put("homepageHotSection", cards(section, ""));
        }
        // The 热播短剧 row is sourced from /ranks/hot/ (the same data the rank
        // page's default tab shows). Fall back to the home page's own 热播短剧
        // section when the rank page is unavailable, so the row never vanishes.
        try { result.put("homepageHot", rank("hot").getJSONArray("items")); }
        catch (Exception error) {
            JSONArray fallback = result.optJSONArray("homepageHotSection");
            result.put("homepageHot", fallback == null ? new JSONArray() : fallback);
        }
        try { result.put("recommend", listing(page(profile.route("recommend")), 1, "")); }
        catch (Exception error) { result.put("recommend", new JSONObject().put("items", result.getJSONArray("items")).put("nextPage", JSONObject.NULL)); }
        try { result.put("newest", listing(page(profile.route("newest")), 1, "")); }
        catch (Exception error) { result.put("newest", new JSONObject().put("items", new JSONArray()).put("nextPage", JSONObject.NULL)); }
        if (featured.length() == 0 && result.getJSONArray("items").length() == 0) throw new IllegalStateException("首页内容暂时不可用");
        return result;
    }

    /** Topic hub: /topics/ renders one `a.hg-topic-card` per collection. */
    JSONObject topics() throws Exception {
        String cardSelector = profile.selector("topicCards");
        if (cardSelector.isEmpty()) throw new IllegalStateException("当前站点配置不支持专题");
        Document document = page(profile.route("topics"));
        JSONArray items = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (Element card : document.select(cardSelector)) {
            java.util.regex.Matcher match = java.util.regex.Pattern.compile("/topics/([^/\"]+)/").matcher(card.attr("href"));
            if (!match.find()) continue;
            String slug = match.group(1);
            if (!seen.add(slug)) continue;
            Element image = card.selectFirst("img");
            String cover = image == null ? "" : (image.hasAttr("data-src") ? image.attr("data-src") : image.attr("src"));
            String title = text(first(card, profile.selector("topicTitle")));
            if (title.isEmpty()) {
                Element alt = card.selectFirst("img[alt]");
                title = alt == null ? "专题" : alt.attr("alt");
            }
            String meta = text(first(card, profile.selector("topicMeta")));
            java.util.regex.Matcher countMatch = java.util.regex.Pattern.compile("([0-9]+)").matcher(meta);
            JSONObject item = new JSONObject();
            item.put("slug", slug);
            item.put("title", title);
            item.put("cover", cover);
            item.put("count", countMatch.find() ? Integer.parseInt(countMatch.group(1)) : 0);
            item.put("description", text(first(card, profile.selector("topicDesc"))));
            items.put(item);
        }
        JSONObject result = new JSONObject();
        result.put("items", items);
        result.put("total", items.length());
        return result;
    }

    /** One topic's drama list; paginated at /topics/{slug}/{page}/. */
    JSONObject topic(String slug, int pageNumber) throws Exception {
        if (slug == null || !slug.matches("[A-Za-z0-9%_.-]{1,80}") || pageNumber < 1 || pageNumber > 50) {
            throw new IllegalArgumentException("无效专题");
        }
        String path = pageNumber == 1
                ? profile.route("topic", "slug", slug)
                : profile.route("topicPage", "slug", slug, "page", String.valueOf(pageNumber));
        if (path.isEmpty()) throw new IllegalStateException("当前站点配置不支持专题");
        Document document = page(path);
        JSONObject result = listing(document, pageNumber, "");
        String title = text(document.selectFirst(".hg-breadcrumb__current"));
        if (title.isEmpty()) title = text(document.selectFirst("h1"));
        result.put("title", title.replaceAll("介绍$", "").trim());
        result.put("slug", slug);
        if (result.getJSONArray("items").length() == 0) throw new IllegalStateException("专题内容暂时不可用");
        return result;
    }

    private static final Set<String> RANK_KINDS = new HashSet<>(Arrays.asList("hot", "recommend", "potential"));

    /** /ranks/{hot|recommend|potential}/ — a single page of ranked dramas. */
    JSONObject rank(String kind) throws Exception {
        if (kind == null || !RANK_KINDS.contains(kind)) throw new IllegalArgumentException("无效榜单");
        String cardSelector = profile.selector("rankCards");
        if (cardSelector.isEmpty()) throw new IllegalStateException("当前站点配置不支持榜单");
        Document document = page(profile.route("ranks", "kind", kind));
        JSONArray items = new JSONArray();
        Set<String> ids = new HashSet<>();
        for (Element card : document.select(cardSelector)) {
            String id = card.attr("data-track-id");
            if (!id.matches("\\d+") || !ids.add(id)) continue;
            Element image = card.selectFirst("img");
            String cover = image == null ? "" : (image.hasAttr("data-src") ? image.attr("data-src") : image.attr("src"));
            String title = text(first(card, profile.selector("rankTitle")));
            if (title.isEmpty()) title = card.attr("data-track-title");
            if (title.isEmpty()) title = "剧集 " + id;
            JSONObject item = new JSONObject();
            item.put("id", id);
            item.put("title", title);
            item.put("cover", cover);
            item.put("episodeLabel", text(first(card, profile.selector("rankEpisode"))));
            // ".hg-rank-item__score" renders "9.5分" (star icon + text); keep the
            // raw text so rank cards look identical to every other listing card.
            item.put("score", text(first(card, profile.selector("rankScore"))));
            items.put(item);
        }
        JSONObject result = new JSONObject();
        result.put("items", items);
        result.put("total", items.length());
        result.put("nextPage", JSONObject.NULL);
        result.put("kind", kind);
        return result;
    }

    /** Pool for the vertical recommendation feed: hot ranking first, then the
     *  newest page, de-duplicated. Order matters — it is the swipe order. */
    JSONObject feedPool() throws Exception {
        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<>();
        try { appendUnique(out, seen, rank("hot").optJSONArray("items")); } catch (Exception ignored) { }
        try { appendUnique(out, seen, recommend(1, true).optJSONArray("items")); } catch (Exception ignored) { }
        if (out.length() == 0) throw new IllegalStateException("推荐池暂时不可用");
        JSONObject result = new JSONObject();
        result.put("items", out);
        return result;
    }

    private static void appendUnique(JSONArray out, Set<String> seen, JSONArray items) throws Exception {
        if (items == null) return;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id");
            if (id.isEmpty() || !seen.add(id)) continue;
            JSONObject slim = new JSONObject();
            slim.put("id", id);
            slim.put("title", item.optString("title"));
            slim.put("cover", item.optString("cover"));
            slim.put("episodeLabel", item.optString("episodeLabel"));
            slim.put("score", item.optString("score"));
            out.put(slim);
        }
    }

    JSONObject category(String id, int pageNumber) throws Exception {
        if (!profile.categoryAllowed(id) || pageNumber < 1 || pageNumber > 100) throw new IllegalArgumentException("无效分类");
        String path = profile.route(pageNumber == 1 ? "category" : "categoryPage", "category", id, "page", String.valueOf(pageNumber));
        JSONObject result = listing(page(path), pageNumber, id);
        if (result.getJSONArray("items").length() == 0) throw new IllegalStateException("分类内容暂时不可用");
        return result;
    }

    JSONObject tags() throws Exception {
        JSONArray bundled;
        try (InputStream input = appContext.getAssets().open("tags.json"); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            bundled = new JSONArray(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        }
        JSONArray items = new JSONArray();
        try {
            String xml = new String(fetch(profile.origin + profile.route("tagSitemap")), StandardCharsets.UTF_8);
            Document sitemap = Jsoup.parse(xml, "", Parser.xmlParser());
            Set<String> current = new LinkedHashSet<>();
            for (Element loc : sitemap.select("loc")) {
                java.util.regex.Matcher match = profile.pattern("tag", null).matcher(profile.relative(text(loc)));
                if (match.matches()) current.add(match.group(1));
            }
            if (!current.isEmpty()) {
                for (int i = 0; i < bundled.length(); i++) {
                    JSONObject tag = bundled.optJSONObject(i);
                    if (tag != null && current.remove(tag.optString("slug"))) items.put(tag);
                }
                for (String slug : current) {
                    String name = slug;
                    try { name = text(page(profile.route("tag", "slug", slug)).selectFirst(profile.selector("tagTitle"))).replaceFirst("短剧在线观看$", "").trim(); }
                    catch (Exception ignored) { /* 新标签暂用链接名称，仍可进入列表 */ }
                    items.put(new JSONObject().put("slug", slug).put("name", name).put("group", "other"));
                }
            }
        } catch (Exception ignored) { /* 离线时沿用内置标签 */ }
        if (items.length() == 0) items = bundled;
        return new JSONObject().put("items", items);
    }

    JSONObject tagListing(String slug, int pageNumber) throws Exception {
        if (slug == null || !slug.matches("[a-z0-9-]{1,80}") || pageNumber < 1 || pageNumber > 500) {
            throw new IllegalArgumentException("无效标签或页码");
        }
        String path = profile.route(pageNumber == 1 ? "tag" : "tagPage", "slug", slug, "page", String.valueOf(pageNumber));
        return listing(page(path), pageNumber, "");
    }

    JSONObject recommend(int pageNumber, boolean newest) throws Exception {
        if (pageNumber < 1) throw new IllegalArgumentException("无效页码");
        String name = newest ? "newest" : "recommend";
        return listing(page(profile.route(pageNumber == 1 ? name : name + "Page", "page", String.valueOf(pageNumber))), pageNumber, "");
    }

    JSONObject search(String term) throws Exception {
        String query = term.trim();
        if (query.length() > 80) query = query.substring(0, 80);
        if (query.isEmpty()) return new JSONObject().put("items", new JSONArray()).put("nextPage", JSONObject.NULL);
        return listing(page(profile.route("search", "query", java.net.URLEncoder.encode(query, StandardCharsets.UTF_8))), 1, "");
    }

    JSONObject detail(String id) throws Exception {
        if (!id.matches("\\d+")) throw new IllegalArgumentException("无效剧集");
        Document document = page(profile.route("detail", "id", id));
        Element node = document.selectFirst(profile.selector("content"));
        if (node == null) throw new IllegalStateException("未找到剧集详情");
        JSONObject data = profile.mapJson(new JSONObject(node.data().isEmpty() ? node.text() : node.data()), "content");
        if (!id.equals(String.valueOf(data.opt("id")))) throw new IllegalStateException("剧集详情不匹配");
        JSONObject result = new JSONObject();
        result.put("id", id);
        result.put("title", data.optString("title"));
        result.put("cover", data.optString("coverSrc", data.optString("posterSrc")));
        result.put("description", data.optString("description"));
        result.put("score", text(document.selectFirst(profile.selector("detailScore"))));
        JSONArray tags = new JSONArray();
        JSONArray siteTags = data.optJSONArray("tags");
        for (int i = 0; siteTags != null && i < siteTags.length(); i++) {
            if (tags.length() >= 5) break;
            String value = siteTags.optString(i).trim();
            if (!value.isEmpty()) tags.put(value);
        }
        result.put("tags", tags);
        JSONArray episodes = new JSONArray();
        Set<Integer> seenEpisodes = new HashSet<>();
        for (Element link : document.select(profile.selector("episodeLinks"))) {
            String href = link.attr("href");
            java.util.regex.Matcher match = profile.pattern("episode", id).matcher(profile.relative(href));
            if (!match.matches()) continue;
            int number = match.group(1) == null ? 1 : Integer.parseInt(match.group(1));
            if (!seenEpisodes.add(number)) continue;
            episodes.put(new JSONObject().put("number", number).put("url", profile.origin + href));
        }
        if (episodes.length() == 0) throw new IllegalStateException("未找到选集列表");
        result.put("episodeLabel", "共 " + episodes.length() + " 集");
        result.put("episodes", episodes);
        return result;
    }

    JSONObject episode(String id, int number) throws Exception {
        return episode(id, number, "");
    }

    /**
     * @param pageUrl real episode page URL taken from the detail payload's episode
     *                list. Preferred over the route template: when the site changes
     *                its URL scheme, the template silently points at a 404 while the
     *                links on the detail page are still correct.
     */
    JSONObject episode(String id, int number, String pageUrl) throws Exception {
        if (!id.matches("\\d+") || number < 1) throw new IllegalArgumentException("无效选集");
        String path = profile.route(number == 1 ? "episodeFirst" : "episode", "id", id, "episode", String.valueOf(number));
        java.util.List<String> candidates = new java.util.ArrayList<>();
        if (pageUrl != null && pageUrl.startsWith("https://")) candidates.add(pageUrl);
        candidates.add(profile.origin + path);

        Exception failure = null;
        for (String url : candidates) {
            try {
                Document document = pageAbsolute(url);
                JSONObject result = readEpisode(document, id, number);
                if (result != null) return result;
                failure = new IllegalStateException("未取得有效播放地址");
            } catch (Exception error) {
                failure = error;
            }
        }
        // Final attempt: the CDN sometimes answers the native stack with a
        // challenge/empty shell while the browser channel gets the real document.
        try {
            byte[] body = WebViewFetcher.get(appContext).fetch(candidates.get(0));
            if (body.length >= 1000) {
                JSONObject result = readEpisode(
                        Jsoup.parse(new String(body, StandardCharsets.UTF_8), profile.origin), id, number);
                if (result != null) return result;
            }
        } catch (Exception ignored) { }
        throw failure != null ? failure : new IllegalStateException("未取得播放地址");
    }

    /** Parses one episode page; null when no playable URL could be recovered. */
    private JSONObject readEpisode(Document document, String id, int number) throws Exception {
        Element node = document.selectFirst(profile.selector("content"));
        // The inline JSON block is the primary source, but it is the most volatile
        // part of the page: a renamed key or a wrapped payload used to surface as
        // "加载失败" with no way to recover. Parse defensively and fall back to a
        // raw scan of the HTML before giving up.
        String raw = node == null ? "" : (node.data().isEmpty() ? node.text() : node.data());
        JSONObject data = null;
        String json = raw.trim();
        // Tolerate a JS wrapper (window.__DATA__ = {...};) by trimming to the
        // outermost object literal before parsing.
        if (!json.startsWith("{")) {
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) json = json.substring(start, end + 1);
        }
        if (!json.trim().isEmpty()) {
            try { data = profile.mapJson(new JSONObject(json), "content"); }
            catch (Exception ignored) { /* structure changed — fall back below */ }
        }
        String source = data == null ? "" : data.optString("videoSrc").trim();
        if (source.startsWith("//")) source = "https:" + source;
        if (!source.startsWith("https://")) source = extractVideoSource(document.html());

        // 2026-11 redesign: the page ships videoSrc="" and the signed manifest
        // lives behind POST /api/videos/{id}/episodes/{n}/playback. Without this
        // call every episode fails with "未取得有效播放地址" even though the page
        // loaded fine.
        String cdnKey = data == null ? "" : data.optString("cdnAuthKey");
        String cdnValue = data == null ? "" : data.optString("cdnAuthValue");
        String cdnApp = data == null ? "" : data.optString("cdnApp");
        if (!source.startsWith("https://")) {
            JSONObject payload = playbackPayload(id, number);
            JSONObject streams = payload.optJSONObject("streams");
            source = streams == null ? "" : streams.optString("main", "").trim();
            if (source.isEmpty() && streams != null) source = streams.optString("preview", "").trim();
            if (source.startsWith("//")) source = "https:" + source;
            JSONObject cdn = payload.optJSONObject("cdn");
            if (cdn != null) {
                cdnKey = cdn.optString("authKey", cdnKey);
                cdnValue = cdn.optString("authValue", cdnValue);
                cdnApp = cdn.optString("app", cdnApp);
            }
        }
        if (!source.startsWith("https://")) return null;
        // The id check is only a sanity guard; some redesigns change how the id is
        // rendered (string vs number, or a different field). A valid playable URL
        // wins over a strict mismatch, otherwise playback dies on every rotation.
        JSONObject result = new JSONObject();
        result.put("id", id);
        result.put("episode", number);
        result.put("title", data == null ? "剧集 " + id : data.optString("title", "剧集 " + id));
        result.put("source", source);
        if (!cdnKey.isEmpty() && !cdnValue.isEmpty()) {
            result.put("cdnAuthKey", cdnKey);
            result.put("cdnAuthValue", cdnValue);
            result.put("cdnApp", cdnApp);
        }
        result.put("cover", data == null ? "" : data.optString("coverSrc", data.optString("posterSrc")));
        result.put("nextUrl", attr(document.selectFirst(profile.selector("next")), "href"));
        return result;
    }

    /** Last-resort search for a playable URL when the structured JSON block moves.
     *  Covers renamed keys, JSON-escaped slashes and bare m3u8 links in scripts. */
    private static String extractVideoSource(String html) {
        if (html == null || html.isEmpty()) return "";
        String[] patterns = {
                "\"videoSrc\"\\s*:\\s*\"([^\"]+)\"",
                "\"video_src\"\\s*:\\s*\"([^\"]+)\"",
                "\"playUrl\"\\s*:\\s*\"([^\"]+)\"",
                "\"play_url\"\\s*:\\s*\"([^\"]+)\"",
                "\"url\"\\s*:\\s*\"([^\"]+\\.(?:m3u8|mp4)[^\"]*)\"",
                "(https?://[^\"'\\s\\\\]+\\.(?:m3u8|mp4)[^\"'\\s\\\\]*)"
        };
        for (String pattern : patterns) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(pattern).matcher(html);
            if (!matcher.find()) continue;
            String value = matcher.group(1).replace("\\/", "/").replace("\\u0026", "&").trim();
            if (value.startsWith("//")) value = "https:" + value;
            if (value.startsWith("http://")) value = "https://" + value.substring(7);
            if (value.startsWith("https://")) return value;
        }
        return "";
    }

    /** 列表卡用的封面宽度：3x 屏上卡片约 342px，360 足够。 */
    private static final int COVER_CARD_WIDTH = 360;
    /** 详情/轮播大图宽度。 */
    private static final int COVER_HERO_WIDTH = 720;

    /**
     * 封面入口。
     * @param inline true 返回 base64 data URI（首屏轮播要读像素做配色，canvas 需要同源）；
     *               false 返回本地 https URL，由 WebViewAssetLoader 流式读取，省掉 base64 的 33% 膨胀。
     */
    String coverUrl(String value, boolean inline) throws Exception {
        File file = ensureCover(value, inline ? COVER_HERO_WIDTH : COVER_CARD_WIDTH);
        if (file == null) return "";
        if (inline) {
            try {
                byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
                return "data:" + mimeOf(file.getName()) + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
            } catch (java.io.IOException missing) {
                return "";
            }
        }
        return "https://appassets.androidplatform.net/cover/" + file.getName();
    }

    /** 供 WebViewAssetLoader 读取的封面文件；名字做白名单校验，避免路径穿越。 */
    File coverFile(String name) {
        if (name == null || !name.matches("[0-9a-f]{32}\\.(webp|gif|png|jpg)")) return null;
        File file = new File(coverDir, name);
        return file.isFile() ? file : null;
    }

    /** 缓存分区吃紧时系统会整个删掉 cache 子目录；每次读写前都要能自愈。 */
    private void ensureCoverDir() {
        if (!coverDir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            coverDir.mkdirs();
        }
    }

    private long freeCacheBytes() {
        try {
            return coverDir.getUsableSpace();
        } catch (Exception error) {
            return Long.MAX_VALUE;
        }
    }

    private static String mimeOf(String name) {
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".png")) return "image/png";
        return "image/jpeg";
    }

    private File ensureCover(String value, int width) throws Exception {
        ensureCoverDir();
        value = profile.coverUrl(value);
        if (value.isEmpty()) {
            // blob:/data:/不在白名单 等无法取数或已被放行为空的情况：
            // 上层 UI 会回退到文字占位符，这里不再抛错，避免一张坏图卡死整屏。
            return null;
        }
        URL url = new URL(value);
        String path = url.getPath();
        String extension = path.substring(path.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
        if (profile.object("cover").optBoolean("checkExtension", true)
                && !Arrays.asList("jpg", "jpeg", "png", "webp", "gif").contains(extension)) {
            throw new IllegalArgumentException("不支持的封面格式");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        // 缓存键带目标宽度：改了规格必然换文件，不会读到旧的大图。
        byte[] hash = digest.digest((profile.fingerprint + ":" + url.getHost() + path + ":w" + width)
                .getBytes(StandardCharsets.UTF_8));
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < 16; i++) key.append(String.format("%02x", hash[i] & 0xff));
        String base = key.toString();

        // 命中：同名不同扩展都算命中（转码后是 .webp，动图保留原扩展）。
        for (String suffix : new String[]{".webp", ".gif", ".png", ".jpg"}) {
            File hit = new File(coverDir, base + suffix);
            if (!hit.isFile()) continue;
            byte[] bytes;
            try { bytes = java.nio.file.Files.readAllBytes(hit.toPath()); }
            catch (java.io.IOException missing) { continue; }
            if (!validImage(bytes)) { hit.delete(); continue; }
            hit.setLastModified(System.currentTimeMillis());
            return hit;
        }

        byte[] decoded = fetch(value);
        String mode = profile.object("cover").optString("mode");
        if ("aes-cbc".equals(mode) || "auto".equals(mode) && !validImage(decoded)) {
            Cipher cipher = Cipher.getInstance(profile.object("cover").getString("transformation"));
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(profile.keyBytes("key"), "AES"),
                    new IvParameterSpec(profile.keyBytes("iv")));
            decoded = cipher.doFinal(decoded);
        }
        repairConfiguredHeader(decoded);
        if (!validImage(decoded)) throw new IllegalStateException("封面解密结果无效");
        return writeCover(base, decoded, width);
    }

    /** 下采样到目标宽度并转 WebP；GIF 与转码后反而更大的图保留原字节。 */
    private File writeCover(String base, byte[] raw, int width) throws Exception {
        boolean gif = raw.length > 3 && raw[0] == 'G' && raw[1] == 'I' && raw[2] == 'F';
        if (!gif) {
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
            if (bounds.outWidth > 0) {
                int sample = 1;
                while (bounds.outWidth / (sample * 2) > width) sample *= 2;
                android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
                options.inSampleSize = sample;
                android.graphics.Bitmap bitmap = android.graphics.BitmapFactory
                        .decodeByteArray(raw, 0, raw.length, options);
                if (bitmap != null) {
                    if (bitmap.getWidth() > width) {
                        int height = Math.max(1, Math.round(bitmap.getHeight() * width / (float) bitmap.getWidth()));
                        android.graphics.Bitmap scaled = android.graphics.Bitmap
                                .createScaledBitmap(bitmap, width, height, true);
                        if (scaled != bitmap) bitmap.recycle();
                        bitmap = scaled;
                    }
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP, 82, out);
                    bitmap.recycle();
                    byte[] encoded = out.toByteArray();
                    if (encoded.length > 0 && encoded.length < raw.length) {
                        File file = new File(coverDir, base + ".webp");
                        if (writeCoverBytes(file, encoded)) return file;
                        return null;  // 空间不足：安静降级为文字占位，不刷错误日志
                    }
                }
            }
        }
        String suffix = gif ? ".gif"
                : raw[0] == (byte) 0x89 ? ".png"
                : raw[0] == (byte) 0xff ? ".jpg" : ".webp";
        File file = new File(coverDir, base + suffix);
        return writeCoverBytes(file, raw) ? file : null;
    }

    /**
     * 写封面。缓存分区被写满时系统会清空 cache 目录，这里重建目录并释放自己占的视频缓存后重试一次；
     * 仍然失败就返回 false，让 UI 走文字占位，不再反复抛 ENOENT。
     */
    private boolean writeCoverBytes(File file, byte[] bytes) {
        for (int attempt = 0; attempt < 2; attempt++) {
            ensureCoverDir();
            try (FileOutputStream stream = new FileOutputStream(file)) {
                stream.write(bytes);
                return true;
            } catch (java.io.IOException error) {
                if (attempt == 1) return false;
                if (freeCacheBytes() < 128L * 1024 * 1024) {
                    try { VideoPlaybackCache.clear(appContext); }
                    catch (Exception ignored) { }
                }
            }
        }
        return false;
    }

    private static void deleteTree(File target) {
        if (target == null || !target.exists()) return;
        File[] children = target.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        //noinspection ResultOfMethodCallIgnored
        target.delete();
    }

    private boolean repairConfiguredHeader(byte[] bytes) throws Exception {
        boolean repaired = "legacy-xor".equals(profile.object("cover").optString("headerRepair")) && repairCoverHeader(bytes);
        boolean patched = profile.patchHeader(bytes);
        return repaired || patched;
    }

    private static boolean repairCoverHeader(byte[] bytes) {
        // Current CDN payloads differ from the valid Windows cache at byte offset 6 by XOR 0x0E.
        // Check the surrounding format signature so an already-normalized cache is not changed twice.
        if (bytes.length < 12) return false;
        boolean png = bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G' &&
                bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x14 && bytes[7] == 0x0a;
        boolean jpeg = bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff;
        boolean jfif = jpeg && bytes[3] == (byte) 0xe0 && bytes[6] == 'D' &&
                bytes[7] == 'F' && bytes[8] == 'I' && bytes[9] == 'F' && bytes[10] == 0;
        boolean dqt = jpeg && bytes[3] == (byte) 0xdb && (bytes[6] & 0xfc) == 0x0c;
        boolean exif = jpeg && bytes[3] == (byte) 0xe1 && bytes[6] == 'K' &&
                bytes[7] == 'x' && bytes[8] == 'i' && bytes[9] == 'f';
        boolean icc = jpeg && bytes[3] == (byte) 0xe2 && bytes[6] == 'G' &&
                bytes[7] == 'C' && bytes[8] == 'C' && bytes[9] == '_';
        if (!(png || jfif || dqt || exif || icc)) return false;
        bytes[6] ^= 0x0e;
        return true;
    }

    private static boolean validImage(byte[] bytes) {
        if (bytes.length < 16) return false;
        return (bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8) ||
                (bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G' &&
                        bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a) ||
                (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F' &&
                        bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') ||
                (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F');
    }

    long coversSize() {
        File[] files = coverDir.listFiles();
        if (files == null) return 0;
        long bytes = 0;
        for (File file : files) if (file.isFile()) bytes += file.length();
        return bytes;
    }

    void clearCovers() {
        File[] files = coverDir.listFiles();
        if (files != null) for (File file : files) file.delete();
    }
}
