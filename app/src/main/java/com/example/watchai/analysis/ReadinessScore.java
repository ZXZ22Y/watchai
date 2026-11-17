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
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 身体准备度（1–10）—— 基于「前一天 + 昨晚」真实数据的训练就绪度综合评分。
 *
 * 严格按三步方案实现：
 *   第 0 步 红旗筛查：任一命中 → 建议今天休息（静息心率、夜间血氧可自动查；
 *            呼吸率 / 手腕温度 / 发热咽痛腹泻 / 关节痛手表无数据，跳过并标注）。
 *   第 1 步 三项打分（满分 100）：
 *            恢复信号 40 = HRV vs 7晚基线 20 + 静息心率 vs 基线 12
 *                          + 呼吸率/夜间血氧 5 + 手腕温度偏差 3
 *            睡眠     35 = 总时长 15 + 深睡+REM占比 10 + 睡眠分数/规律性 10
 *            负荷     25 = 急慢性负荷比(7d/28d) 12 + 昨日训练强度与时长 8
 *                          + 连续高强度天数 5
 *            主观修正 ±10：晨起感受/酸痛/晨脉（本 App 无录入，按 0 计并标注）
 *   第 2 步 分档对应训练：≥80 好 / 65-79 正常 / 50-64 偏低 / 35-49 差 / <35 很差
 *   第 3 步 当天可回补：说明分数动态、补觉补碳水的回升空间。
 *
 * 数据缺失原则：手表没有采集的项一律「按无异常计入（得满分）」，并在解读里
 * 明确标注未提供，绝不臆造数值；有数据的项严格按方案扣分规则执行。
 */
public class ReadinessScore {

    public static class Result {
        // ---- 主分数 ----
        public int score = 0;              // 百分制综合分 0-100
        public int readiness10 = 1;        // 对外展示 1-10

        // ---- 第0步 红旗 ----
        public boolean redFlag = false;
        public String redFlagText = "";

        // ---- 第1步 三大项 ----
        public int recoveryScore = 0;      // 恢复信号 /40
        public int sleepScore = 0;         // 睡眠 /35
        public int loadScore = 0;          // 负荷 /25
        public int subjectiveMod = 0;      // 主观修正 -10..+5（无录入=0）

        // 恢复信号明细
        public int hrvScore = 0, rhrScore = 0, respSpo2Score = 0, tempScore = 0; // 20/12/5/3
        // 睡眠明细
        public int sleepDurationScore = 0, sleepStructureScore = 0, sleepRegularScore = 0; // 15/10/10
        // 负荷明细
        public int loadRatioScore = 0, yesterdayLoadScore = 0, consecutiveLoadScore = 0;   // 12/8/5

        // ---- 逐项解读文案 ----
        public String hrvText = "", rhrText = "", spo2Text = "", tempText = "";
        public String durText = "", structText = "", regText = "";
        public String loadRatioText = "", yestLoadText = "", consText = "";

        // ---- 第2/3步 文案 ----
        public String tier = "";           // 好/正常/偏低/差/很差
        public String tierAdvice = "";     // 分档对应的当天训练
        public String reboundText = "";    // 当天可回补
        public String detailText = "";     // 评分构成明细（多行）
        public String prevDate = "";       // 评估基准日

        // ---- 兼容旧展示字段（透传 SleepQuality，供既有卡片用）----
        public int sleepPart = 0, durationPart = 0, deepPart = 0, remPart = 0;
        public int efficiencyPart = 0, regularityPart = 0, hrvAdj = 0, sleepDebtMin = 0;
        public String regularityText = "", debtText = "";
    }

    public static Result evaluate(HealthDataSet data) {
        Result r = new Result();
        if (data == null || data.days == null || data.days.isEmpty()) return r;
        List<DayHealth> days = data.days;
        int n = days.size();
        DayHealth latest = days.get(n - 1);

        SleepRecord last = null;
        for (int i = n - 1; i >= 0; i--) {
            SleepRecord s = days.get(i).sleep;
            if (s != null && s.hasData()) { last = s; break; }
        }
        DayHealth prev = previousDay(days);
        r.prevDate = latest.date != null ? latest.date : "";

        List<String> notes = new ArrayList<String>();

        // ---------- 基线 ----------
        int restHr = 0;
        try { restHr = HeartRateBaseline.compute(data).restHr; } catch (Exception ignored) { }
        List<Double> hrvVals = new ArrayList<Double>();
        for (int i = n - 1, c = 0; i >= 0 && c < 7; i--) {
            SleepRecord s = days.get(i).sleep;
            if (s != null && s.avgHrv > 0) { hrvVals.add((double) s.avgHrv); c++; }
        }
        double hrvBase = median(hrvVals);
        int rhr = latest.minHeartRate > 0 ? latest.minHeartRate : (last != null ? last.avgHeartRate : 0);
        int nightSpo2Min = nightSpo2Min(latest);

        // ---------- 第 0 步：红旗筛查 ----------
        List<String> flags = new ArrayList<String>();
        if (restHr > 0 && rhr >= restHr + 10)
            flags.add("静息心率 " + rhr + " bpm，高出你的基线 " + (rhr - restHr) + " bpm");
        if (nightSpo2Min > 0 && nightSpo2Min < 90)
            flags.add("夜间血氧最低 " + nightSpo2Min + "%，低于 90%");
        if (!flags.isEmpty()) {
            r.redFlag = true;
            StringBuilder fb = new StringBuilder("需要警惕：");
            for (int i = 0; i < flags.size(); i++) { if (i > 0) fb.append("；"); fb.append(flags.get(i)); }
            fb.append("。按红旗规则，今天建议休息。");
            r.redFlagText = fb.toString();
        }
        notes.add("呼吸率、手腕温度、发热/咽痛/腹泻、关节痛等症状手表未采集，红旗筛查仅覆盖静息心率与夜间血氧。");

        // ---------- 第 1 步 · 恢复信号（40）----------
        // 1) 夜间 HRV vs 7 晚基线 20：低10%扣6，低20%扣12
        if (last != null && last.avgHrv > 0 && hrvBase > 0) {
            double ratio = last.avgHrv / hrvBase;
            if (ratio < 0.80) {
                r.hrvScore = 8;
                r.hrvText = "昨晚 HRV " + last.avgHrv + " ms，低于基线 20% 以上，扣 12 分，恢复明显不足。";
            } else if (ratio < 0.90) {
                r.hrvScore = 14;
                r.hrvText = "昨晚 HRV " + last.avgHrv + " ms，低于基线约 " + Math.round((1 - ratio) * 100) + "%，扣 6 分。";
            } else {
                r.hrvScore = 20;
                r.hrvText = "昨晚 HRV " + last.avgHrv + " ms，与 7 晚基线（约 " + Math.round(hrvBase) + " ms）相当或更高，恢复良好。";
            }
        } else {
            r.hrvScore = 20;
            r.hrvText = "7 晚 HRV 基线不足，本项按无异常计入（20/20）。";
            notes.add("HRV 基线数据不足，夜间HRV项按满分计入。");
        }

        // 2) 静息心率 vs 基线 12：高3扣4，高5扣8
        if (restHr > 0 && rhr > 0) {
            int d = rhr - restHr;
            if (d >= 5) { r.rhrScore = 4;  r.rhrText = "静息心率 " + rhr + " bpm，高出基线 " + d + " bpm（≥5），扣 8 分。"; }
            else if (d >= 3) { r.rhrScore = 8; r.rhrText = "静息心率 " + rhr + " bpm，高出基线 " + d + " bpm（≥3），扣 4 分。"; }
            else { r.rhrScore = 12; r.rhrText = "静息心率 " + rhr + " bpm，与基线（" + restHr + " bpm）接近，正常。"; }
        } else {
            r.rhrScore = 12;
            r.rhrText = "缺少静息心率基线，本项按无异常计入（12/12）。";
            notes.add("静息心率基线不足，该项按满分计入。");
        }

        // 3) 呼吸率 / 夜间血氧 5：呼吸率+1.5扣3；SpO2 偶发偏低扣2
        int spo2 = 5;
        StringBuilder sb = new StringBuilder();
        if (nightSpo2Min > 0 && nightSpo2Min < 93) {
            spo2 -= 2;
            sb.append("夜间血氧最低 ").append(nightSpo2Min).append("% 偶发偏低，扣 2 分；");
        } else if (nightSpo2Min > 0) {
            sb.append("夜间血氧最低 ").append(nightSpo2Min).append("%，正常；");
        } else {
            notes.add("无夜间血氧分时数据，血氧项按无异常计入。");
        }
        notes.add("手表未提供夜间呼吸率，呼吸率部分按无异常计入。");
        r.respSpo2Score = spo2;
        r.spo2Text = sb.length() > 0 ? sb.toString() : "夜间血氧/呼吸率数据不足，本项按无异常计入（5/5）。";

        // 4) 手腕温度偏差 3：偏高0.3℃以上扣3（手表无温度数据）
        r.tempScore = 3;
        r.tempText = "手表未提供手腕温度，本项按无异常计入（3/3）。";
        notes.add("手表未提供手腕温度，温度偏差项按满分计入。");

        r.recoveryScore = r.hrvScore + r.rhrScore + r.respSpo2Score + r.tempScore; // ≤40

        // ---------- 第 1 步 · 睡眠（35）----------
        if (last != null) {
            int total = last.totalMinutes();
            float hours = total / 60f;
            // 总时长 15：<7h扣5，<6h扣10
            if (hours < 6f) { r.sleepDurationScore = 5;  r.durText = "睡眠 " + fmt1(hours) + " h（<6h），扣 10 分。"; }
            else if (hours < 7f) { r.sleepDurationScore = 10; r.durText = "睡眠 " + fmt1(hours) + " h（<7h），扣 5 分。"; }
            else { r.sleepDurationScore = 15; r.durText = "睡眠 " + fmt1(hours) + " h，时长充足。"; }
            // 深睡+REM 占比 10：任一<15% 扣5
            int deepPct = pct(last.deepMinutes, total), remPct = pct(last.remMinutes, total);
            if (deepPct < 15 || remPct < 15) {
                r.sleepStructureScore = 5;
                r.structText = "深睡 " + deepPct + "% / REM " + remPct + "%，有一项低于 15%，扣 5 分。";
            } else {
                r.sleepStructureScore = 10;
                r.structText = "深睡 " + deepPct + "% / REM " + remPct + "%，结构良好。";
            }
            // 睡眠分数/规律性 10：分数<70扣5，入睡晚1h以上扣3
            int sc = 0;
            try { sc = SleepScorer.score(last).score; } catch (Exception ignored) { }
            boolean lateBed = bedtimeLate(recentSleeps(days, 7), last);
            int reg = 10;
            StringBuilder rg = new StringBuilder();
            if (sc > 0 && sc < 70) { reg -= 5; rg.append("睡眠分数 ").append(sc).append("（<70），扣 5 分；"); }
            else if (sc > 0) rg.append("睡眠分数 ").append(sc).append("，正常；");
            if (lateBed) { reg -= 3; rg.append("入睡比平时晚 1 小时以上，扣 3 分；"); }
            r.sleepRegularScore = Math.max(0, reg);
            r.regText = rg.length() > 0 ? rg.toString() : "睡眠规律数据不足，本项按无异常计入（10/10）。";
        } else {
            r.sleepDurationScore = 15; r.sleepStructureScore = 10; r.sleepRegularScore = 10;
            r.durText = "无昨晚睡眠数据，睡眠项按中性计入。";
            notes.add("缺少昨晚睡眠数据，睡眠项按满分计入。");
        }
        r.sleepScore = r.sleepDurationScore + r.sleepStructureScore + r.sleepRegularScore; // ≤35

        // ---------- 第 1 步 · 负荷（25）----------
        // 1) 急慢性负荷比（7d/28d）12：0.8~1.3满分，>1.5扣8，<0.8扣4
        double acute = avgLoad(days, 7), chronic = avgLoad(days, 28);
        if (chronic > 0 && acute > 0 && hasLoadData(days)) {
            double ratio = acute / chronic;
            if (ratio >= 0.8 && ratio <= 1.3) { r.loadRatioScore = 12; r.loadRatioText = "急慢性负荷比 " + fmt2(ratio) + "，处于 0.8~1.3 理想区间。"; }
            else if (ratio > 1.5) { r.loadRatioScore = 4;  r.loadRatioText = "急慢性负荷比 " + fmt2(ratio) + "（>1.5），短期负荷激增，扣 8 分。"; }
            else if (ratio > 1.3) { r.loadRatioScore = 8;  r.loadRatioText = "急慢性负荷比 " + fmt2(ratio) + "，偏高，扣 4 分。"; }
            else { r.loadRatioScore = 8; r.loadRatioText = "急慢性负荷比 " + fmt2(ratio) + "（<0.8），负荷偏低，扣 4 分。"; }
        } else {
            r.loadRatioScore = 12;
            r.loadRatioText = "训练负荷数据不足，本项按无异常计入（12/12）。";
            notes.add("训练负荷数据不足，负荷比项按满分计入。");
        }
        // 2) 昨日训练强度与时长 8：高强度或>90min扣5
        if (prev != null) {
            boolean high = prev.trainingLoad >= 70 || prev.exerciseMinutes > 90;
            if (high) {
                r.yesterdayLoadScore = 3;
                r.yestLoadText = "前一天" + (prev.exerciseMinutes > 90 ? "训练超过 90 分钟" : "训练负荷偏高(" + prev.trainingLoad + ")") + "，扣 5 分。";
            } else {
                r.yesterdayLoadScore = 8;
                r.yestLoadText = "前一天训练强度与时长适中。";
            }
        } else {
            r.yesterdayLoadScore = 8;
            r.yestLoadText = "无前一天数据，本项按无异常计入（8/8）。";
        }
        // 3) 连续高强度天数 5：连续3天扣5
        int streak = highStrengthStreak(days);
        if (streak >= 3) { r.consecutiveLoadScore = 0; r.consText = "已连续 " + streak + " 天高强度，扣 5 分。"; }
        else { r.consecutiveLoadScore = 5; r.consText = "无连续 3 天高强度负荷。"; }
        r.loadScore = r.loadRatioScore + r.yesterdayLoadScore + r.consecutiveLoadScore; // ≤25

        // ---------- 主观修正 ±10（无录入）----------
        r.subjectiveMod = 0;
        notes.add("主观晨起感受/肌肉酸痛未录入，主观修正按 0 计（未来可扩展 ±10 输入）。");

        // ---------- 合成 ----------
        r.score = clamp(r.recoveryScore + r.sleepScore + r.loadScore + r.subjectiveMod);
        r.readiness10 = Math.max(1, Math.min(10, (int) Math.round(r.score / 10f)));

        // ---------- 第 2 步：分档对应训练 ----------
        if (r.score >= 80) { r.tier = "好";   r.tierAdvice = "可上强度：间歇、对抗、打全场。"; }
        else if (r.score >= 65) { r.tier = "正常"; r.tierAdvice = "常规训练，别冲 PR。"; }
        else if (r.score >= 50) { r.tier = "偏低"; r.tierAdvice = "降 30% 强度，以技术 / 力量训练为主。"; }
        else if (r.score >= 35) { r.tier = "差";   r.tierAdvice = "只做低强度有氧 20~30 分钟。"; }
        else { r.tier = "很差"; r.tierAdvice = "休息、拉伸、热水澡、补觉。"; }
        if (r.redFlag) r.tierAdvice = "红旗信号命中，" + r.tierAdvice + " 今天以休息为第一优先。";

        // ---------- 第 3 步：当天可回补 ----------
        r.reboundText = "准备度低不代表只能“废一天”：补 1~2 小时睡眠、补足碳水与水分、避免晚间高强度训练，第二天分数常能回升 15~20 分。准备度是全天动态更新的，不是早上定死——状态好转时不必拘泥于清晨的分数。";

        // ---------- 评分明细 ----------
        StringBuilder d = new StringBuilder();
        d.append("【恢复信号 ").append(r.recoveryScore).append("/40】\n");
        d.append("· 夜间HRV ").append(r.hrvScore).append("/20：").append(r.hrvText).append("\n");
        d.append("· 静息心率 ").append(r.rhrScore).append("/12：").append(r.rhrText).append("\n");
        d.append("· 呼吸率/夜间血氧 ").append(r.respSpo2Score).append("/5：").append(r.spo2Text).append("\n");
        d.append("· 手腕温度 ").append(r.tempScore).append("/3：").append(r.tempText).append("\n\n");
        d.append("【睡眠 ").append(r.sleepScore).append("/35】\n");
        d.append("· 总时长 ").append(r.sleepDurationScore).append("/15：").append(r.durText).append("\n");
        d.append("· 深睡+REM ").append(r.sleepStructureScore).append("/10：").append(r.structText).append("\n");
        d.append("· 分数/规律性 ").append(r.sleepRegularScore).append("/10：").append(r.regText).append("\n\n");
        d.append("【负荷 ").append(r.loadScore).append("/25】\n");
        d.append("· 急慢性负荷比 ").append(r.loadRatioScore).append("/12：").append(r.loadRatioText).append("\n");
        d.append("· 昨日强度时长 ").append(r.yesterdayLoadScore).append("/8：").append(r.yestLoadText).append("\n");
        d.append("· 连续高强度 ").append(r.consecutiveLoadScore).append("/5：").append(r.consText).append("\n");
        d.append("· 主观修正 ").append(r.subjectiveMod).append("/±10：未录入，按 0 计。\n");
        d.append("\n总分 ").append(r.score).append("/100 → ").append(r.tier)
         .append("（").append(r.readiness10).append("/10）");
        if (!notes.isEmpty()) {
            d.append("\n\n说明：");
            for (int i = 0; i < notes.size(); i++) d.append(notes.get(i)).append(i < notes.size() - 1 ? " " : "");
        }
        r.detailText = d.toString();

        // ---------- 兼容旧展示字段 ----------
        try { copySleep(r, SleepQuality.eval(days)); } catch (Exception ignored) { }
        return r;
    }

    // ================= 工具 =================

    /** 找 latest 之前紧邻的一天（按日期倒退匹配；找不到取 latest 前最后一条）。 */
    private static DayHealth previousDay(List<DayHealth> days) {
        int n = days.size();
        if (n < 2) return null;
        DayHealth lt = days.get(n - 1);
        if (lt.date != null && lt.date.length() == 10) {
            try {
                SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
                Calendar cal = Calendar.getInstance();
                cal.setTime(f.parse(lt.date));
                cal.add(Calendar.DAY_OF_MONTH, -1);
                String want = f.format(cal.getTime());
                for (int i = n - 2; i >= 0; i--) if (want.equals(days.get(i).date)) return days.get(i);
            } catch (Exception ignored) { }
        }
        for (int i = n - 2; i >= 0; i--) if (days.get(i) != null) return days.get(i);
        return null;
    }

    /** 夜间血氧最低值：取 22/23/0~6 时分时血氧中的有效最小值；无分时数据返回 0。 */
    private static int nightSpo2Min(DayHealth d) {
        if (d == null || d.spo2ByHour == null || d.spo2ByHour.length < 24) return 0;
        int[] hours = {22, 23, 0, 1, 2, 3, 4, 5, 6};
        int min = 0;
        for (int h : hours) {
            int v = d.spo2ByHour[h];
            if (v >= 80 && v <= 100) { if (min == 0 || v < min) min = v; }
        }
        return min;
    }

    /** 近 k 个有 HRV/床位数据的睡眠记录（新的在前）。 */
    private static List<SleepRecord> recentSleeps(List<DayHealth> days, int k) {
        List<SleepRecord> out = new ArrayList<SleepRecord>();
        for (int i = days.size() - 1; i >= 0 && out.size() < k; i--) {
            SleepRecord s = days.get(i).sleep;
            if (s != null && s.hasData()) out.add(s);
        }
        return out;
    }

    /** 昨晚入睡时间是否比个人近期习惯晚 1 小时以上。 */
    private static boolean bedtimeLate(List<SleepRecord> recent, SleepRecord last) {
        if (last == null || last.bedTimeMs <= 0 || recent.size() < 3) return false;
        List<Double> mins = new ArrayList<Double>();
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.CHINA);
        double lastMin = -1;
        for (SleepRecord s : recent) {
            if (s.bedTimeMs <= 0) continue;
            double m = minuteOfDay(s.bedTimeMs, hm);
            if (m < 12 * 60) m += 24 * 60; // 凌晨入睡映射到前夜
            if (s == last) lastMin = m;
            else mins.add(m);
        }
        if (lastMin < 0 || mins.size() < 2) return false;
        double base = median(mins);
        return (lastMin - base) > 60;
    }

    /** 近 k 天训练负荷均值（只计有数据的天）。 */
    private static double avgLoad(List<DayHealth> days, int k) {
        int sum = 0, cnt = 0;
        for (int i = days.size() - 1, c = 0; i >= 0 && c < k; i--, c++) {
            int v = days.get(i).trainingLoad;
            if (v > 0) { sum += v; cnt++; }
        }
        return cnt > 0 ? sum / (double) cnt : 0;
    }

    private static boolean hasLoadData(List<DayHealth> days) {
        for (DayHealth d : days) if (d.trainingLoad > 0) return true;
        return false;
    }

    /** 从「昨天」往前数连续高强度天数（负荷≥60 或 锻炼≥45min）。 */
    private static int highStrengthStreak(List<DayHealth> days) {
        int streak = 0;
        for (int i = days.size() - 2; i >= 0; i--) {
            DayHealth d = days.get(i);
            boolean high = d.trainingLoad >= 60 || d.exerciseMinutes >= 45;
            if (high) streak++; else break;
        }
        return streak;
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

    private static int pct(int part, int total) { return total > 0 ? Math.round(part * 100f / total) : 0; }
    private static int clamp(int v) { return Math.max(0, Math.min(100, v)); }
    private static String fmt1(float v) { return String.format(Locale.CHINA, "%.1f", v); }
    private static String fmt2(double v) { return String.format(Locale.CHINA, "%.2f", v); }

    private static void copySleep(Result r, SleepQuality.Result s) {
        if (s == null) return;
        r.sleepPart = s.score;
        r.durationPart = s.durationScore;
        r.deepPart = s.deepScore;
        r.remPart = s.remScore;
        r.efficiencyPart = s.efficiencyScore;
        r.regularityPart = s.regularityScore;
        r.hrvAdj = s.hrvAdj;
        r.sleepDebtMin = s.debtMin;
        r.regularityText = s.regularityText;
        r.debtText = s.debtText;
    }
}
