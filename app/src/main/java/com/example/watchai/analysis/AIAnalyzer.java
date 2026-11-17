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
import com.example.watchai.model.HealthDataSet;

import java.util.ArrayList;
import java.util.List;

/** 本地智能分析引擎：基于趋势、阈值和相关性生成中文解读，完全离线 */
public class AIAnalyzer {

    public static class Insight {
        public String icon;      // 图标文字（如 ↗ / ! / ✓）
        public String title;
        public String detail;
        public int color;        // 0=绿 1=橙 2=蓝 3=红

        public Insight(String icon, String title, String detail, int color) {
            this.icon = icon; this.title = title; this.detail = detail; this.color = color;
        }
    }

    public static List<Insight> analyze(HealthDataSet data) {
        List<Insight> out = new ArrayList<>();
        if (data == null || data.days == null || data.days.isEmpty()) return out;

        int n = data.days.size();
        DayHealth latest = data.days.get(n - 1);
        DayHealth first = data.days.get(0);

        // 1) 睡眠趋势
        int[] scores = new int[n];
        float sum = 0;
        for (int i = 0; i < n; i++) {
            scores[i] = SleepScorer.score(data.days.get(i).sleep).score;
            sum += scores[i];
        }
        float avgScore = sum / n;
        float delta = scores[n - 1] - scores[0];
        if (delta > 6) {
            out.add(new Insight("↗", "睡眠评分持续改善",
                "近 " + n + " 天平均 " + (int) avgScore + " 分，最新一晚比 " + n + " 天前提升 " + (int) delta + " 分，作息调整见效。", 0));
        } else if (delta < -6) {
            out.add(new Insight("↘", "睡眠评分走低",
                "最新评分比 " + n + " 天前下降 " + (int) -delta + " 分，建议检查近期压力与入睡时间。", 1));
        } else {
            out.add(new Insight("→", "睡眠评分保持稳定",
                "近 " + n + " 天平均 " + (int) avgScore + " 分，波动较小，生物钟规律。", 2));
        }

        // 2) 深睡与压力相关性
        int lowDeepDays = 0, highStressDays = 0;
        for (DayHealth d : data.days) {
            if (d.sleep != null && d.sleep.hasData() && d.sleep.deepMinutes * 100f / d.sleep.totalMinutes() < 15f) lowDeepDays++;
            if (d.stress >= 55) highStressDays++;
        }
        if (lowDeepDays >= 3) {
            out.add(new Insight("!", "深睡不足预警",
                n + " 天中有 " + lowDeepDays + " 天深睡占比低于 15%。" +
                (highStressDays >= 3 ? "同期 " + highStressDays + " 天压力偏高，压力与深睡减少高度相关。" : "建议固定入睡时间并减少睡前刺激。"), 1));
        }

        // 3) 步数趋势
        int stepSum = 0;
        for (DayHealth d : data.days) stepSum += d.steps;
        int stepAvg = stepSum / n;
        if (latest.steps >= 10000) {
            out.add(new Insight("✓", "活动量达标",
                "今日 " + latest.steps + " 步，已超过 1 万步目标，近 " + n + " 天日均 " + stepAvg + " 步。", 0));
        } else if (stepAvg < 6000) {
            out.add(new Insight("!", "活动量偏低",
                "日均仅 " + stepAvg + " 步，建议每天增加 2000 步的快走，有助于提升深睡与 HRV。", 1));
        } else {
            out.add(new Insight("→", "活动量中等",
                "日均 " + stepAvg + " 步，距离 1 万步目标还差 " + Math.max(0, 10000 - latest.steps) + " 步。", 2));
        }

        // 4) 静息心率与 HRV
        if (latest.sleep != null && latest.sleep.avgHrv > 0) {
            int hrv = latest.sleep.avgHrv;
            if (hrv >= 50) out.add(new Insight("✓", "恢复状态良好", "昨晚 HRV " + hrv + " ms，处于较高水平，身体恢复充分。", 0));
            else if (hrv < 35) out.add(new Insight("!", "身体可能疲劳", "昨晚 HRV 仅 " + hrv + " ms，偏低，建议降低训练强度并保证睡眠。", 3));
        }

        // 5) 血氧
        if (latest.spo2 > 0 && latest.spo2 < 95) {
            out.add(new Insight("!", "血氧偏低", "最近血氧 " + latest.spo2 + "%，如持续低于 95% 建议咨询医生。", 3));
        }

        // 6) 压力
        if (latest.stress >= 60) {
            out.add(new Insight("!", "压力偏高", "最近压力指数 " + latest.stress + "/100，建议 5 分钟深呼吸或轻度拉伸。", 1));
        }

        // 7) 锻炼
        if (data.exercises != null && !data.exercises.isEmpty()) {
            long ws = System.currentTimeMillis() / 1000L - 6L * 86400L;
            int cnt = 0, sec = 0; double dist = 0;
            for (com.example.watchai.model.Exercise e : data.exercises)
                if (e.startSec >= ws) { cnt++; sec += e.durationSec; dist += e.distanceM; }
            int min = sec / 60;
            if (cnt == 0) {
                out.add(new Insight("!", "本周还没有锻炼记录",
                    "手表上完成的运动会自动出现在这里。每周 150 分钟中等强度运动能明显改善睡眠与 HRV。", 1));
            } else if (min >= 150) {
                out.add(new Insight("✓", "每周运动达标",
                    "近 7 天 " + cnt + " 次锻炼、累计 " + min + " 分钟" +
                    (dist > 0 ? String.format("、%.1f 公里", dist / 1000.0) : "") + "，已达世卫建议，保持节奏。", 0));
            } else {
                out.add(new Insight("→", "运动量可再增加",
                    "近 7 天 " + cnt + " 次、累计 " + min + " 分钟，距离每周 150 分钟还差 " + Math.max(0, 150 - min) + " 分钟。", 2));
            }
            com.example.watchai.model.Exercise last = data.exercises.get(0);
            if (last.avgHr > 0 && last.distanceM > 500) {
                out.add(new Insight("i", "最近一场：" + last.name,
                    String.format("%.2f 公里 · %d 分钟 · 平均心率 %d bpm · 消耗 %d 千卡",
                        last.distanceKm(), last.durationSec / 60, last.avgHr, last.calories), 2));
            }
        }

        if (out.isEmpty()) {
            out.add(new Insight("i", "数据样本不足",
                "当前还没有足够的历史数据可供分析，先在小米运动健康里同步手表数据。", 2));
        }
        return out;
    }
}
