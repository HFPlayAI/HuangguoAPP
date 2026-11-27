package com.huangguo.mobile;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.regex.Pattern;

/** Immutable rules for one screen/playback session; callers keep the same request interface. */
final class SiteProfile {
    private final JSONObject rules;
    final String origin;
    final String fingerprint;

    SiteProfile(Context context, JSONObject overrides) throws Exception {
        JSONObject defaults = new JSONObject(new String(HotUpdateManager.read(
                context.getAssets().open("site-default.json"), 128 * 1024), StandardCharsets.UTF_8));
        rules = merge(defaults, overrides == null ? new JSONObject() : overrides);
        origin = rules.getString("origin").replaceAll("/+$", "");
        URI uri = new URI(origin);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || !uri.getPath().isEmpty())
            throw new IllegalArgumentException("站点必须是 HTTPS 域名");
        for (Iterator<String> keys = object("routes").keys(); keys.hasNext();) {
            String path = object("routes").getString(keys.next());
            if (!path.startsWith("/") || path.startsWith("//") || path.contains(".."))
                throw new IllegalArgumentException("无效站点路径");
        }
        for (Iterator<String> keys = object("selectors").keys(); keys.hasNext();)
            Jsoup.parse("").select(object("selectors").getString(keys.next()));
        for (Iterator<String> keys = object("patterns").keys(); keys.hasNext();)
            Pattern.compile(object("patterns").getString(keys.next()).replace("{id}", "1"));
        JSONArray hosts = object("cover").optJSONArray("allowedHosts");
        if (hosts == null) hosts = new JSONArray();
        // B 方案：白名单可为空。非空则按原逻辑做精确匹配；为空则放行任意 https 主机。
        // 逐项仍校验字符合法性（不允许 blob: 等非 http(s) 协议串混入白名单）。
        for (int i = 0; i < hosts.length(); i++) {
            if (!hosts.getString(i).matches("[a-zA-Z0-9.-]+")) throw new IllegalArgumentException("无效封面域名");
        }
        String mode = object("cover").getString("mode");
        if (!java.util.Arrays.asList("plain", "aes-cbc", "auto").contains(mode))
            throw new IllegalArgumentException("不支持的封面模式");
        String transformation = object("cover").getString("transformation");
        if (!java.util.Arrays.asList("AES/CBC/NoPadding", "AES/CBC/PKCS5Padding").contains(transformation))
            throw new IllegalArgumentException("不支持的解密方式");
        if (!"plain".equals(mode) && (keyBytes("key").length != 16 && keyBytes("key").length != 24
                && keyBytes("key").length != 32 || keyBytes("iv").length != 16))
            throw new IllegalArgumentException("无效封面密钥或 IV");
        fingerprint = hex(MessageDigest.getInstance("SHA-256").digest(rules.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static JSONObject merge(JSONObject base, JSONObject patch) throws Exception {
        JSONObject out = new JSONObject(base.toString());
        for (Iterator<String> keys = patch.keys(); keys.hasNext();) {
            String key = keys.next();
            Object value = patch.get(key);
            if (value instanceof JSONObject && out.opt(key) instanceof JSONObject)
                out.put(key, merge(out.getJSONObject(key), (JSONObject) value));
            else out.put(key, value);
        }
        return out;
    }

    JSONObject object(String key) { return rules.optJSONObject(key); }
    String selector(String key) { return object("selectors").optString(key); }
    String attribute(String key) { return object("attributes").optString(key); }
    String route(String name, String... pairs) {
        String result = object("routes").optString(name);
        for (int i = 0; i < pairs.length; i += 2) result = result.replace("{" + pairs[i] + "}", pairs[i + 1]);
        return result;
    }
    Pattern pattern(String name, String id) {
        return Pattern.compile(object("patterns").optString(name).replace("{id}", id == null ? "" : id));
    }
    String relative(String link) {
        try {
            URI resolved = new URI(origin + "/").resolve(link);
            if (!new URI(origin).getHost().equalsIgnoreCase(resolved.getHost())) return "";
            return resolved.getRawPath();
        } catch (Exception error) { return ""; }
    }
    boolean categoryAllowed(String name) {
        JSONArray names = rules.optJSONArray("categories");
        for (int i = 0; names != null && i < names.length(); i++) if (name.equals(names.optString(i))) return true;
        return false;
    }
    JSONObject mapJson(JSONObject input, String kind) throws Exception {
        JSONObject map = object("fields").getJSONObject(kind), result = new JSONObject(input.toString());
        for (Iterator<String> keys = map.keys(); keys.hasNext();) {
            String key = keys.next();
            Object paths = map.get(key);
            JSONArray aliases = paths instanceof JSONArray ? (JSONArray) paths : new JSONArray().put(paths);
            result.remove(key);
            for (int i = 0; i < aliases.length(); i++) {
                Object value = input;
                for (String part : aliases.getString(i).split("\\."))
                    value = value instanceof JSONObject ? ((JSONObject) value).opt(part) : null;
                if (value != null && value != JSONObject.NULL) { result.put(key, value); break; }
            }
        }
        return result;
    }
    byte[] keyBytes(String name) {
        String value = object("cover").optString(name);
        String encoding = object("cover").optString("keyEncoding", "text");
        if ("base64".equals(encoding)) return android.util.Base64.decode(value, android.util.Base64.DEFAULT);
        if ("hex".equals(encoding)) {
            if (!value.matches("(?:[0-9a-fA-F]{2})+")) throw new IllegalArgumentException("无效十六进制密钥");
            byte[] bytes = new byte[value.length() / 2];
            for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
            return bytes;
        }
        if (!"text".equals(encoding)) throw new IllegalArgumentException("不支持的密钥编码");
        return value.getBytes(StandardCharsets.UTF_8);
    }
    String coverUrl(String value) throws Exception {
        // blob:/data: 等运行时资源无法脱离页面执行，Jsoup 无头解析拿不到真实字节。
        // 直接返回空串，交由上层跳过该卡图（不报错、不卡整屏）。详见 SiteRepository.coverData。
        if (value == null || value.isEmpty()
                || value.startsWith("blob:") || value.startsWith("data:")
                || value.startsWith("http:")) {
            return "";
        }
        URI uri = new URI(value);
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null || uri.getUserInfo() != null) {
            // 非 https/不可解析的地址同样跳过，而非报错中断整屏
            return "";
        }
        JSONObject rewrites = object("cover").optJSONObject("hostRewrites");
        if (rewrites != null && rewrites.has(host)) {
            host = rewrites.getString(host);
            uri = new URI("https", null, host, uri.getPort(), uri.getPath(), uri.getQuery(), null);
        }
        JSONArray hosts = object("cover").optJSONArray("allowedHosts");
        if (hosts == null) hosts = new JSONArray();
        // B 方案：白名单非空则精确匹配，命中即放行；为空则放行任意 https 主机（一劳永逸）。
        if (hosts.length() == 0) return uri.toString();
        for (int i = 0; i < hosts.length(); i++) if (host.equalsIgnoreCase(hosts.getString(i))) return uri.toString();
        // 白名单非空但本主机不在其中：不再硬抛异常中断整屏，返回空串让上层跳过该图。
        return "";
    }
    boolean patchHeader(byte[] data) throws Exception {
        boolean changed = false;
        JSONArray patches = object("cover").optJSONArray("bytePatches");
        for (int i = 0; patches != null && i < patches.length(); i++) {
            JSONObject patch = patches.getJSONObject(i);
            int offset = patch.getInt("offset");
            if (offset < 0 || offset >= data.length) throw new IllegalArgumentException("封面补丁越界");
            if ((data[offset] & 255) == patch.getInt("from")) {
                changed |= (data[offset] & 255) != patch.getInt("to");
                data[offset] = (byte) patch.getInt("to");
            }
        }
        return changed;
    }
    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }
}
