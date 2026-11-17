/*
 * ============================================================
 * 腕上智析 WatchAI  —  原创作品，请勿去除本声明
 * 原作者：酷安 @想吃薯片zz
 * 开源地址：https://github.com/ZXZ22Y/watchai
 * 本文件为原作者编写，二次修改/分发请保留出处与署名。
 * 溯源标识：WATCHAI-ORIGIN-ByCoolapk-XiangChiShuaiPianZZ-2026
 * ============================================================
 */
package com.example.watchai.analysis;

import com.example.watchai.model.SleepRecord;

/**
 * 睡眠评分（与"身体准备度"完全同一口径）。
 *
 * 设计原则：评分只由睡眠本身决定，不拿 HRV/心率是否在"正常范围"达标式打分。
 *   时长(25) + 深睡结构(20) + REM结构(20) + 睡眠效率(15) + 作息规律(20) = 100
 *
 * 注：字段名 continuityScore/recoveryScore 为历史兼容保留，
 * 现语义分别为「睡眠效率(15)」和「作息规律(20)」。作息规律需要多晚数据，
 * 单晚评分时给中性分（12/20），并写入 advice 说明。
 */
public class SleepScorer {

    public static class Result {
        public int score;            // 0-100
        public String level;         // 优秀/良好/一般/较差
        public int durationScore;    // 时长 0-25
        public int deepScore;        // 深睡 0-20
        public int remScore;         // REM 0-20
        public int continuityScore;  // 睡眠效率 0-15（历史字段名）
        public int recoveryScore;    // 作息规律 0-20（历史字段名）
        public String[] advice;
    }

    /** 单晚评分（scores 数组用；作息规律无历史序列时取中性分） */
    public static Result score(SleepRecord s) {
        Result r = new Result();
        if (s == null || !s.hasData()) {
            r.score = 0;
            r.level = "无数据";
            r.advice = new String[]{"暂无睡眠数据，请先在小米运动健康同步手表数据。"};
            return r;
        }

        float hours = s.sleepHours();
        int total = s.totalMinutes();

        // 1) 时长 7-9h 满分（25）
        if (hours >= 7f && hours <= 9f) r.durationScore = 25;
        else if (hours >= 6f && hours < 7f) r.durationScore = 18;
        else if (hours > 9f && hours <= 10f) r.durationScore = 19;
        else if (hours >= 5f && hours < 6f) r.durationScore = 11;
        else r.durationScore = 5;

        // 2) 深睡结构（20）
        int deepPct = total > 0 ? Math.round(s.deepMinutes * 100f / total) : 0;
        if (deepPct >= 20) r.deepScore = 20;
        else if (deepPct >= 15) r.deepScore = 16;
        else if (deepPct >= 10) r.deepScore = 11;
        else r.deepScore = 5;
        if (s.deepMinutes >= 90 && r.deepScore < 20) r.deepScore = Math.min(20, r.deepScore + 2);
        if (s.deepMinutes > 0 && s.deepMinutes < 30) r.deepScore = Math.min(r.deepScore, 6);

        // 3) REM 结构（20）
        int remPct = total > 0 ? Math.round(s.remMinutes * 100f / total) : 0;
        if (remPct >= 22) r.remScore = 20;
        else if (remPct >= 18) r.remScore = 16;
        else if (remPct >= 12) r.remScore = 11;
        else r.remScore = 5;

        // 4) 睡眠效率：睡眠 /(睡眠+清醒)（15）
        float eff = total > 0 ? total * 100f / (total + Math.max(0, s.awakeMinutes)) : 0;
        if (eff >= 90) r.continuityScore = 15;
        else if (eff >= 85) r.continuityScore = 12;
        else if (eff >= 80) r.continuityScore = 9;
        else r.continuityScore = 5;

        // 5) 作息规律（20）：单晚评分无历史序列，给中性分；床位/起床时间缺失时同样中性
        r.recoveryScore = 12;

        r.score = r.durationScore + r.deepScore + r.remScore + r.continuityScore + r.recoveryScore;
        r.score = Math.max(0, Math.min(100, r.score));
        if (r.score >= 90) r.level = "优秀";
        else if (r.score >= 80) r.level = "良好";
        else if (r.score >= 70) r.level = "一般";
        else r.level = "较差";

        r.advice = buildAdvice(s, hours, deepPct, remPct, eff);
        return r;
    }

    private static String[] buildAdvice(SleepRecord s, float hours, int deepPct, int remPct, float eff) {
        java.util.List<String> list = new java.util.ArrayList<String>();
        if (hours < 7f) list.add("睡眠时长不足 " + String.format("%.1f", 7f - hours) + " 小时，建议提前 30 分钟入睡。");
        else if (hours > 9f) list.add("睡眠偏长，留意是否因疲劳或作息不规律导致。");
        else list.add("睡眠时长理想，保持在 7–9 小时的健康区间。");
        if (deepPct < 15) list.add("深睡比例偏低（" + deepPct + "%），睡前一小时减少屏幕使用、避免剧烈运动。");
        else list.add("深睡比例 " + deepPct + "%，身体修复状况良好。");
        if (remPct < 18) list.add("REM 睡眠偏少，可能影响记忆与情绪调节，建议固定起床时间。");
        if (eff < 85) list.add("睡眠效率 " + Math.round(eff) + "%，夜间清醒较多，注意卧室温度、光线并减少起夜。");
        if (list.isEmpty()) list.add("各项指标均衡，继续保持当前的作息节奏。");
        return list.toArray(new String[0]);
    }
}
