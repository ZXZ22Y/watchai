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

import com.example.watchai.model.DayHealth;
import com.example.watchai.model.Exercise;
import com.example.watchai.model.HealthDataSet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 从手表真实历史数据自动提取两项个人心率基线，无需用户手填：
 *  - 静息心率：多日「每日最低心率」的中位数（最稳健的静息心率代理），睡眠平均心率交叉校验；
 *  - 最大心率：历史所有运动实测峰值的高分位（过滤异常），比年龄公式 220-age 更贴合个人。
 * 返回值 0 表示数据不足，调用方应回退到经验值。
 */
public class HeartRateBaseline {

    public static class Result {
        public int restHr = 0;        // 自动静息心率 bpm
        public String restSource = ""; // 来源说明
        public int restSamples = 0;   // 用于计算的样本数
        public int maxHr = 0;         // 自动最大心率 bpm
        public String maxSource = "";
        public int maxSamples = 0;
    }

    public static Result compute(HealthDataSet data) {
        Result r = new Result();
        if (data == null) return r;

        // ---------- 静息心率 ----------
        // 主口径：每日最低心率（hr_record 全天最小值），取中位数，过滤异常
        List<Integer> dailymin = new ArrayList<Integer>();
        List<Integer> sleepAvg = new ArrayList<Integer>();
        if (data.days != null) {
            for (DayHealth d : data.days) {
                if (d.minHeartRate >= 35 && d.minHeartRate <= 95) dailymin.add(d.minHeartRate);
                if (d.sleep != null && d.sleep.avgHeartRate >= 40 && d.sleep.avgHeartRate <= 95)
                    sleepAvg.add(d.sleep.avgHeartRate);
            }
        }
        int rest = 0, restSamples = 0;
        String restSrc = "";
        if (!dailymin.isEmpty()) {
            Collections.sort(dailymin);
            rest = median(dailymin);
            restSamples = dailymin.size();
            restSrc = "近 " + restSamples + " 天夜间最低心率中位数";
        } else if (!sleepAvg.isEmpty()) {
            // 兜底：睡眠期间平均心率偏低，减去小修正近似静息心率
            Collections.sort(sleepAvg);
            rest = Math.round(median(sleepAvg)) - 2;
            restSamples = sleepAvg.size();
            restSrc = "近 " + restSamples + " 晚睡眠平均心率推算";
        }
        if (rest < 35 || rest > 95) rest = 0;
        r.restHr = Math.round(rest);
        r.restSource = r.restHr > 0 ? restSrc : "";
        r.restSamples = restSamples;

        // ---------- 最大心率 ----------
        // 主口径：历史所有运动的实测峰值，取上分位（比单场峰值稳健，比 220-age 个人化）
        List<Integer> peaks = new ArrayList<Integer>();
        if (data.exercises != null) {
            for (Exercise e : data.exercises) {
                int m = e.maxHr;
                // 部分运动汇总表没有 max_hrm，用逐点轨迹解码出的峰值兜底
                if (m <= 0 && e.hasTrack && e.trackMaxHr > 0) m = Math.round((float) e.trackMaxHr);
                if (m >= 120 && m <= 220) peaks.add(m);
            }
        }
        int max = 0;
        if (!peaks.isEmpty()) {
            Collections.sort(peaks);
            // P90 高分位：接近个人真实上限，又不被单次极值或测量毛刺带偏
            int idx = (int) Math.ceil(peaks.size() * 0.90) - 1;
            if (idx < 0) idx = 0;
            if (idx >= peaks.size()) idx = peaks.size() - 1;
            max = peaks.get(idx);
            // 与最高值差距过大时用最高值（样本少时避免低估）
            if (peaks.size() <= 3) max = peaks.get(peaks.size() - 1);
            r.maxSamples = peaks.size();
            r.maxSource = "近 " + peaks.size() + " 场运动实测峰值";
        }
        if (max < 140 || max > 220) max = 0;
        r.maxHr = max;
        if (r.maxHr == 0) r.maxSource = "";
        return r;
    }

    private static int median(List<Integer> sorted) {
        int n = sorted.size();
        if (n == 0) return 0;
        if (n % 2 == 1) return sorted.get(n / 2);
        return Math.round((sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2f);
    }
}
