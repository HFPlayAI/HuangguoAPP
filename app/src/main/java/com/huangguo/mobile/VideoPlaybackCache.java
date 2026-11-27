package com.huangguo.mobile;

import android.content.Context;
import androidx.media3.common.C;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import java.io.File;

/** Shared by playback, the next episode and seek previews; never caches mutable playlists. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class VideoPlaybackCache {
    /** 目标 2GB：单集 5 分钟约 40~80MB，512MB 也只够七八集，看几部就得回源。
     *  实际生效值取「2GB」与「可用空间的三分之一」的较小者，避免把手机塞满。 */
    static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;

    private static long effectiveCap(Context app) {
        try {
            android.os.StatFs stat = new android.os.StatFs(app.getCacheDir().getAbsolutePath());
            long free = stat.getAvailableBytes();
            long safe = Math.max(512L * 1024 * 1024, free / 3);
            return Math.min(MAX_BYTES, safe);
        } catch (Exception error) {
            return MAX_BYTES;
        }
    }
    private static SimpleCache cache;

    private static synchronized SimpleCache get(Context context) {
        if (cache == null) {
            Context app = context.getApplicationContext();
            cache = new SimpleCache(new File(app.getCacheDir(), "video-segments"),
                    new LeastRecentlyUsedCacheEvictor(effectiveCap(app)), new StandaloneDatabaseProvider(app));
        }
        return cache;
    }

    /** 当前视频分段缓存占用（字节）。 */
    static long size(Context context) {
        try {
            SimpleCache instance = get(context);
            return instance.getCacheSpace();
        } catch (Exception error) {
            return 0;
        }
    }

    /** 释放并删除整个分段缓存目录；下次播放会重新建。 */
    static synchronized void clear(Context context) throws Exception {
        Context app = context.getApplicationContext();
        File dir = new File(app.getCacheDir(), "video-segments");
        if (cache != null) {
            try { cache.release(); } catch (Exception ignored) { }
            cache = null;
        }
        deleteTree(dir);
    }

    private static void deleteTree(File target) {
        if (target == null || !target.exists()) return;
        File[] children = target.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        //noinspection ResultOfMethodCallIgnored
        target.delete();
    }

    static HlsMediaSource.Factory sources(Context context) {
        return sources(context, "", null);
    }

    static HlsMediaSource.Factory sources(Context context, String origin) {
        return sources(context, origin, null);
    }

    /**
     * @param origin       active site origin, sent as Referer
     * @param extraHeaders per-episode CDN auth (x-cdn-auth / x-cdn-app). The
     *                     playback API hands these out with the signed manifest;
     *                     the web player attaches them to every .m3u8 request.
     */
    static HlsMediaSource.Factory sources(Context context, String origin,
                                          java.util.Map<String, String> extraHeaders) {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(20_000)
                .setReadTimeoutMs(25_000)
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36");
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        if (origin != null && !origin.isEmpty()) {
            headers.put("Referer", origin.endsWith("/") ? origin : origin + "/");
        }
        headers.put("Accept", "*/*");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        if (extraHeaders != null) headers.putAll(extraHeaders);
        http.setDefaultRequestProperties(headers);
        DataSource.Factory upstream = new DefaultDataSource.Factory(context, http);
        CacheDataSource.Factory cached = new CacheDataSource.Factory().setCache(get(context))
                .setUpstreamDataSourceFactory(upstream).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
        // Media segments rotate across CDN nodes and some answer 403. The player's
        // own retry loop only re-hits the SAME dead URL with growing backoff, which
        // wasted ~17s per attempt. One retry is enough: a failure then surfaces
        // immediately and PlayerActivity re-fetches a fresh signed manifest, which
        // usually points at a healthy node.
        return new HlsMediaSource.Factory(type -> type == C.DATA_TYPE_MANIFEST
                ? upstream.createDataSource() : cached.createDataSource())
                .setLoadErrorHandlingPolicy(
                        new androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(1));
    }
}
