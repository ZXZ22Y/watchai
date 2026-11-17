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
import com.example.watchai.model.SleepRecord;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 内置 AI 健康异常提示 —— 基于个人基线的可解释偏离检测。
 *
 * 【设计依据（调研）】
 *  - 个人内基线（within-person baseline）：Fitbit/Google 用「静息心率偏离个人平时范围」
 *    触发提醒；HRV 连续多晚下降提示恢复不足/压力；呼吸率与皮温的细微变化作为健康变化信号。
 *  - 学术：Apple Watch/Fitbit/Oura 的纵向研究显示，感染/炎症发作前 HR↑、夜间 HRV 昼夜节律改变、
 *    活动量下降可持续数周；夜间呼吸率（可由 HRV 的 RSA 提取）是呼吸道感染的早期标志物。
 *  - 可解释性：不输出黑箱概率，而是给出「哪个指标、偏离基线多少、方向」的证据链。
 *
 * 【方法】
 *  1) 取近 5 晚有数据的睡眠：前 4 晚为个人基线，昨晚为对照（样本不足时降级置信度）。
 *  2) 每个指标用中位数 + MAD（中位绝对偏差）建立稳健基线，抗单点异常；
 *     修正 z = 0.6745*(x - median)/MAD，无 MAD 时用相对变化率。
 *  3) 六类信号：夜间心率↑、HRV↓、睡眠时长↓、深睡+REM占比↓、夜间清醒↑、血氧↓。
 *  4) 干扰因素排除：前一晚大型运动 / 熬夜 / 作息大乱（旅行时差）——命中则显著降权。
 *  5) 多信号同向累加 → 风险分 → 等级；置信度 = 数据完整度 × 基线稳定性 × 一致性 × 无干扰。
 *
 * 【边界】结果仅作健康提醒，不构成医疗诊断；明确在文案中声明。
 */
public class HealthAnomalyDetector {

    public static final String DISCLAIMER =
            "本提示基于手表睡眠数据的统计偏离，仅供健康参考，不构成医疗诊断。如有持续不适请及时就医。";

    public static class Reason {
        public String metric;      // 指标名
        public String detail;      // 偏离描述（含数值）
        public int contribution;   // 对风险分的贡献
        public Reason(String m, String d, int c) { metric = m; detail = d; contribution = c; }
    }

    public static class Result {
        public String level = "normal";    // normal / low / medium / high
        public String label = "未见明显异常";
        public int riskScore = 0;          // 0-100 偏离强度
        public int confidence = 0;         // 0-100 置信度
        public boolean hasAlert = false;   // 是否达到提示门槛
        public boolean confounded = false; // 是否被干扰因素解释
        public String confounderText = "";
        public List<Reason> reasons = new ArrayList<Reason>();
        public String advice = "";         // 提示文案
        public int baselineNights = 0;     // 基线晚数
        public String headline = "";       // 一句话结论
    }

    /** 单指标信号定义 */
    private static class Signal {
        String name;
        double cur, base;
        double z;            // 修正 z（正=向"异常方向"偏离的幅度）
        double rel;          // 相对变化（异常方向为正）
        boolean lowerIsWorse; // 真实方向：true=昨晚比基线低才是异常（HRV/睡眠/血氧等）
        double noiseFloor;   // 离散度为0时的最小噪声尺度（用于稳健 z 兜底）
        int maxScore;        // 该指标最大贡献
        boolean available;
        Signal(String n, boolean lower, double noise, int mx) {
            name = n; lowerIsWorse = lower; noiseFloor = noise; maxScore = mx;
        }
        /** 面向用户的真实变化描述（不混淆异常方向） */
        String changeText() {
            if (base <= 0) return "";
            double pct = (cur - base) / base * 100;
            if (Math.abs(pct) < 0.5) return "与基线持平";
            return (pct > 0 ? "比基线高 " : "比基线低 ") + Math.round(Math.abs(pct)) + "%"
                    + "（基线 " + fmt(base) + "，昨晚 " + fmt(cur) + "）";
        }
    }

    public static Result evaluate(HealthDataSet data) {
        Result r = new Result();
        if (data == null || data.days == null) return r;

        // 收集最近 6 晚有数据的睡眠（新→旧）
        List<SleepRecord> sleeps = new ArrayList<SleepRecord>();
        List<DayHealth> sleepDays = new ArrayList<DayHealth>();
        for (int i = data.days.size() - 1; i >= 0 && sleeps.size() < 6; i--) {
            DayHealth d = data.days.get(i);
            if (d.sleep != null && d.sleep.hasData()) { sleeps.add(d.sleep); sleepDays.add(d); }
        }
        if (sleeps.isEmpty()) {
            r.advice = "暂无睡眠数据，同步手表后即可开始建立你的个人基线。";
            return r;
        }
        if (sleeps.size() < 3) {
            r.label = "样本不足"; r.level = "normal";
            r.confidence = 20;
            r.advice = "目前只有 " + sleeps.size() + " 晚睡眠数据。连续记录 5 晚后，我就能基于你的个人基线识别异常变化。";
            r.headline = "正在建立个人基线（" + sleeps.size() + "/5 晚）";
            return r;
        }

        // 昨晚 = 对照；其余为基线（最多 5 晚）
        SleepRecord last = sleeps.get(0);
        DayHealth lastDay = sleepDays.get(0);
        List<SleepRecord> base = sleeps.subList(1, sleeps.size());
        r.baselineNights = base.size();

        // ---- 构造六类信号（异常方向已归一化：值越大越"像生病"）----
        List<Signal> signals = new ArrayList<Signal>();
        signals.add(hrSignal(last, base));                 // 夜间心率升高
        signals.add(hrvSignal(last, base));                // HRV 降低
        signals.add(durationSignal(last, base));           // 睡眠时长缩短
        signals.add(depthSignal(last, base));              // 深睡+REM 占比降低
        signals.add(awakeSignal(last, base));              // 夜间清醒增多
        signals.add(spo2Signal(lastDay, sleepDays));       // 血氧降低

        // ---- 干扰因素：前一晚大型运动 / 熬夜 / 作息大乱 ----
        List<String> confounders = detectConfounders(last, lastDay, base, sleepDays);
        r.confounded = !confounders.isEmpty();
        if (r.confounded) {
            StringBuilder cb = new StringBuilder("检测到可能影响睡眠的因素：");
            for (int i = 0; i < confounders.size(); i++) {
                if (i > 0) cb.append("、");
                cb.append(confounders.get(i));
            }
            cb.append("。这些情况本身就会改变睡眠与心率，因此本次不作疾病风险提示。");
            r.confounderText = cb.toString();
        }

        // ---- 汇总证据 ----
        double total = 0, maxTotal = 0;
        int availCnt = 0, strongCnt = 0;
        List<Reason> reasons = new ArrayList<Reason>();
        for (Signal s : signals) {
            if (!s.available) continue;
            availCnt++;
            maxTotal += s.maxScore;
            // 偏离幅度：z 为主，rel 为辅（取两者中更保守的归一值）
            double zPart = clamp01(s.z / 2.5);       // z≥2.5 视为强偏离
            double relPart = clamp01(s.rel / 0.30);  // 相对变化≥30% 视为强偏离
            double dev = Math.max(zPart, relPart * 0.8);
            double contrib = dev * s.maxScore;
            total += contrib;
            if (dev >= 0.55) strongCnt++;
            if (dev >= 0.30) {
                reasons.add(new Reason(s.name, s.name + " " + s.changeText(), (int) Math.round(contrib)));
            }
        }
        // 排序：贡献大的在前
        java.util.Collections.sort(reasons, (a, b) -> Integer.compare(b.contribution, a.contribution));
        r.reasons = reasons;

        // 风险分（0-100）= 已偏离信号 / 可用信号满分的比例
        r.riskScore = maxTotal > 0 ? (int) Math.round(total / maxTotal * 100) : 0;
        if (r.confounded) r.riskScore = (int) Math.round(r.riskScore * 0.35); // 有干扰因素大幅降权

        // 置信度：数据完整度(可用信号) × 基线长度 × 一致性(强信号数)
        double completeness = clamp01(availCnt / 6.0);
        double baselineConf = clamp01(base.size() / 5.0);
        double agreement = strongCnt >= 2 ? 1.0 : (strongCnt == 1 ? 0.6 : 0.3);
        r.confidence = (int) Math.round(completeness * 100 * 0.35 + baselineConf * 100 * 0.30 + agreement * 100 * 0.35);
        if (r.confounded) r.confidence = (int) Math.round(r.confidence * 0.5);

        // ---- 分档（多信号同向 + 置信度门槛，避免误报）----
        // 关键误报控制：必须有「至少 2 个中等以上偏离」或「1 个强偏离」才考虑提示；
        // 单指标轻微波动、干扰因素、低置信度一律不出提示。
        int mediumCnt = 0;
        for (Signal s : signals) {
            if (!s.available) continue;
            double zPart = clamp01(s.z / 2.5), relPart = clamp01(s.rel / 0.30);
            if (Math.max(zPart, relPart * 0.8) >= 0.30) mediumCnt++;
        }
        if (r.confounded || availCnt < 3 || r.confidence < 45 || (strongCnt < 1 && mediumCnt < 2)) {
            r.level = "normal"; r.label = "未见明显异常"; r.hasAlert = false;
        } else if (r.riskScore >= 58 && strongCnt >= 2 && r.confidence >= 62) {
            r.level = "high"; r.label = "建议留意"; r.hasAlert = true;
        } else if (r.riskScore >= 35 && r.confidence >= 52) {
            r.level = "medium"; r.label = "轻微偏离"; r.hasAlert = true;
        } else if (r.riskScore >= 25 && mediumCnt >= 2) {
            r.level = "low"; r.label = "轻微波动"; r.hasAlert = true;
        } else {
            r.level = "normal"; r.label = "未见明显异常"; r.hasAlert = false;
        }

        // ---- 可解释文案 ----
        r.headline = buildHeadline(r, strongCnt);
        r.advice = buildAdvice(r, confounders);
        return r;
    }

    // ==================== 信号构造 ====================

    /** 夜间心率升高（越大越异常） */
    private static Signal hrSignal(SleepRecord cur, List<SleepRecord> base) {
        Signal s = new Signal("夜间心率", false, 2.0, 20); // 噪声尺度 2bpm
        List<Double> v = new ArrayList<Double>();
        for (SleepRecord x : base) if (x.avgHeartRate > 0) v.add((double) x.avgHeartRate);
        if (cur.avgHeartRate > 0 && v.size() >= 2) {
            s.available = true; s.cur = cur.avgHeartRate; s.base = median(v);
            s.rel = s.base > 0 ? (s.cur - s.base) / s.base : 0;
            s.z = robustZ(s.cur, v, s.noiseFloor);
        }
        return s;
    }

    /** HRV 降低（越低越异常 → 取负向） */
    private static Signal hrvSignal(SleepRecord cur, List<SleepRecord> base) {
        Signal s = new Signal("夜间 HRV", true, 3.0, 22); // 噪声尺度 3ms
        List<Double> v = new ArrayList<Double>();
        for (SleepRecord x : base) if (x.avgHrv > 0) v.add((double) x.avgHrv);
        if (cur.avgHrv > 0 && v.size() >= 2) {
            s.available = true; s.cur = cur.avgHrv; s.base = median(v);
            s.rel = s.base > 0 ? (s.base - s.cur) / s.base : 0; // 下降为正
            s.z = -robustZ(s.cur, v, s.noiseFloor);
        }
        return s;
    }

    /** 睡眠时长缩短 */
    private static Signal durationSignal(SleepRecord cur, List<SleepRecord> base) {
        Signal s = new Signal("睡眠时长", true, 15.0, 18); // 15min 噪声
        List<Double> v = new ArrayList<Double>();
        for (SleepRecord x : base) if (x.totalMinutes() > 0) v.add((double) x.totalMinutes());
        if (cur.totalMinutes() > 0 && v.size() >= 2) {
            s.available = true; s.cur = cur.totalMinutes() / 60.0; s.base = median(v) / 60.0;
            s.rel = s.base > 0 ? (s.base - s.cur) / s.base : 0;
            s.z = -robustZ(cur.totalMinutes(), v, s.noiseFloor);
        }
        return s;
    }

    /** 深睡+REM 占比降低 */
    private static Signal depthSignal(SleepRecord cur, List<SleepRecord> base) {
        Signal s = new Signal("深睡+REM 占比", true, 3.0, 12); // 3 个百分点噪声
        List<Double> v = new ArrayList<Double>();
        for (SleepRecord x : base) if (x.totalMinutes() > 0) v.add(depthPct(x));
        if (cur.totalMinutes() > 0 && v.size() >= 2) {
            double cp = depthPct(cur); double m = median(v);
            s.available = true; s.cur = cp; s.base = m;
            s.rel = m > 0 ? (m - cp) / m : 0;
            s.z = -robustZ(cp, v, s.noiseFloor);
        }
        return s;
    }

    /** 夜间清醒增多 */
    private static Signal awakeSignal(SleepRecord cur, List<SleepRecord> base) {
        Signal s = new Signal("夜间清醒", false, 5.0, 10); // 5min 噪声
        List<Double> v = new ArrayList<Double>();
        for (SleepRecord x : base) v.add((double) x.awakeMinutes);
        if (v.size() >= 2) {
            s.available = true; s.cur = cur.awakeMinutes; s.base = median(v);
            s.rel = s.base > 3 ? (s.cur - s.base) / Math.max(1, s.base) : clamp01((cur.awakeMinutes - 15) / 60.0);
            s.z = robustZ(cur.awakeMinutes, v, s.noiseFloor);
        }
        return s;
    }

    /** 血氧降低（用当日血氧，基线取前几日血氧） */
    private static Signal spo2Signal(DayHealth curDay, List<DayHealth> allSleepDays) {
        Signal s = new Signal("血氧", true, 0.5, 12); // 0.5% 噪声
        List<Double> v = new ArrayList<Double>();
        for (int i = 1; i < allSleepDays.size(); i++) {
            int sp = allSleepDays.get(i).spo2;
            if (sp > 0) v.add((double) sp);
        }
        int cur = curDay != null ? curDay.spo2 : 0;
        if (cur > 0 && v.size() >= 2) {
            s.available = true; s.cur = cur; s.base = median(v);
            s.rel = s.base > 0 ? (s.base - cur) / s.base : 0;
            s.z = -robustZ(cur, v, s.noiseFloor);
        }
        return s;
    }

    // ==================== 干扰因素 ====================

    /** 前一晚大型运动 / 熬夜 / 作息大乱（旅行） */
    private static List<String> detectConfounders(SleepRecord last, DayHealth lastDay,
                                                   List<SleepRecord> base, List<DayHealth> sleepDays) {
        List<String> out = new ArrayList<String>();
        // 1) 前一晚/当日大型运动
        if (lastDay != null && (lastDay.trainingLoad >= 70 || lastDay.exerciseMinutes >= 60))
            out.add("前一天有较大量运动（负荷 " + lastDay.trainingLoad + " / 锻炼 " + lastDay.exerciseMinutes + " 分钟）");
        // 2) 熬夜：入睡时间比个人习惯晚 1.5h 以上
        if (last.bedTimeMs > 0 && base.size() >= 2) {
            List<Double> beds = new ArrayList<Double>();
            SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.CHINA);
            double cur = minuteOfDay(last.bedTimeMs, hm);
            if (cur < 12 * 60) cur += 24 * 60;
            for (SleepRecord x : base) {
                if (x.bedTimeMs <= 0) continue;
                double m = minuteOfDay(x.bedTimeMs, hm);
                if (m < 12 * 60) m += 24 * 60;
                beds.add(m);
            }
            if (beds.size() >= 2 && (cur - median(beds)) > 90)
                out.add("昨晚明显熬夜（入睡比平时晚 " + Math.round(cur - median(beds)) + " 分钟）");
        }
        // 3) 作息大乱：近几晚入睡时间波动 > 3h（疑似旅行/时差）
        if (base.size() >= 3) {
            List<Double> beds = new ArrayList<Double>();
            SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.CHINA);
            for (SleepRecord x : base) {
                if (x.bedTimeMs <= 0) continue;
                double m = minuteOfDay(x.bedTimeMs, hm);
                if (m < 12 * 60) m += 24 * 60;
                beds.add(m);
            }
            if (beds.size() >= 3 && stdev(beds) > 180)
                out.add("近期作息波动较大（疑似旅行或时差）");
        }
        return out;
    }

    // ==================== 文案 ====================

    private static String buildHeadline(Result r, int strongCnt) {
        if (r.confounded) return "昨晚的变化可能由运动或作息引起";
        if (!r.hasAlert) return "昨晚各项指标与个人基线基本一致";
        if (r.level.equals("high")) return "多项恢复指标明显偏离，建议留意身体状况";
        if (r.level.equals("medium")) return "部分恢复指标出现偏离，留意身体信号";
        return "昨晚指标有轻微波动";
    }

    private static String buildAdvice(Result r, List<String> confounders) {
        StringBuilder sb = new StringBuilder();
        if (r.confounded) {
            sb.append(r.confounderText);
        } else if (!r.hasAlert) {
            sb.append("昨晚的睡眠时长、心率、HRV 等与你的个人基线相近，没有发现需要特别留意的异常变化。继续保持规律作息。");
        } else {
            sb.append("昨晚有 ").append(r.reasons.size()).append(" 项指标偏离你的个人基线：");
            for (int i = 0; i < Math.min(3, r.reasons.size()); i++) {
                if (i > 0) sb.append("；");
                sb.append(r.reasons.get(i).detail);
            }
            sb.append("。");
            if (r.level.equals("high"))
                sb.append("多项信号同向变化，可能与身体不适或感染早期有关。建议今天降低强度、保证休息与补水，并留意是否出现发热、咽痛、乏力等症状。");
            else if (r.level.equals("medium"))
                sb.append("变化幅度不大，可能与疲劳或压力有关。建议今天安排轻松活动，今晚早点睡，观察明晚是否恢复。");
            else
                sb.append("属于正常范围内的波动，不必担心，保持当前节奏即可。");
        }
        sb.append(" ").append(DISCLAIMER);
        return sb.toString();
    }

    // ==================== 统计工具 ====================

    /** 稳健 z：以 MAD 为尺度；MAD≈0 时用该指标的最小噪声尺度兜底，避免把正常波动判成异常。 */
    private static double robustZ(double x, List<Double> v, double noiseFloor) {
        double med = median(v);
        List<Double> dev = new ArrayList<Double>();
        for (double a : v) dev.add(Math.abs(a - med));
        double mad = median(dev) * 1.4826; // MAD 缩放到近似标准差
        double scale = Math.max(mad, noiseFloor); // 尺度下限 = 生理噪声，防止除零误报
        return (x - med) / scale;
    }

    private static double depthPct(SleepRecord s) {
        int total = s.totalMinutes();
        return total > 0 ? (s.deepMinutes + s.remMinutes) * 100.0 / total : 0;
    }

    private static double median(List<Double> v) {
        if (v == null || v.isEmpty()) return 0;
        Double[] a = v.toArray(new Double[0]);
        Arrays.sort(a);
        int m = a.length >> 1;
        return (a.length % 2 == 1) ? a[m] : (a[m - 1] + a[m]) / 2;
    }

    private static double stdev(List<Double> v) {
        if (v == null || v.isEmpty()) return 0;
        double mean = 0;
        for (double x : v) mean += x;
        mean /= v.size();
        double var = 0;
        for (double x : v) var += (x - mean) * (x - mean);
        return Math.sqrt(var / v.size());
    }

    private static double minuteOfDay(long ms, SimpleDateFormat fmt) {
        String[] p = fmt.format(new Date(ms)).split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    private static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }
    private static String fmt(double v) { return String.format(Locale.CHINA, "%.1f", v); }
}
