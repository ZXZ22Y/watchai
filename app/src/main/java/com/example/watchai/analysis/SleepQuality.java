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
import com.example.watchai.model.SleepRecord;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 睡眠质量评估 —— 身体准备度的核心。
 *
 * 设计原则（回应用户诉求）：准备度要「推算睡得怎么样」，
 * 而不是拿心率/HRV/压力是否在"正常范围"来达标式打分。
 * 因此准备度完全由睡眠本身决定：
 *   时长(25) + 深睡结构(20) + REM结构(20) + 睡眠效率(15) + 作息规律(20) = 100
 * 睡眠债作为软修正（长期欠睡会扣分）。HRV/心率只在文字里做恢复旁证，不参与计分。
 */
public class SleepQuality {

    public static class Result {
        public int score = 0;              // 睡眠质量/准备度 0-100
        public int durationScore = 0;      // 时长 0-25
        public int deepScore = 0;          // 深睡 0-20
        public int remScore = 0;           // REM 0-20
        public int efficiencyScore = 0;    // 效率/连续 0-15
        public int regularityScore = 0;    // 作息规律 0-20
        public int debtMin = 0;            // 睡眠债（分钟，正=欠，负=盈余）
        public int hrvAdj = 0;             // HRV 恢复修正分（-8..+8），基于个人基线
        public String regularityText = "";
        public String debtText = "";
        public String hrvText = "";        // HRV 恢复解读
    }

    public static Result eval(List<DayHealth> days) {
        Result r = new Result();
        if (days == null || days.isEmpty()) return r;

        // 最近 7 个有睡眠数据的晚上（用于规律性/睡眠债）
        List<SleepRecord> recent = new ArrayList<SleepRecord>();
        SleepRecord last = null;
        for (int i = days.size() - 1; i >= 0 && recent.size() < 7; i--) {
            SleepRecord x = days.get(i).sleep;
            if (x != null && x.hasData()) {
                recent.add(x);
                if (last == null) last = x;
            }
        }
        if (last == null) return r;

        int total = last.totalMinutes();              // 不含清醒
        float hours = total / 60f;
        int deepPct = pct(last.deepMinutes, total);
        int remPct = pct(last.remMinutes, total);
        int awake = last.awakeMinutes;

        // 1) 时长：7-9h 满分（25）
        if (hours >= 7f && hours <= 9f) r.durationScore = 25;
        else if (hours >= 6f && hours < 7f) r.durationScore = 18;
        else if (hours > 9f && hours <= 10f) r.durationScore = 19;
        else if (hours >= 5f && hours < 6f) r.durationScore = 11;
        else r.durationScore = 5;

        // 2) 深睡结构：占比 >=20% 满分，兼顾绝对量（20）
        if (deepPct >= 20) r.deepScore = 20;
        else if (deepPct >= 15) r.deepScore = 16;
        else if (deepPct >= 10) r.deepScore = 11;
        else r.deepScore = 5;
        if (last.deepMinutes >= 90 && r.deepScore < 20) r.deepScore = Math.min(20, r.deepScore + 2);
        if (last.deepMinutes > 0 && last.deepMinutes < 30) r.deepScore = Math.min(r.deepScore, 6);

        // 3) REM 结构：占比 >=22% 满分（20）
        if (remPct >= 22) r.remScore = 20;
        else if (remPct >= 18) r.remScore = 16;
        else if (remPct >= 12) r.remScore = 11;
        else r.remScore = 5;

        // 4) 睡眠效率：睡眠 / (睡眠+清醒)（15）
        float eff = total > 0 ? total * 100f / (total + Math.max(0, awake)) : 0;
        if (eff >= 90) r.efficiencyScore = 15;
        else if (eff >= 85) r.efficiencyScore = 12;
        else if (eff >= 80) r.efficiencyScore = 9;
        else r.efficiencyScore = 5;

        // 5) 作息规律：入睡/起床时间的波动（20）
        r.regularityScore = regularity(recent, r);

        // 睡眠债：相对 8 小时目标的累计缺口（优化：按每晚分布描述，给出可执行补足节奏）
        for (SleepRecord s : recent) {
            if (s.totalMinutes() <= 0) continue;
            r.debtMin += (480 - s.totalMinutes());
        }
        float debtH = r.debtMin / 60f;
        if (r.debtMin > 30) {
            int nights = Math.max(1, (int) Math.ceil(r.debtMin / 60f / 0.5f)); // 每晚多睡 30 分钟需几晚补回
            r.debtText = "近 " + recent.size() + " 晚累计欠睡 " + String.format("%.1f", debtH)
                    + " 小时（平均每晚少睡 " + String.format("%.0f", r.debtMin / (float) recent.size())
                    + " 分钟）。若每晚多睡 30 分钟，约 " + nights + " 晚可补回。";
        } else if (r.debtMin < -30) {
            r.debtText = "近 " + recent.size() + " 晚睡眠盈余 " + String.format("%.1f", -debtH)
                    + " 小时，恢复储备充足，可适度安排稍高强度训练。";
        } else {
            r.debtText = "近 " + recent.size() + " 晚睡眠基本达标，没有明显睡眠债。";
        }

        // 睡眠基础分 + 睡眠债软修正（长期欠睡最多扣 10 分）
        int base = r.durationScore + r.deepScore + r.remScore + r.efficiencyScore + r.regularityScore;
        if (r.debtMin > 0) base -= Math.min(10, r.debtMin / 60f / 2.4f);

        // HRV 恢复修正：以个人近 7 晚 HRV 为基线，昨晚相对基线的偏差反映前一日运动/疲劳后的自主神经恢复。
        // 权重刻意小（最多 ±8 分），睡眠结构仍是准备度的主导。
        applyHrv(recent, last, base, r);

        r.score = Math.max(1, Math.min(100, Math.round(base + r.hrvAdj)));

        return r;
    }

    /** 作息规律性：入睡/起床时间标准差越小分越高 */
    private static int regularity(List<SleepRecord> recent, Result r) {
        if (recent.size() < 2) {
            r.regularityText = "再积累几晚睡眠数据，就能评估你的作息是否规律。";
            return 12; // 中性分，不奖惩
        }
        List<Double> beds = new ArrayList<Double>();
        List<Double> wakes = new ArrayList<Double>();
        SimpleDateFormat hmFmt = new SimpleDateFormat("HH:mm", Locale.CHINA);
        for (SleepRecord s : recent) {
            if (s.bedTimeMs > 0) {
                double m = minuteOfDay(s.bedTimeMs, hmFmt);
                if (m < 12 * 60) m += 24 * 60; // 凌晨入睡映射到前一天晚间，23:30=1410, 00:30=1470
                beds.add(m);
            }
            if (s.wakeTimeMs > 0) wakes.add(minuteOfDay(s.wakeTimeMs, hmFmt));
        }
        double sd = 0; int n = 0;
        if (beds.size() >= 2) { sd += stdev(beds); n++; }
        if (wakes.size() >= 2) { sd += stdev(wakes); n++; }
        if (n == 0) { r.regularityText = "入睡/起床时间数据不足，暂无法评估规律性。"; return 12; }
        sd /= n;
        int score;
        if (sd <= 20) score = 20;
        else if (sd <= 40) score = 16;
        else if (sd <= 60) score = 11;
        else if (sd <= 90) score = 6;
        else score = 3;
        if (sd <= 30) r.regularityText = "作息非常规律（波动约 " + Math.round(sd) + " 分钟），这是高质量睡眠的重要基础。";
        else if (sd <= 60) r.regularityText = "作息较稳定（波动约 " + Math.round(sd) + " 分钟），继续保持固定入睡时间会更好。";
        else r.regularityText = "作息波动较大（约 " + Math.round(sd) + " 分钟），尽量固定起床时间是改善睡眠最有效的第一步。";
        return score;
    }

    /**
     * HRV 恢复修正：HRV 反映自主神经（副交感）活性，前一天训练/疲劳后恢复越充分，
     * 当晚 HRV 越接近或高于个人基线；明显低于基线则提示尚未完全恢复。
     * 关键：用「个人基线」而非绝对阈值（不同人 HRV 差异极大），避免达标式打分。
     * 修正幅度限制在 -8..+8，睡眠结构始终主导。
     */
    private static void applyHrv(List<SleepRecord> recent, SleepRecord last, int base, Result r) {
        int lastHrv = last.avgHrv;
        // 个人基线：近 7 晚有效 HRV 的中位数（比均值更能抗单晚异常）
        List<Double> hs = new ArrayList<Double>();
        for (SleepRecord s : recent) if (s.avgHrv > 0) hs.add((double) s.avgHrv);
        if (lastHrv <= 0 || hs.size() < 2) {
            r.hrvText = "再积累几晚 HRV 数据，就能结合你的个人基线评估恢复程度。";
            return;
        }
        double median = median(hs);
        if (median <= 0) { r.hrvText = ""; return; }
        double ratio = lastHrv / median; // 昨晚 / 个人基线

        if (ratio >= 1.10) {
            r.hrvAdj = 8;
            r.hrvText = "昨晚 HRV " + lastHrv + " ms，高于你的近期基线（约 " + Math.round(median)
                    + " ms）10% 以上，说明前一天的运动 / 疲劳已充分恢复，状态在线。";
        } else if (ratio >= 1.03) {
            r.hrvAdj = 4;
            r.hrvText = "昨晚 HRV " + lastHrv + " ms，略高于近期基线（约 " + Math.round(median)
                    + " ms），恢复良好。";
        } else if (ratio >= 0.92) {
            r.hrvAdj = 0;
            r.hrvText = "昨晚 HRV " + lastHrv + " ms，与近期基线（约 " + Math.round(median)
                    + " ms）接近，恢复处于个人常态。";
        } else if (ratio >= 0.82) {
            r.hrvAdj = -4;
            r.hrvText = "昨晚 HRV " + lastHrv + " ms，低于近期基线（约 " + Math.round(median)
                    + " ms），可能前一天训练强度偏高、身体尚未完全恢复，今天宜降低强度。";
        } else {
            r.hrvAdj = -8;
            r.hrvText = "昨晚 HRV " + lastHrv + " ms，明显低于近期基线（约 " + Math.round(median)
                    + " ms），恢复不足的迹象较明显，建议今天以休息或轻度活动为主。";
        }
    }

    private static double median(List<Double> vals) {
        if (vals == null || vals.isEmpty()) return 0;
        Double[] a = vals.toArray(new Double[0]);
        java.util.Arrays.sort(a);
        int m = a.length >> 1;
        return (a.length % 2 == 1) ? a[m] : (a[m - 1] + a[m]) / 2;
    }

    private static double minuteOfDay(long ms, SimpleDateFormat fmt) {
        String[] p = fmt.format(new Date(ms)).split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    private static double stdev(List<Double> v) {
        double mean = 0;
        for (double x : v) mean += x;
        mean /= v.size();
        double var = 0;
        for (double x : v) var += (x - mean) * (x - mean);
        return Math.sqrt(var / v.size());
    }

    private static int pct(int part, int total) { return total > 0 ? Math.round(part * 100f / total) : 0; }
}
