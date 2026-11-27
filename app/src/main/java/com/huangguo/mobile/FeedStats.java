package com.huangguo.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 推荐埋点与轻量排序。
 *
 * 三个埋点（与抖音一致的最小集合）：
 *  - 曝光 impression：卡片真正开始播
 *  - 有效播放 play3s：播放超过 3 秒
 *  - 完播 complete：整集播完（非空播放列表）
 * 另外记录一个负向信号 skip：3 秒内被划走。
 *
 * 打分只用两类特征：剧本身的反馈（喜欢/划走）和标签偏好（看完的题材加权、
 * 划走的题材降权）。没有标签数据时退化为"质量分 + 探索随机"，不会完全失去排序。
 */
final class FeedStats {
    private static final String PREFS = "feed_stats";
    private static final Random RANDOM = new Random();

    private FeedStats() { }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ 埋点写入

    static void impression(Context context, String id) {
        bump(context, "d:" + id, 0);
    }

    static void play3s(Context context, String id) {
        bump(context, "d:" + id, 1);
    }

    static void complete(Context context, String id) {
        bump(context, "d:" + id, 2);
        for (String tag : tagsOf(context, id)) bump(context, "t:" + tag, 1);
    }

    static void skip(Context context, String id) {
        bump(context, "d:" + id, 3);
        for (String tag : tagsOf(context, id)) bump(context, "t:" + tag, 2);
    }

    private static synchronized void bump(Context context, String key, int slot) {
        SharedPreferences preferences = prefs(context);
        int[] values = parse(preferences.getString(key, ""));
        values[slot] = values[slot] + 1;
        preferences.edit().putString(key, values[0] + "," + values[1] + "," + values[2] + "," + values[3]).apply();
    }

    private static int[] parse(String raw) {
        int[] values = new int[4];
        if (raw == null || raw.isEmpty()) return values;
        String[] parts = raw.split(",");
        for (int i = 0; i < values.length && i < parts.length; i++) {
            try { values[i] = Integer.parseInt(parts[i].trim()); } catch (Exception ignored) { }
        }
        return values;
    }

    // ------------------------------------------------------------ 标签缓存

    static void saveTags(Context context, String id, JSONArray tags) {
        if (tags == null || tags.length() == 0) return;
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < tags.length(); i++) {
            String tag = tags.optString(i).trim();
            if (tag.isEmpty()) continue;
            if (builder.length() > 0) builder.append('|');
            builder.append(tag);
        }
        if (builder.length() == 0) return;
        prefs(context).edit().putString("g:" + id, builder.toString()).apply();
    }

    static String[] tagsOf(Context context, String id) {
        String raw = prefs(context).getString("g:" + id, "");
        if (raw == null || raw.isEmpty()) return new String[0];
        return raw.split("\\|");
    }

    // ------------------------------------------------------------ 打分

    /** 单剧偏好：完播加权、划走扣分，再按曝光归一。 */
    private static double dramaAffinity(Context context, String id) {
        int[] v = parse(prefs(context).getString("d:" + id, ""));
        if (v[0] <= 0) return 0;
        return (2.0 * v[2] + 0.5 * v[1] - 1.5 * v[3]) / Math.max(1, v[0]);
    }

    /** 标签偏好：同类看完加分、同类划走扣分（-1 ~ +2）。 */
    private static double tagAffinity(Context context, JSONArray tags) {
        if (tags == null || tags.length() == 0) return 0;
        double total = 0;
        int counted = 0;
        for (int i = 0; i < tags.length(); i++) {
            String tag = tags.optString(i);
            if (tag.isEmpty()) continue;
            int[] v = parse(prefs(context).getString("t:" + tag, ""));
            if (v[1] + v[2] == 0) continue;
            total += (2.0 * v[1] - 1.5 * v[2]) / Math.max(1, v[1] + v[2]);
            counted++;
        }
        if (counted == 0) return 0;
        return Math.max(-1.0, Math.min(2.0, total / counted));
    }

    private static double rating(JSONObject item) {
        String raw = item.optString("score", "").replaceAll("[^0-9.]", "");
        try { return Math.min(10.0, Double.parseDouble(raw)); } catch (Exception ignored) { return 0; }
    }

    /**
     * 重排推荐池：分数 = 质量分 + 标签偏好 + 同剧反馈 + 探索抖动。
     * 每 5 条插 1 条"低分/冷门"候选做探索，避免越推越窄。
     */
    static JSONArray rank(Context context, JSONArray pool) {
        if (pool == null || pool.length() <= 1) return pool == null ? new JSONArray() : pool;
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < pool.length(); i++) {
            JSONObject item = pool.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id");
            double score = 0.45 * (rating(item) / 10.0)
                    + 0.9 * tagAffinity(context, tagsArray(context, id))
                    + 0.8 * dramaAffinity(context, id)
                    + RANDOM.nextDouble() * 0.55;
            scored.add(new Scored(item, score));
        }
        scored.sort((left, right) -> Double.compare(right.score, left.score));

        // 探索位：从尾部 30% 里抽没有排进去的，插到第 5、10、15… 位
        List<Scored> head = new ArrayList<>(scored);
        JSONArray out = new JSONArray();
        int exploreWindow = Math.max(3, head.size() / 3);
        int nextExplore = 4;
        for (int i = 0; i < head.size(); i++) {
            if (i == nextExplore && head.size() > 8) {
                int from = Math.max(0, head.size() - exploreWindow);
                if (from < head.size() - 1) {
                    int pick = from + RANDOM.nextInt(head.size() - from);
                    out.put(head.remove(pick).item);
                    nextExplore += 5;
                    i--;
                    continue;
                }
            }
            out.put(head.get(i).item);
        }
        return out;
    }

    private static JSONArray tagsArray(Context context, String id) {
        JSONArray tags = new JSONArray();
        for (String tag : tagsOf(context, id)) tags.put(tag);
        return tags;
    }

    private static final class Scored {
        final JSONObject item;
        final double score;
        Scored(JSONObject item, double score) { this.item = item; this.score = score; }
    }
}
