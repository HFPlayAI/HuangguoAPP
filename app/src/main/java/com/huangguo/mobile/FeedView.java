package com.huangguo.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 推荐流（抖音式内嵌 tab，不是二级页面）。
 *
 * 由 MainActivity 贴在 WebView 之上、底部导航栏之上，所以 dock 始终可见可点，
 * 也没有"返回"按钮 —— 切 tab 就等于退出推荐。
 *
 * 交互：
 *  - 上滑 → 下一部剧第 1 集；下滑 → 上一部剧第 1 集（手动滑动永远换剧）。
 *  - 一集播完自动连播同剧下一集，最后一集播完进入下一部。
 *
 * 流畅度：两个 standby 播放器，同时预热"同剧下一集"和"下一部第 1 集"。
 * 命中即接管已缓冲的引擎，切换不重新起播。
 *
 * 推荐记忆：持久化"推荐池签名 + 当前下标 + 已看过剧集"。下次进入若池子没变，
 * 从上次位置继续；换了池子则从第一条没看过的开始，避免每次重刷同样十条。
 */
final class FeedView extends FrameLayout {
    /** 收藏能力由宿主提供：未登录写本地，登录后顺便推云端。 */
    interface Host {
        boolean isBookmarked(String id);
        boolean toggleBookmark(JSONObject item);
        void openDetail(String id);
        void openFullscreen(JSONObject payload);
    }

    private static final String PREFS = "feed_state";
    private static final String KEY_SIG = "poolSig";
    private static final String KEY_INDEX = "index";
    private static final String KEY_SEEN = "seen";
    private static final int SEEN_LIMIT = 300;

    private static final int FLING_VELOCITY = 900;
    private static final float DRAG_RATIO = 0.22f;
    private static final int PREFETCH_AHEAD = 3;
    private static final long TICK_MS = 250;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(3);
    private final Map<String, JSONObject> detailCache = new HashMap<>();
    private final Map<String, JSONObject> readyCache = new HashMap<>();

    private ExoPlayer player;
    private ExoPlayer standbyNext;
    private String standbyNextKey;
    private EpisodeLoadControl standbyNextControl;
    private ExoPlayer standbySwipe;
    private String standbySwipeKey;
    private EpisodeLoadControl standbySwipeControl;
    private PlayerView playerView;

    private JSONArray items = new JSONArray();
    private int index = 0;
    private String poolSig = "";

    private JSONArray episodes = new JSONArray();
    private int episode = 1;
    private int totalEpisodes = 0;

    private TextView titleView;
    private TextView counterView;
    private TextView messageView;
    private SeekBar seekBar;
    private ProgressBar spinner;

    private final Host host;
    private TextView heartView;
    private TextView speedButton;
    private TextView fullscreenButton;
    private View pauseBadge;
    private float speed = 1f;
    private boolean endedMarked = false;
    private boolean play3sMarked = false;

    private SiteRepository repository;
    private int loadToken = 0;
    private boolean destroyed = false;
    private boolean paused = false;
    private boolean dragging = false;
    private float downX, downY;
    private long downAt;

    FeedView(Context context, Host host) {
        super(context);
        this.host = host;
        repository = new SiteRepository(context);
        setBackgroundColor(Color.BLACK);
        buildUi();
        player = createPlayer(new EpisodeLoadControl(false));
        playerView.setPlayer(player);
    }

    /** standby 必须用小缓冲（5s / 4MB）：三个播放器都用默认策略时，192MB 堆直接 OOM。 */
    private ExoPlayer createPlayer(EpisodeLoadControl control) {
        ExoPlayer engine = new ExoPlayer.Builder(getContext())
                .setLoadControl(control)
                .setMediaSourceFactory(VideoPlaybackCache.sources(getContext(), referer(), new HashMap<>()))
                .build();
        engine.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (destroyed || engine != player) return;
                if (state == Player.STATE_READY) {
                    spinner.setVisibility(View.GONE);
                    messageView.setVisibility(View.GONE);
                    prepareStandby();
                } else if (state == Player.STATE_BUFFERING) {
                    spinner.setVisibility(View.VISIBLE);
                } else if (state == Player.STATE_ENDED) {
                    long position = engine.getCurrentPosition();
                    long duration = engine.getDuration();
                    if (duration < 6_000 && position < 1_500) {
                        messageView.setText("播放异常，点击重试");
                        messageView.setVisibility(View.VISIBLE);
                        note("ended-empty");
                        return;
                    }
                    advanceAfterEpisode();
                }
            }

            @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize size) {
                if (destroyed || engine != player) return;
                // 与 PlayerActivity 完全一致：只有"明显竖屏"才裁切填满，其余按比例完整显示。
                boolean tall = size.width > 0 && size.height > 0
                        && (double) size.width * size.pixelWidthHeightRatio / size.height < 0.9;
                playerView.setResizeMode(tall
                        ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        : AspectRatioFrameLayout.RESIZE_MODE_FIT);
            }

            @Override public void onIsPlayingChanged(boolean playing) {
                if (destroyed || engine != player) return;
                updatePauseBadge();
            }

            @Override public void onPlayerError(PlaybackException error) {
                if (destroyed || engine != player) return;
                spinner.setVisibility(View.GONE);
                messageView.setText("加载失败，点击重试");
                messageView.setVisibility(View.VISIBLE);
            }
        });
        return engine;
    }

    private void buildUi() {
        playerView = new PlayerView(getContext());
        playerView.setUseController(false);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
        addView(playerView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        View scrim = new View(getContext());
        scrim.setOnTouchListener(this::onTouch);
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        spinner = new ProgressBar(getContext());
        LayoutParams spinnerParams = new LayoutParams(96, 96);
        spinnerParams.gravity = Gravity.CENTER;
        addView(spinner, spinnerParams);

        messageView = new TextView(getContext());
        messageView.setTextColor(Color.WHITE);
        messageView.setTextSize(15);
        messageView.setGravity(Gravity.CENTER);
        messageView.setPadding(48, 24, 48, 24);
        messageView.setVisibility(View.GONE);
        messageView.setOnClickListener(view -> loadEpisode(episode));
        LayoutParams messageParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        messageParams.gravity = Gravity.CENTER;
        addView(messageView, messageParams);

        // 底部信息区用纵向 LinearLayout 堆叠：红心 / 剧名 / 集数 / 进度条。
        // 早先用绝对像素定位，手机上系统字体一放大，剧名就长到红心身上去了。
        android.widget.LinearLayout stack = new android.widget.LinearLayout(getContext());
        stack.setOrientation(android.widget.LinearLayout.VERTICAL);
        stack.setGravity(Gravity.START);
        stack.setPadding(dp(18), 0, dp(18), dp(10));

        // 红心在左，全屏/倍速在右，同一行（行高 48dp），字体放大也不会错位。
        android.widget.LinearLayout actionRow = new android.widget.LinearLayout(getContext());
        actionRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.BOTTOM);

        heartView = new TextView(getContext());
        heartView.setText("♥");
        heartView.setTextSize(30);
        heartView.setGravity(Gravity.CENTER);
        heartView.setShadowLayer(8f, 0f, 2f, Color.BLACK);
        heartView.setOnClickListener(view -> toggleHeart());
        android.widget.LinearLayout.LayoutParams heartParams =
                new android.widget.LinearLayout.LayoutParams(dp(48), dp(48));
        actionRow.addView(heartView, heartParams);

        View spacer = new View(getContext());
        actionRow.addView(spacer, new android.widget.LinearLayout.LayoutParams(0, dp(1), 1f));

        fullscreenButton = pill("全屏", 12);
        fullscreenButton.setOnClickListener(view -> openFullscreen());
        android.widget.LinearLayout.LayoutParams fsParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, dp(36));
        fsParams.leftMargin = dp(6);
        actionRow.addView(fullscreenButton, fsParams);

        speedButton = pill("1.0×", 13);
        speedButton.setOnClickListener(view -> showSpeedMenu());
        android.widget.LinearLayout.LayoutParams speedParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, dp(36));
        speedParams.leftMargin = dp(6);
        actionRow.addView(speedButton, speedParams);

        android.widget.LinearLayout.LayoutParams rowParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(8);
        stack.addView(actionRow, rowParams);

        titleView = new TextView(getContext());
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(17);
        titleView.setShadowLayer(6f, 0f, 2f, Color.BLACK);
        titleView.setPadding(0, dp(2), 0, dp(2));
        titleView.setOnClickListener(view -> {
            JSONObject item = currentItem();
            if (item != null && host != null) host.openDetail(item.optString("id"));
        });
        stack.addView(titleView, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        counterView = new TextView(getContext());
        counterView.setTextColor(0xFFDDDDDD);
        counterView.setTextSize(13);
        counterView.setShadowLayer(6f, 0f, 2f, Color.BLACK);
        android.widget.LinearLayout.LayoutParams counterParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        counterParams.topMargin = dp(2);
        stack.addView(counterView, counterParams);

        seekBar = new SeekBar(getContext());
        seekBar.setMax(1000);
        seekBar.setPadding(0, 0, 0, 0);
        styleSeekBar(seekBar);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) bar.setTag(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                Object tag = bar.getTag();
                if (tag instanceof Integer && player != null) {
                    long duration = player.getDuration();
                    if (duration > 0) player.seekTo(duration * ((Integer) tag) / 1000L);
                }
            }
        });
        android.widget.LinearLayout.LayoutParams seekParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(26));
        seekParams.topMargin = dp(10);
        stack.addView(seekBar, seekParams);

        LayoutParams stackParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        stackParams.gravity = Gravity.BOTTOM;
        addView(stack, stackParams);

        pauseBadge = new PauseBadge(getContext());
        pauseBadge.setVisibility(View.GONE);
        pauseBadge.setOnClickListener(view -> {
            if (player != null) player.play();
        });
        LayoutParams badgeParams = new LayoutParams(dp(84), dp(84));
        badgeParams.gravity = Gravity.CENTER;
        addView(pauseBadge, badgeParams);

        main.post(tick);
    }

    // ---------------------------------------------------------------- lifecycle

    /** MainActivity 加入视图后调用。startIndex < 0 表示"按上次进度续播"。 */
    void start(JSONArray pool, int startIndex) {
        items = pool == null ? new JSONArray() : pool;
        if (items.length() == 0) {
            messageView.setText("推荐池暂时不可用");
            messageView.setVisibility(View.VISIBLE);
            return;
        }
        poolSig = signature(items);
        index = startIndex >= 0 ? Math.min(startIndex, items.length() - 1) : resolveStartIndex(items);
        prefetchDetails();
        loadEpisode(1);
    }

    void pausePlayback() {
        paused = true;
        savePosition();
        if (player != null) player.setPlayWhenReady(false);
    }

    void resumePlayback() {
        paused = false;
        if (player != null && !destroyed) player.setPlayWhenReady(true);
    }

    void dispose() {
        closeSpeedMenu();
        savePosition();
        destroyed = true;
        loadToken++;
        main.removeCallbacks(tick);
        releaseStandby();
        if (player != null) { player.release(); player = null; }
        worker.shutdownNow();
    }

    // ------------------------------------------------------------- 推荐记忆

    private static String signature(JSONArray pool) {
        StringBuilder builder = new StringBuilder();
        int limit = Math.min(pool.length(), 10);
        for (int i = 0; i < limit; i++) {
            JSONObject item = pool.optJSONObject(i);
            builder.append(item == null ? "?" : item.optString("id")).append(',');
        }
        return builder.toString();
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private Set<String> seenIds() {
        Set<String> seen = new LinkedHashSet<>();
        String raw = prefs().getString(KEY_SEEN, "");
        if (raw == null || raw.isEmpty()) return seen;
        for (String part : raw.split(",")) if (!part.isEmpty()) seen.add(part);
        return seen;
    }

    /** 池子没变 → 从上次那条继续；池子变了 → 第一条没看过的。 */
    private int resolveStartIndex(JSONArray pool) {
        String savedSig = prefs().getString(KEY_SIG, "");
        int savedIndex = prefs().getInt(KEY_INDEX, -1);
        if (poolSig.equals(savedSig) && savedIndex >= 0 && savedIndex < pool.length()) {
            return savedIndex;
        }
        Set<String> seen = seenIds();
        for (int i = 0; i < pool.length(); i++) {
            JSONObject item = pool.optJSONObject(i);
            if (item != null && !seen.contains(item.optString("id"))) return i;
        }
        return 0;
    }

    private void persistPosition() {
        JSONObject item = currentItem();
        if (item == null) return;
        Set<String> seen = seenIds();
        seen.add(item.optString("id"));
        List<String> trimmed = new ArrayList<>(seen);
        if (trimmed.size() > SEEN_LIMIT) trimmed = trimmed.subList(trimmed.size() - SEEN_LIMIT, trimmed.size());
        prefs().edit()
                .putString(KEY_SIG, poolSig)
                .putInt(KEY_INDEX, index)
                .putString(KEY_SEEN, android.text.TextUtils.join(",", trimmed))
                .apply();
    }

    // ---------------------------------------------------------------- playback

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 原生 SeekBar 轨道太细，这里换成加粗圆角轨道 + 圆点，视觉更接近抖音。 */
    private void styleSeekBar(SeekBar bar) {
        android.graphics.drawable.GradientDrawable track = new android.graphics.drawable.GradientDrawable();
        track.setColor(0x59FFFFFF);
        track.setCornerRadius(7f);
        android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable();
        fill.setColor(0xFFFFFFFF);
        fill.setCornerRadius(7f);
        android.graphics.drawable.ClipDrawable clip = new android.graphics.drawable.ClipDrawable(
                fill, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{track, clip});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        layers.setLayerHeight(0, dp(4));
        layers.setLayerHeight(1, dp(4));
        layers.setLayerGravity(0, Gravity.CENTER_VERTICAL);
        layers.setLayerGravity(1, Gravity.CENTER_VERTICAL);
        bar.setProgressDrawable(layers);
        android.graphics.drawable.GradientDrawable thumb = new android.graphics.drawable.GradientDrawable();
        thumb.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        thumb.setColor(0xFFFFFFFF);
        thumb.setSize(dp(8), dp(8));
        bar.setThumb(thumb);
        bar.setThumbOffset(dp(4));
        bar.setSplitTrack(false);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (!dragging && player != null) {
                long duration = player.getDuration();
                long position = player.getCurrentPosition();
                if (duration > 0) {
                    seekBar.setProgress((int) Math.min(1000, position * 1000L / duration));
                    counterView.setText(counterText(position, duration));
                }
                if (!play3sMarked && position >= 3000) {
                    play3sMarked = true;
                    JSONObject watching = currentItem();
                    if (watching != null) FeedStats.play3s(getContext(), watching.optString("id"));
                }
            }
            main.postDelayed(this, TICK_MS);
        }
    };

    /** 剧名 + 评分（金色小字），评分来自推荐池的榜单数据。 */
    private void setTitle(String title, String score) {
        if (score == null || score.isEmpty()) {
            titleView.setText(title);
            return;
        }
        android.text.SpannableStringBuilder builder = new android.text.SpannableStringBuilder(title);
        builder.append("  ");
        int start = builder.length();
        builder.append(score);
        builder.setSpan(new android.text.style.ForegroundColorSpan(0xFFFFD54F), start, builder.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new android.text.style.RelativeSizeSpan(0.78f), start, builder.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        titleView.setText(builder);
    }

    private String counterText(long position, long duration) {
        String base = totalEpisodes > 0
                ? "第 " + episode + " 集 / 共 " + totalEpisodes + " 集"
                : "第 " + episode + " 集";
        if (duration <= 0) return base;
        return base + " · " + clock(position) + " / " + clock(duration);
    }

    private static String clock(long ms) {
        long total = Math.max(0, ms / 1000);
        return String.format(java.util.Locale.US, "%d:%02d", total / 60, total % 60);
    }

    private boolean onTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                downAt = SystemClock.elapsedRealtime();
                return true;
            case MotionEvent.ACTION_UP:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                long held = SystemClock.elapsedRealtime() - downAt;
                if (Math.abs(dy) > Math.abs(dx) * 1.4f) {
                    float height = view.getHeight() == 0 ? 1f : view.getHeight();
                    boolean fast = held > 0 && Math.abs(dy) / (held / 1000f) > FLING_VELOCITY;
                    if (Math.abs(dy) > height * DRAG_RATIO || fast) {
                        if (dy < 0) goNextDrama(); else goPreviousDrama();
                        return true;
                    }
                } else if (Math.abs(dx) < 24 && Math.abs(dy) < 24 && held < 400) {
                    if (player.isPlaying()) player.pause(); else player.play();
                }
                return true;
            default:
                return true;
        }
    }

    /** 3 秒内划走 = 对该剧不感兴趣。 */
    private void markSkipIfQuick() {
        JSONObject item = currentItem();
        if (item == null || player == null) return;
        if (player.getCurrentPosition() < 3000) {
            FeedStats.skip(getContext(), item.optString("id"));
        }
    }

    /** 进详情再回来时，从上次看到的位置继续。 */
    private void savePosition() {
        if (player == null) return;
        JSONObject item = currentItem();
        if (item == null) return;
        long position = player.getCurrentPosition();
        if (position <= 0) return;
        prefs().edit().putLong("pos:" + item.optString("id") + "#" + episode, position).apply();
    }

    private void restorePosition(String key) {
        long position = prefs().getLong("pos:" + key, 0);
        if (position > 1500 && player != null) player.seekTo(position);
    }

    private void onEpisodeStarted(String id, int number) {
        closeSpeedMenu();
        endedMarked = false;
        play3sMarked = false;
        FeedStats.impression(getContext(), id);
        updateHeart(id);
    }

    private void updateHeart(String id) {
        boolean saved = host != null && host.isBookmarked(id);
        heartView.setText(saved ? "♥" : "♡");
        heartView.setTextColor(saved ? 0xFFFF3B5C : 0xCCFFFFFF);
    }

    private void toggleHeart() {
        JSONObject item = currentItem();
        if (item == null || host == null) return;
        boolean saved = host.toggleBookmark(item);
        heartView.setText(saved ? "♥" : "♡");
        heartView.setTextColor(saved ? 0xFFFF3B5C : 0xCCFFFFFF);
        Toast.makeText(getContext(), saved ? "已收藏，可在「我的」查看" : "已取消收藏", Toast.LENGTH_SHORT).show();
    }

    private static final float[] SPEEDS = {0.75f, 1f, 1.25f, 1.5f, 2f};
    private android.widget.PopupWindow speedPopup;

    private static String speedLabel(float value) {
        String text = value == Math.rint(value)
                ? String.format(java.util.Locale.US, "%.1f", value)
                : String.format(java.util.Locale.US, "%.2f", value).replaceAll("0+$", "");
        return text + "×";
    }

    /** 半透明胶囊按钮，和播放器的悬浮控件同一套观感。 */
    private TextView pill(String text, int sizeSp) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(0x66000000);
        background.setCornerRadius(dp(18));
        view.setBackground(background);
        view.setPadding(dp(12), dp(6), dp(12), dp(6));
        return view;
    }

    private android.view.View speedPanelView;

    /** 内嵌面板而不是 PopupWindow：PopupWindow 在部分机型/嵌入式 WebView 上点击命中异常。 */
    private void showSpeedMenu() {
        if (speedPanelView != null) { closeSpeedMenu(); return; }
        final android.widget.LinearLayout panel = new android.widget.LinearLayout(getContext());
        panel.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(0xF21C1F22);
        background.setCornerRadius(dp(12));
        panel.setBackground(background);
        panel.setPadding(dp(6), dp(6), dp(6), dp(6));
        for (final float option : SPEEDS) {
            TextView cell = new TextView(getContext());
            cell.setText(speedLabel(option));
            cell.setTextSize(13);
            cell.setGravity(Gravity.CENTER);
            boolean selected = Math.abs(option - speed) < 0.001f;
            cell.setTextColor(selected ? Color.BLACK : Color.WHITE);
            android.graphics.drawable.GradientDrawable cellBg = new android.graphics.drawable.GradientDrawable();
            cellBg.setColor(selected ? 0xFFFFD54F : 0xFF3B4042);
            cellBg.setCornerRadius(dp(8));
            cell.setBackground(cellBg);
            android.widget.LinearLayout.LayoutParams params =
                    new android.widget.LinearLayout.LayoutParams(dp(52), dp(36));
            params.leftMargin = dp(3);
            params.rightMargin = dp(3);
            cell.setLayoutParams(params);
            cell.setOnClickListener(view -> {
                speed = option;
                speedButton.setText(speedLabel(option));
                if (player != null) player.setPlaybackSpeed(option);
                closeSpeedMenu();
            });
            panel.addView(cell);
        }
        LayoutParams params = new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.END | Gravity.BOTTOM;
        params.setMargins(0, 0, dp(16), dp(68));
        speedPanelView = panel;
        addView(panel, params);
    }

    private void closeSpeedMenu() {
        if (speedPanelView == null) return;
        removeView(speedPanelView);
        speedPanelView = null;
    }

    private void openFullscreen() {
        JSONObject item = currentItem();
        if (item == null || host == null) return;
        try {
            JSONObject payload = new JSONObject();
            payload.put("item", item);
            payload.put("episodes", episodes);
            payload.put("episode", episode);
            payload.put("index", 0);
            payload.put("fullscreen", true);
            host.openFullscreen(payload);
        } catch (Exception ignored) { }
    }

    private void updatePauseBadge() {
        if (pauseBadge == null || player == null) return;
        int state = player.getPlaybackState();
        boolean show = !player.isPlaying() && state != Player.STATE_IDLE && state != Player.STATE_ENDED;
        pauseBadge.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** 暂停时居中显示的标志：半透明圆 + 两条竖杠，点击继续播放。 */
    private final class PauseBadge extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        PauseBadge(Context context) { super(context); }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = Math.min(cx, cy) - dp(2);
            paint.setColor(0x66000000);
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setColor(0xFFFFFFFF);
            float barWidth = radius * 0.22f;
            float barHeight = radius * 0.76f;
            float gap = radius * 0.2f;
            float left = cx - gap / 2f - barWidth;
            android.graphics.RectF bar = new android.graphics.RectF(
                    left, cy - barHeight / 2f, left + barWidth, cy + barHeight / 2f);
            canvas.drawRoundRect(bar, barWidth / 2f, barWidth / 2f, paint);
            bar.offset(barWidth + gap, 0);
            canvas.drawRoundRect(bar, barWidth / 2f, barWidth / 2f, paint);
        }
    }

    private String referer() {
        try {
            return repository == null ? "" : repository.origin();
        } catch (Exception error) {
            return "";
        }
    }

    private JSONObject currentItem() {
        return items.optJSONObject(index);
    }

    private void advanceAfterEpisode() {
        note("ended pos=" + player.getCurrentPosition() + " dur=" + player.getDuration());
        JSONObject finished = currentItem();
        if (finished != null && !endedMarked) {
            endedMarked = true;
            FeedStats.complete(getContext(), finished.optString("id"));
        }
        if (episode < totalEpisodes) loadEpisode(episode + 1);
        else goNextDrama();
    }

    private void goNextDrama() {
        markSkipIfQuick();
        if (index + 1 >= items.length()) {
            index = 0;
            main.post(() -> Toast.makeText(getContext(), "已看完一轮推荐，从头开始", Toast.LENGTH_SHORT).show());
            note("wrap");
        } else {
            index++;
            note("next");
        }
        loadEpisode(1);
        prefetchDetails();
    }

    private void goPreviousDrama() {
        markSkipIfQuick();
        if (index <= 0) {
            main.post(() -> Toast.makeText(getContext(), "已经是第一个推荐了", Toast.LENGTH_SHORT).show());
            return;
        }
        index--;
        note("prev");
        loadEpisode(1);
    }

    private Map<String, String> headersOf(JSONObject ready) {
        Map<String, String> headers = new LinkedHashMap<>();
        String key = ready.optString("cdnAuthKey", "");
        String value = ready.optString("cdnAuthValue", "");
        String app = ready.optString("cdnApp", "");
        if (!key.isEmpty() && !value.isEmpty()) {
            headers.put(key, value);
            if (!app.isEmpty()) headers.put("x-cdn-app", app);
        }
        return headers;
    }

    private void attach(ExoPlayer engine, String id, int number, JSONObject ready) {
        String source = ready.optString("source");
        if (source.isEmpty()) throw new IllegalStateException("没有播放地址");
        engine.stop();
        engine.clearMediaItems();
        engine.setMediaSource(VideoPlaybackCache.sources(getContext(), referer(), headersOf(ready))
                        .createMediaSource(new MediaItem.Builder()
                                .setUri(source)
                                .setMediaId(id + "#" + number)
                                .setMimeType(MimeTypes.APPLICATION_M3U8)
                                .build()),
                0);
        engine.prepare();
    }

    private void loadEpisode(int number) {
        final JSONObject item = currentItem();
        if (item == null) return;
        final String id = item.optString("id");
        final String title = item.optString("title", "影片");
        final String key = id + "#" + number;

        if (adoptStandby(key)) {
            episode = number;
            totalEpisodes = Math.max(totalEpisodes, countEpisodes(id));
            setTitle(title, item.optString("score"));
            counterView.setText(counterText(0, 0));
            spinner.setVisibility(View.GONE);
            messageView.setVisibility(View.GONE);
            note("adopt");
            persistPosition();
            restorePosition(key);
            onEpisodeStarted(id, number);
            prepareStandby();
            return;
        }

        final int token = ++loadToken;
        main.post(() -> {
            spinner.setVisibility(View.VISIBLE);
            messageView.setVisibility(View.GONE);
            setTitle(title, item.optString("score"));
            counterView.setText("第 " + number + " 集");
        });

        worker.submit(() -> {
            try {
                JSONArray eps = episodesFor(id);
                String pageUrl = "";
                for (int i = 0; i < eps.length(); i++) {
                    JSONObject entry = eps.optJSONObject(i);
                    if (entry != null && entry.optInt("number") == number) { pageUrl = entry.optString("url"); break; }
                }
                JSONObject ready = readyFor(id, number, pageUrl);
                final JSONArray resolvedEps = eps;
                final JSONObject payload = ready;
                main.post(() -> {
                    if (destroyed || token != loadToken) return;
                    episodes = resolvedEps;
                    totalEpisodes = resolvedEps.length();
                    episode = number;
                    try {
                        attach(player, id, number, payload);
                        player.setVolume(1f);
                        player.setPlaybackSpeed(speed);
                        restorePosition(id + "#" + number);
                        player.setPlayWhenReady(!paused);
                        updatePauseBadge();
                    } catch (Exception error) {
                        spinner.setVisibility(View.GONE);
                        messageView.setText("加载失败，点击重试");
                        messageView.setVisibility(View.VISIBLE);
                        return;
                    }
                    counterView.setText(counterText(0, 0));
                    persistPosition();
                    onEpisodeStarted(id, number);
                    prepareStandby();
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (destroyed || token != loadToken) return;
                    spinner.setVisibility(View.GONE);
                    messageView.setText("加载失败，点击重试");
                    messageView.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void prepareStandby() {
        if (destroyed) return;
        JSONObject item = currentItem();
        if (item == null) return;
        String id = item.optString("id");
        if (totalEpisodes > 0 && episode < totalEpisodes) {
            warm(standbyNextKey, id, episode + 1, true);
        }
        JSONObject next = items.optJSONObject(index + 1);
        if (next == null) next = items.optJSONObject(0);
        if (next != null) {
            String nextId = next.optString("id");
            if (!nextId.isEmpty()) warm(standbySwipeKey, nextId, 1, false);
        }
    }

    private void warm(String currentKey, final String id, final int number, final boolean forNext) {
        final String key = id + "#" + number;
        if (key.equals(currentKey)) return;
        worker.submit(() -> {
            try {
                JSONArray eps = episodesFor(id);
                String pageUrl = "";
                for (int i = 0; i < eps.length(); i++) {
                    JSONObject entry = eps.optJSONObject(i);
                    if (entry != null && entry.optInt("number") == number) { pageUrl = entry.optString("url"); break; }
                }
                JSONObject ready = readyFor(id, number, pageUrl);
                main.post(() -> {
                    if (destroyed) return;
                    if (forNext) { releaseNext(); } else { releaseSwipe(); }
                    EpisodeLoadControl control = new EpisodeLoadControl(true);
                    ExoPlayer engine = createPlayer(control);
                    engine.setVolume(0f);
                    try { attach(engine, id, number, ready); }
                    catch (Exception error) { engine.release(); return; }
                    if (forNext) { standbyNext = engine; standbyNextKey = key; standbyNextControl = control; }
                    else { standbySwipe = engine; standbySwipeKey = key; standbySwipeControl = control; }
                });
            } catch (Exception ignored) { }
        });
    }

    private boolean adoptStandby(String key) {
        ExoPlayer adopted = null;
        EpisodeLoadControl control = null;
        if (standbyNext != null && key.equals(standbyNextKey)) {
            adopted = standbyNext;
            control = standbyNextControl;
            standbyNext = null;
            standbyNextKey = null;
            standbyNextControl = null;
        } else if (standbySwipe != null && key.equals(standbySwipeKey)) {
            adopted = standbySwipe;
            control = standbySwipeControl;
            standbySwipe = null;
            standbySwipeKey = null;
            standbySwipeControl = null;
        }
        if (adopted == null) return false;
        // 接管后恢复成正常缓冲，否则播到 5 秒处会卡住。
        if (control != null) control.promote();
        ExoPlayer old = player;
        playerView.setPlayer(null);
        player = adopted;
        player.setVolume(1f);
        playerView.setPlayer(player);
        player.setPlayWhenReady(!paused);
        if (old != null) old.release();
        return true;
    }

    private void releaseNext() {
        if (standbyNext != null) { standbyNext.release(); standbyNext = null; }
        standbyNextKey = null;
        standbyNextControl = null;
    }

    private void releaseSwipe() {
        if (standbySwipe != null) { standbySwipe.release(); standbySwipe = null; }
        standbySwipeKey = null;
        standbySwipeControl = null;
    }

    private void releaseStandby() {
        releaseNext();
        releaseSwipe();
    }

    private JSONObject readyFor(String id, int number, String pageUrl) throws Exception {
        String key = id + "#" + number;
        synchronized (readyCache) {
            JSONObject cached = readyCache.get(key);
            if (cached != null) return cached;
        }
        JSONObject ready = repository.episode(id, number, pageUrl);
        synchronized (readyCache) { readyCache.put(key, ready); }
        return ready;
    }

    private JSONArray episodesFor(String id) throws Exception {
        synchronized (detailCache) {
            JSONObject cached = detailCache.get(id);
            if (cached != null) {
                JSONArray eps = cached.optJSONArray("episodes");
                if (eps != null && eps.length() > 0) return eps;
            }
        }
        JSONObject detail = repository.detail(id);
        FeedStats.saveTags(getContext(), id, detail.optJSONArray("tags"));
        JSONArray eps = detail.optJSONArray("episodes");
        if (eps == null) eps = new JSONArray();
        synchronized (detailCache) { detailCache.put(id, detail); }
        return eps;
    }

    private int countEpisodes(String id) {
        synchronized (detailCache) {
            JSONObject cached = detailCache.get(id);
            if (cached == null) return totalEpisodes;
            JSONArray eps = cached.optJSONArray("episodes");
            return eps == null ? totalEpisodes : eps.length();
        }
    }

    private void prefetchDetails() {
        for (int offset = 0; offset <= PREFETCH_AHEAD; offset++) {
            JSONObject next = items.optJSONObject(index + offset);
            if (next == null) break;
            final String id = next.optString("id");
            if (id.isEmpty()) continue;
            synchronized (detailCache) { if (detailCache.containsKey(id)) continue; }
            worker.submit(() -> {
                try {
                    JSONObject detail = repository.detail(id);
                    synchronized (detailCache) { detailCache.put(id, detail); }
                } catch (Exception ignored) { }
            });
        }
    }

    private void note(String what) {
        MainActivity.noteDiagnostic(getContext(), "feed " + what + " idx=" + index + " ep=" + episode
                + " total=" + totalEpisodes + " id=" + (currentItem() == null ? "?" : currentItem().optString("id")));
    }
}
