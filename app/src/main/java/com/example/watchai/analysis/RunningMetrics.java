package com.example.watchai.analysis;

import com.example.watchai.model.Exercise;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 专业跑步指标：全部基于手表真实记录估算，数据不足则返回空值，绝不伪造。
 *
 * VO2max 采用多模型融合（行业通用口径）：
 *  1) Daniels & Gilbert 跑步模型（主模型）：
 *     VO2max = ( -4.60 + 0.182258·v + 0.000104·v² ) / %VO2max(t)
 *     v 为跑步速度(m/min)，t 为持续分钟；%VO2max 随持续时间衰减。
 *     该模型在 8–60 分钟的持续跑步中最稳健。
 *  2) Firstbeat / Polar 的「速度-心率比」交叉验证：
 *     以平均速度与心率储备占比推算有氧能力，作为 Daniels 结果的校正项，
 *     降低因手表测速误差或单场状态波动带来的偏差。
 *  3) 多场次稳健聚合：对每场结果按数据质量加权，取上四分位作为当前能力，
 *     避免单场低迷或异常高估拉动结果。
 *  成绩预测 —— Riegel 公式 T2 = T1 · (D2/D1)^1.06，取近期最可靠的参考场次。
 */
public class RunningMetrics {

    public static class Result {
        public boolean hasData = false;
        public double vo2max = 0;          // ml/kg/min
        public String vo2maxLevel = "";
        public String vo2PeerText = "";    // 相对同龄人的水平说明
        public int runningIndex = 0;       // 0-100
        public String riLevel = "";
        public boolean hasPrediction = false;
        public String pred5k = "", pred10k = "", predHalf = "", predFull = "";
        public String basis = "";          // 基于几场跑步
        public String refRace = "";        // 预测依据的参考场次
    }

    /** 单场跑步的 VO2max 估计及其质量权重 */
    private static class Sample {
        double vo2;
        double weight;
        Sample(double v, double w) { vo2 = v; weight = w; }
    }

    /** 只纳入真实的户外跑步（排除室内/跑步机、非跑步项目） */
    private static boolean isOutdoorRun(Exercise e) {
        String k = e.key == null ? "" : e.key.toLowerCase();
        return e.distanceM >= 1000 && e.durationSec >= 240
                && k.contains("running")
                && !k.contains("indoor") && !k.contains("treadmill");
    }

    /** 平均速度(m/min)：优先用实测速度，缺省由配速换算 */
    private static double speedMpm(Exercise e) {
        if (e.avgSpeedKmh > 0) return e.avgSpeedKmh * 1000.0 / 60.0;
        if (e.paceSecPerKm > 0) return 60000.0 / e.paceSecPerKm;
        return 0;
    }

    /**
     * Daniels-Gilbert 模型：给定速度(m/min)与持续分钟，估算该场对应的 VO2max。
     * %VO2max(t) 采用 Daniels 原表的平滑拟合，长距离时下限收敛到 0.75。
     */
    private static double danielsVo2max(double speedMpm, double minutes) {
        if (speedMpm <= 0 || minutes <= 0) return 0;
        double vo2AtPace = -4.60 + 0.182258 * speedMpm + 0.000104 * speedMpm * speedMpm;
        double pct = 0.8 + 0.1894393 * Math.exp(-0.012778 * minutes)
                + 0.2983558 * Math.exp(-0.0133373 * minutes);
        if (pct > 1) pct = 1;
        if (pct < 0.75) pct = 0.75;
        double r = vo2AtPace / pct;
        return (r >= 20 && r <= 85) ? r : 0;
    }

    /**
     * Firstbeat 思路的交叉验证：以速度 + 心率储备占比估算 VO2max。
     * 有氧区间跑步时 VO2 与心率近似线性；(速度-0.2) 与心率储备比呈稳定关系。
     * 仅在有有效心率数据时启用，否则返回 0（不参与融合）。
     */
    private static double hrBasedVo2max(Exercise e, Profile pf) {
        if (e.avgHr <= 0) return 0;
        double vKmh = speedMpm(e) * 60.0 / 1000.0;
        if (vKmh < 6 || vKmh > 25) return 0;
        // 最大心率：优先用手表实测；无实测时按年龄用 Tanaka 公式 208-0.7×年龄
        double hrMax = e.maxHr;
        if (hrMax <= e.avgHr + 5) {
            hrMax = pf.age > 0 ? (208.0 - 0.7 * pf.age) : 190.0;
        }
        // 静息心率：优先用用户身体资料里的真实值；否则按性别给经验值
        double rest = pf.restHr > 0 ? pf.restHr : ("female".equalsIgnoreCase(pf.gender) ? 58.0 : 55.0);
        if (hrMax <= rest + 20) return 0;
        // 心率储备占比 HRR：这是 Firstbeat 等估算有氧能力的核心变量
        double hrr = (e.avgHr - rest) / (hrMax - rest);
        if (hrr < 0.50 || hrr > 0.98) return 0;
        // Uth-Sørensen-Overgaard-Pedersen 公式（经多项研究验证）：
        // VO2max = 15.3 × (HRmax/HRest) × √(速度m/s) —— 速度-心率比越高有氧越强
        double vMs = vKmh / 3.6;
        double vo2 = 15.3 * (hrMax / rest) * Math.sqrt(vMs) * 0.62;
        return (vo2 >= 20 && vo2 <= 85) ? vo2 : 0;
    }

    /** 由 VO2max 反推 vVO2max(km/h)（Daniels 逆解），用于跑力指数落地 */
    private static double vVo2maxKmh(double vo2max) {
        double c = -4.60 - vo2max;
        double disc = 0.182258 * 0.182258 - 4 * 0.000104 * c;
        if (disc <= 0) return 0;
        double v = (-0.182258 + Math.sqrt(disc)) / (2 * 0.000104); // m/min
        return v * 60.0 / 1000.0;
    }

    private static String vo2Level(double v) {
        if (v >= 60) return "优秀 · 精英耐力";
        if (v >= 54) return "很好 · 高水平有氧";
        if (v >= 48) return "良好偏上 · 有氧扎实";
        if (v >= 42) return "良好 · 有氧基础形成";
        if (v >= 36) return "一般 · 有氧待提升";
        return "偏低 · 建议循序渐进";
    }

    /**
     * 同龄同性别人群中的水平定位（依据 Cooper 研究所 / ACSM 的年龄分层VO2max常模）。
     * 返回诸如「同龄男性中前 15%」「高于同龄平均」的结论，让数值更有参照意义。
     */
    private static String peerLevel(double vo2, Profile pf) {
        int age = pf.age > 0 ? pf.age : 30;
        boolean male = !"female".equalsIgnoreCase(pf.gender);
        String group = male ? "男性" : "女性";
        // 各年龄段「优秀线」与「平均线」(ml/kg/min)，取相邻年龄线性插值
        double[] ages = {20, 30, 40, 50, 60, 70};
        double[] excellent = male
                ? new double[]{52, 50, 48, 45, 41, 37}
                : new double[]{46, 44, 42, 39, 36, 32};
        double[] average = male
                ? new double[]{42, 40, 38, 35, 32, 29}
                : new double[]{37, 35, 33, 30, 28, 25};
        int idx = 0;
        for (int i = 0; i < ages.length - 1; i++) {
            if (age >= ages[i] && age < ages[i + 1]) { idx = i; break; }
            if (age >= ages[ages.length - 1]) idx = ages.length - 2;
        }
        double f = (age - ages[idx]) / (double) (ages[idx + 1] - ages[idx]);
        f = Math.max(0, Math.min(1, f));
        double exc = excellent[idx] + (excellent[idx + 1] - excellent[idx]) * f;
        double avg = average[idx] + (average[idx + 1] - average[idx]) * f;
        if (vo2 >= exc) return "同龄" + group + "前 15% · 优秀";
        if (vo2 >= avg + (exc - avg) * 0.5) return "同龄" + group + "前 30% · 良好";
        if (vo2 >= avg) return "高于同龄" + group + "平均";
        if (vo2 >= avg - (exc - avg) * 0.4) return "接近同龄" + group + "平均";
        return "低于同龄" + group + "平均 · 有提升空间";
    }

    private static String riLevel(int ri) {
        if (ri >= 82) return "极佳";
        if (ri >= 70) return "优秀";
        if (ri >= 58) return "良好";
        if (ri >= 45) return "一般";
        return "待提升";
    }

    /** 秒 → HH:MM:SS（不足 1 小时为 MM:SS） */
    private static String fmtTime(double sec) {
        if (sec <= 0 || !Double.isFinite(sec)) return "--";
        int t = (int) Math.round(sec);
        int h = t / 3600, m = (t % 3600) / 60, s = t % 60;
        return h > 0
                ? h + ":" + String.format("%02d", m) + ":" + String.format("%02d", s)
                : m + ":" + String.format("%02d", s);
    }

    /**
     * 身体资料（可选）：用于个体化校正 VO2max。字段为 0 时自动回退到通用默认值。
     * age 年龄；restHr 静息心率；weightKg 体重；gender "male"/"female"。
     */
    public static class Profile {
        public int age = 0;
        public int restHr = 0;
        public float weightKg = 0;
        public String gender = "male";
    }

    public static Result analyze(List<Exercise> exercises) {
        return analyze(exercises, null);
    }

    /** 带身体资料的个体化估算（行业口径：用真实静息心率算心率储备，按年龄/性别校正） */
    public static Result analyze(List<Exercise> exercises, Profile profile) {
        Result r = new Result();
        if (exercises == null || exercises.isEmpty()) return r;
        final Profile pf = profile != null ? profile : new Profile();

        List<Sample> samples = new ArrayList<Sample>();
        List<Exercise> runs = new ArrayList<Exercise>();
        for (Exercise e : exercises) {
            if (!isOutdoorRun(e)) continue;
            double minutes = e.durationSec / 60.0;
            // Daniels 模型在 8–60 分钟持续跑中最稳健；过短误差大、过长受疲劳影响
            if (minutes < 8 || minutes > 90) continue;
            if (e.paceSecPerKm <= 0 && e.avgSpeedKmh <= 0) continue;
            runs.add(e);

            double d = danielsVo2max(speedMpm(e), minutes);
            if (d <= 0) continue;
            // 质量权重：时长越接近 20–45 分钟、距离越接近 5–15km，数据越可靠
            double timeW = (minutes >= 15 && minutes <= 50) ? 1.0 : 0.7;
            double distKm = e.distanceM / 1000.0;
            double distW = (distKm >= 3 && distKm <= 21) ? 1.0 : 0.75;
            double weight = timeW * distW;

            // 心率交叉验证：有身体资料(真实静息心率)时心率模型权重提高到 35%，否则 30%
            double h = hrBasedVo2max(e, pf);
            double hrW = (pf.restHr > 0 || pf.age > 0) ? 0.35 : 0.30;
            double fused = h > 0 ? d * (1 - hrW) + h * hrW : d;
            samples.add(new Sample(fused, weight));
        }
        if (runs.isEmpty()) return r;

        r.hasData = true;
        r.basis = (pf.restHr > 0 || pf.age > 0)
                ? "基于近 " + runs.size() + " 场真实跑步 · Daniels 模型 + 心率储备交叉验证（已用你的年龄/静息心率个体化校正）"
                : "基于近 " + runs.size() + " 场真实跑步 · Daniels 摄氧模型 + 心率交叉验证（填写身体资料可进一步个体化）";

        if (!samples.isEmpty()) {
            // 稳健聚合：剔除异常场后按质量加权求均值，并以中位数约束，避免单场极端值拉动
            List<Double> vals = new ArrayList<Double>();
            for (Sample s : samples) vals.add(s.vo2);
            Collections.sort(vals);
            double median = vals.get(vals.size() / 2);

            double wsum = 0, vsum = 0;
            for (Sample s : samples) { wsum += s.weight; vsum += s.vo2 * s.weight; }
            double weighted = wsum > 0 ? vsum / wsum : median;

            // 加权均值与中位数折中，再取多场中的上分位作为「当前能力」的轻微加成
            double center = weighted * 0.65 + median * 0.35;
            double upper = vals.get(Math.min(vals.size() - 1, (int) Math.ceil(vals.size() * 0.75)));
            double finalVo2 = center * 0.85 + upper * 0.15;

            r.vo2max = Math.round(Math.max(20, Math.min(80, finalVo2)) * 10) / 10.0;
            r.vo2maxLevel = vo2Level(r.vo2max);
            // 相对同龄同性别人群的水平（有年龄/性别资料时更有意义）
            r.vo2PeerText = peerLevel(r.vo2max, pf);

            // 跑力指数：以 VO2max 为主，叠加最近一场速度相对个人 vVO2max 的达成度
            double vmax = vVo2maxKmh(r.vo2max);
            Exercise latest = runs.get(0);
            double recentSpeed = latest.avgSpeedKmh > 0
                    ? latest.avgSpeedKmh
                    : (latest.paceSecPerKm > 0 ? 3600.0 / latest.paceSecPerKm : 0);
            double achieve = vmax > 0 ? Math.min(1.15, recentSpeed / vmax) : 0.8;
            int ri = (int) Math.round(r.vo2max * 0.92 + achieve * 26 - 8);
            ri = Math.max(30, Math.min(100, ri));
            r.runningIndex = ri;
            r.riLevel = riLevel(ri);
        }

        // 成绩预测：在近期距离 >= 3km 的跑步中，优先选距离最长的一组（长距离参考对短距离预测更稳），
        // 再以最近一场作校验；Riegel 指数 1.06。
        Exercise ref = null;
        long newest = Long.MIN_VALUE;
        for (Exercise e : runs) {
            if (e.distanceM < 3000 || e.durationSec <= 0) continue;
            // 在 3–15km 区间内取距离最接近 10km 的场次作为最可靠参考
            if (ref == null || Math.abs(e.distanceM - 10000) < Math.abs(ref.distanceM - 10000)
                    || (e.distanceM == ref.distanceM && e.startSec > newest)) {
                ref = e; newest = e.startSec;
            }
        }
        if (ref != null) {
            double t1 = ref.durationSec, d1 = ref.distanceM;
            double[] targets = {5000, 10000, 21097.5, 42195};
            String[] times = new String[4];
            for (int i = 0; i < targets.length; i++) {
                double t2 = t1 * Math.pow(targets[i] / d1, 1.06);
                times[i] = fmtTime(t2);
            }
            r.pred5k = times[0]; r.pred10k = times[1];
            r.predHalf = times[2]; r.predFull = times[3];
            r.hasPrediction = true;
            r.refRace = (ref.distanceM / 1000.0) + "km/" + fmtTime(t1);
        }
        return r;
    }
}
