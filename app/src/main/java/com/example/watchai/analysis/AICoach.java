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
import com.example.watchai.model.SleepRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置 AI 教练：完全离线，基于你的真实数据做趋势/相关性/阈值推理，
 * 生成自然语言健康简报 + 今日训练建议 + 可点击的数据问答。
 * 不联网、不上传任何数据，所有结论都由当前读取到的数据计算得出。
 */
public class AICoach {

    public static class Report {
        public String headline = "";
        public String summary = "";
        public String todayAdvice = "";
        public int readiness;              // 百分制综合分 0-100（按三步方案）
        public int readiness10;            // 对外展示 1-10 分
        // ---- 新评分方案字段 ----
        public boolean redFlag = false;              // 第0步 红旗命中
        public String redFlagText = "";
        public int recoveryScore = 0, sleepScore = 0, loadScore = 0, subjectiveMod = 0; // 三大项 /40 /35 /25
        public int hrvScore = 0, rhrScore = 0, respSpo2Score = 0, tempScore = 0;        // 恢复信号明细
        public int sleepDurationScore = 0, sleepStructureScore = 0, sleepRegularScore = 0; // 睡眠明细
        public int loadRatioScore = 0, yesterdayLoadScore = 0, consecutiveLoadScore = 0;   // 负荷明细
        public String rhrText = "", spo2Text = "", tempText = "";
        public String durText = "", structText = "", regText = "";
        public String loadRatioText = "", yestLoadText = "", consText = "";
        public String tier = "";           // 好/正常/偏低/差/很差
        public String tierAdvice = "";     // 第2步 分档对应训练
        public String reboundText = "";    // 第3步 当天可回补
        public String detailText = "";     // 评分构成明细（多行）
        // ---- 兼容旧展示字段 ----
        public int durationPart, deepPart, remPart, efficiencyPart, regularityPart;
        public int hrvAdj;                 // HRV 恢复修正（-8..+8）
        public int sleepPart;              // 昨晚睡眠质量分（兼容旧字段）
        public int sleepDebtMin;           // 睡眠债（分钟，正=欠）
        public String prevDate = "";       // 评估基准日期 yyyy-MM-dd
        public String regularityText = "";
        public String debtText = "";
        public String hrvText = "";        // HRV 恢复解读（相对个人基线）
        public String sleepAnalysis = "";  // 基于昨晚睡眠的深入解读
        public String sportAnalysis = "";  // 运动健康分析
        public String weekComparison = ""; // 本周 vs 上周对比
        public String trainingAdvice = ""; // 可执行训练建议
        public List<String[]> qa = new ArrayList<String[]>();  // {问题, 回答}
    }

    private HealthDataSet data;
    private final List<DayHealth> days = new ArrayList<DayHealth>();
    private DayHealth latest;              // 最近一天
    private DayHealth prev;                // 前一天（用于准备度的白天数据）
    private SleepRecord sleep;             // 最近一晚
    private int latestScore;
    private SleepQuality.Result sq;        // 睡眠驱动评估
    private ReadinessScore.Result rs;      // 综合准备度评估（睡眠 + 前一天数据）

    public static Report analyze(HealthDataSet d) {
        return new AICoach().build(d);
    }

    private Report build(HealthDataSet d) {
        Report r = new Report();
        this.data = d;
        if (d == null || d.days == null || d.days.isEmpty()) {
            r.headline = "还没有足够数据";
            r.summary = "先在小米运动健康里同步一次手表数据，我就能开始为你做睡眠、恢复与训练分析。";
            r.todayAdvice = "数据同步后，这里会给出今天适合的训练强度。";
            return r;
        }
        days.addAll(d.days);
        latest = days.get(days.size() - 1);
        // 找最近一条有睡眠的记录
        for (int i = days.size() - 1; i >= 0; i--) {
            if (days.get(i).sleep != null && days.get(i).sleep.hasData()) { sleep = days.get(i).sleep; break; }
        }
        latestScore = SleepScorer.score(sleep).score;
        sq = SleepQuality.eval(days);
        // 综合准备度：三步方案（红旗筛查 → 三项打分 → 分档建议）
        rs = ReadinessScore.evaluate(d);
        r.readiness = rs.score;
        r.readiness10 = rs.readiness10;
        prev = findPrev();
        fillParts(r);
        r.sleepAnalysis = sleepAnalysis();
        r.sportAnalysis = sportAnalysis();
        r.weekComparison = weekComparison();
        r.trainingAdvice = trainingAdvice();
        r.headline = headline(r.readiness);
        r.summary = summary();
        r.todayAdvice = todayAdvice();
        buildQa(r);
        return r;
    }

    // ---------- 准备度：三步方案综合分 ----------
    private int readiness() { return rs != null ? rs.score : (sq != null ? sq.score : 0); }

    /** 找前一天（与 ReadinessScore 同口径），用于文案里引用白天数据。 */
    private DayHealth findPrev() {
        int n = days.size();
        if (n < 2) return null;
        DayHealth lt = days.get(n - 1);
        if (lt.date != null && lt.date.length() == 10) {
            try {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA);
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.setTime(f.parse(lt.date));
                cal.add(java.util.Calendar.DAY_OF_MONTH, -1);
                String want = f.format(cal.getTime());
                for (int i = n - 2; i >= 0; i--) if (want.equals(days.get(i).date)) return days.get(i);
            } catch (Exception ignored) { }
        }
        for (int i = n - 2; i >= 0; i--) if (days.get(i) != null) return days.get(i);
        return null;
    }

    private void fillParts(Report r) {
        // 新评分方案全字段（来自 ReadinessScore）
        if (rs != null) {
            r.redFlag = rs.redFlag;
            r.redFlagText = rs.redFlagText;
            r.recoveryScore = rs.recoveryScore;
            r.sleepScore = rs.sleepScore;
            r.loadScore = rs.loadScore;
            r.subjectiveMod = rs.subjectiveMod;
            r.hrvScore = rs.hrvScore; r.rhrScore = rs.rhrScore;
            r.respSpo2Score = rs.respSpo2Score; r.tempScore = rs.tempScore;
            r.sleepDurationScore = rs.sleepDurationScore;
            r.sleepStructureScore = rs.sleepStructureScore;
            r.sleepRegularScore = rs.sleepRegularScore;
            r.loadRatioScore = rs.loadRatioScore;
            r.yesterdayLoadScore = rs.yesterdayLoadScore;
            r.consecutiveLoadScore = rs.consecutiveLoadScore;
            r.rhrText = rs.rhrText; r.spo2Text = rs.spo2Text; r.tempText = rs.tempText;
            r.durText = rs.durText; r.structText = rs.structText; r.regText = rs.regText;
            r.loadRatioText = rs.loadRatioText; r.yestLoadText = rs.yestLoadText; r.consText = rs.consText;
            r.tier = rs.tier; r.tierAdvice = rs.tierAdvice;
            r.reboundText = rs.reboundText; r.detailText = rs.detailText;
            r.prevDate = rs.prevDate;
        }
        // 兼容旧展示字段：优先取 ReadinessScore 透传的睡眠质量分项，否则用 sq
        if (rs != null && rs.sleepPart > 0) {
            r.sleepPart = rs.sleepPart;
            r.durationPart = rs.durationPart; r.deepPart = rs.deepPart; r.remPart = rs.remPart;
            r.efficiencyPart = rs.efficiencyPart; r.regularityPart = rs.regularityPart;
            r.hrvAdj = rs.hrvAdj; r.sleepDebtMin = rs.sleepDebtMin;
            r.regularityText = rs.regularityText; r.debtText = rs.debtText;
        } else if (sq != null) {
            r.sleepPart = sq.score;
            r.durationPart = sq.durationScore; r.deepPart = sq.deepScore; r.remPart = sq.remScore;
            r.efficiencyPart = sq.efficiencyScore; r.regularityPart = sq.regularityScore;
            r.hrvAdj = sq.hrvAdj; r.sleepDebtMin = sq.debtMin;
            r.regularityText = sq.regularityText; r.debtText = sq.debtText;
        }
        if (sq != null && sq.hrvText != null) r.hrvText = sq.hrvText;
    }

    private String readinessLabel(int v) {
        // 严格对齐方案第2步分档：≥80 好 / 65-79 正常 / 50-64 偏低 / 35-49 差 / <35 很差
        if (v >= 80) return "好";
        if (v >= 65) return "正常";
        if (v >= 50) return "偏低";
        if (v >= 35) return "差";
        return "很差";
    }

    private String headline(int rd) {
        if (rs != null && rs.redFlag) return "身体发出警报，今天建议休息";
        String name = readinessLabel(rd);
        if (sleep != null && sleep.hasData()) {
            return "昨晚睡眠 + 前一天状态综合评估：今天" + name;
        }
        return "今天" + name;
    }

    private String summary() {
        StringBuilder sb = new StringBuilder();
        int n = days.size();
        // 睡眠
        float hours = sleep != null ? sleep.sleepHours() : 0;
        int avgMin = 0, cnt = 0;
        for (DayHealth d : days) if (d.sleep != null && d.sleep.hasData()) { avgMin += d.sleep.totalMinutes(); cnt++; }
        if (cnt > 0) avgMin /= cnt;
        if (sleep != null && sleep.hasData()) {
            sb.append("昨晚睡了 ").append(String.format("%.1f", hours)).append(" 小时");
            sb.append("，深睡 ").append(pct(sleep.deepMinutes, sleep.totalMinutes())).append("%");
            sb.append("、REM ").append(pct(sleep.remMinutes, sleep.totalMinutes())).append("%");
            if (sleep.avgHrv > 0) sb.append("，夜间 HRV ").append(sleep.avgHrv).append(" ms");
            sb.append("。近 ").append(cnt).append(" 晚平均 ").append(String.format("%.1f", avgMin / 60f)).append(" 小时");
            if (sleep.totalMinutes() > avgMin + 30) sb.append("，昨晚高于平均");
            else if (sleep.totalMinutes() < avgMin - 30) sb.append("，昨晚低于平均");
            else sb.append("，与平均持平");
            sb.append("。\n\n");
        }
        // 前一天活动与恢复（准备度的白天数据口径）
        DayHealth day = prev;
        int steps = day != null ? day.steps : 0;
        int wMin = weekExerciseMinutes(), wCnt = weekExerciseCount();
        sb.append(day != null ? "前一天（" + day.date + "）" : "近期 ").append(steps).append(" 步");
        if (day != null && day.avgHeartRate > 0) sb.append("，平均心率 ").append(day.avgHeartRate).append(" bpm");
        if (day != null && day.stress > 0) sb.append("，压力 ").append(day.stress);
        sb.append("。近 7 天锻炼 ").append(wCnt).append(" 次、累计 ").append(wMin).append(" 分钟");
        sb.append(wMin >= 150 ? "，已达到世卫组织每周 150 分钟建议。" : "，距离每周 150 分钟建议还差 " + Math.max(0, 150 - wMin) + " 分钟。");
        // 新方案综合结论
        if (rs != null) {
            sb.append("\n\n综合恢复信号 ").append(rs.recoveryScore).append("/40、睡眠 ")
              .append(rs.sleepScore).append("/35、负荷 ").append(rs.loadScore)
              .append("/25，准备度 ").append(rs.score).append("/100（").append(rs.tier).append("）。");
            if (rs.redFlag) sb.append(" ").append(rs.redFlagText);
        } else if (day != null && day.trainingLoad > 0) {
            sb.append("\n\n前一天训练负荷 ").append(day.trainingLoad)
              .append("，结合恢复状态安排今天的强度。");
        }
        return sb.toString().trim();
    }

    private String todayAdvice() {
        if (rs != null && rs.redFlag) return "检测到红旗信号，今天休息：拉伸、热水澡、补觉，暂不安排训练。";
        int rd = r0();
        if (sleep == null || !sleep.hasData()) return "还没有昨晚睡眠数据，建议先同步；今天可按平时节奏活动。";
        // 严格对齐方案第2步：分档对应训练
        if (rd >= 80) return "状态好：可上强度——间歇、对抗、打全场。";
        if (rd >= 65) return "状态正常：常规训练，别冲 PR。";
        if (rd >= 50) return "状态偏低：降 30% 强度，以技术 / 力量训练为主。";
        if (rd >= 35) return "状态差：只做低强度有氧 20~30 分钟。";
        return "状态很差：休息、拉伸、热水澡、补觉。";
    }

    private int r0() { return readiness(); }

    // ---------- 数据问答 ----------
    private void buildQa(Report r) {
        r.qa.add(new String[]{"我最近睡得怎么样？", qaSleep()});
        r.qa.add(new String[]{"今天适合高强度训练吗？", qaTraining()});
        r.qa.add(new String[]{"我的作息规律吗？", qaRegularity()});
        r.qa.add(new String[]{"我有睡眠债吗？", qaDebt()});
        r.qa.add(new String[]{"怎样提升我的深睡？", qaDeep()});
        r.qa.add(new String[]{"我的运动量达标吗？", qaExercise()});
        r.qa.add(new String[]{"最近身体状态在变好吗？", qaWeek()});
        r.qa.add(new String[]{"我的心率和恢复健康吗？", qaHeart()});
        r.qa.add(new String[]{"我最近压力大吗？", qaStress()});
        r.qa.add(new String[]{"最近训练负荷合理吗？", qaLoad()});
        r.qa.add(new String[]{"我的跑步效率高吗？", qaRunningEconomy()});
    }

    /** 基于昨晚睡眠的深入解读（首页/睡眠页用） */
    private String sleepAnalysis() {
        if (sleep == null || !sleep.hasData()) return "暂无昨晚睡眠数据。同步手表后，我会深入分析睡眠结构、恢复质量以及它如何影响你今天的状态。";
        StringBuilder sb = new StringBuilder();
        int deepPct = pct(sleep.deepMinutes, sleep.totalMinutes());
        int remPct = pct(sleep.remMinutes, sleep.totalMinutes());
        sb.append("昨晚你睡了 ").append(String.format("%.1f", sleep.sleepHours())).append(" 小时");
        if (sleep.bedTimeMs > 0 && sleep.wakeTimeMs > 0)
            sb.append("（").append(hhmm(sleep.bedTimeMs)).append(" 入睡 – ").append(hhmm(sleep.wakeTimeMs)).append(" 起床）");
        sb.append("。睡眠结构中，深睡 ").append(deepPct).append("%（").append(sleep.deepMinutes).append(" 分钟）、REM ").append(remPct).append("%（").append(sleep.remMinutes).append(" 分钟）");
        sb.append("、浅睡 ").append(pct(sleep.lightMinutes, sleep.totalMinutes())).append("%、夜间清醒 ").append(sleep.awakeMinutes).append(" 分钟。\n");
        // 深睡解读
        if (deepPct >= 20) sb.append("深睡占比达到理想水平（≥20%），身体修复与生长激素分泌充分。");
        else if (deepPct >= 15) sb.append("深睡接近理想值，仍可再提升。");
        else sb.append("深睡偏少，会影响体力恢复与免疫，建议睡前减少屏幕、酒精并把卧室调凉。");
        sb.append("\n");
        // REM 解读
        if (remPct >= 22) sb.append("REM 充足，有利于记忆巩固与情绪稳定。");
        else sb.append("REM 偏少，可能影响记忆与情绪，固定起床时间有助于稳定睡眠周期。");
        sb.append("\n");
        // 恢复指标
        if (sleep.avgHrv > 0) sb.append("夜间 HRV ").append(sleep.avgHrv).append(" ms").append(sleep.avgHrv >= 50 ? "，处于较好水平，自主神经恢复良好。" : sleep.avgHrv >= 35 ? "，属中等水平。" : "，偏低，提示疲劳或压力，今天宜降低强度。");
        if (sleep.avgHeartRate > 0) sb.append("夜间平均心率 ").append(sleep.avgHeartRate).append(" bpm").append(sleep.avgHeartRate <= 60 ? "，安静且恢复良好。" : "，略偏高，留意睡前状态。");
        // 连续性
        sb.append("\n");
        if (sleep.awakeMinutes <= 15) sb.append("夜间连续性很好，几乎一觉到天亮。");
        else sb.append("夜间清醒 ").append(sleep.awakeMinutes).append(" 分钟，注意卧室温度、光线与起夜。");
        return sb.toString();
    }

    /** 运动健康分析（强化版） */
    private String sportAnalysis() {
        int cnt = weekExerciseCount(), min = weekExerciseMinutes();
        double km = weekDistanceKm();
        StringBuilder sb = new StringBuilder();
        if (cnt == 0) {
            sb.append("近 7 天还没有锻炼记录。规律的有氧运动能提升 HRV、深睡与心肺功能，建议每周至少 150 分钟中等强度运动。");
            return sb.toString();
        }
        sb.append("近 7 天你完成了 ").append(cnt).append(" 次锻炼，累计 ").append(min).append(" 分钟");
        if (km > 0) sb.append("、").append(String.format("%.1f", km)).append(" 公里");
        sb.append("。");
        sb.append(min >= 150 ? "已达到世卫组织每周 150 分钟建议，训练量充足。" : "距离每周 150 分钟建议还差 " + Math.max(0, 150 - min) + " 分钟，可逐步增加。");
        // 训练负荷与恢复
        if (latest != null && latest.trainingLoad > 0) {
            int load = latest.trainingLoad;
            sb.append("\n当前训练负荷 ").append(load);
            int hrv = sleep != null ? sleep.avgHrv : 0;
            if (load > 70 && hrv > 0 && hrv < 40) sb.append("，而昨夜 HRV 偏低，负荷与恢复不匹配，今天建议轻松日或休息。");
            else if (load > 80) sb.append("，处于较高区间，注意安排恢复日、保证睡眠与营养。");
            else sb.append("，与恢复状态基本匹配，可保持当前节奏。");
        }
        // 跑步动态
        com.example.watchai.model.Exercise run = null;
        if (data != null && data.exercises != null)
            for (com.example.watchai.model.Exercise e : data.exercises)
                if (e.hasRunningDynamics()) { run = e; break; }
        if (run != null) {
            sb.append("\n最近一场跑步动态：垂直振幅 ").append(String.format("%.1f", run.avgVerticalOscCm)).append(" cm");
            if (run.avgVerticalRatio > 0) sb.append("、垂直步幅比 ").append(String.format("%.1f", run.avgVerticalRatio)).append("%");
            if (run.avgGroundContactMs > 0) sb.append("、平均触地 ").append(String.format("%.0f", run.avgGroundContactMs)).append(" ms");
            sb.append("。");
            if (run.avgVerticalRatio > 0 && run.avgVerticalRatio < 8) sb.append(" 垂直步幅比优秀（<8%），跑步经济性较好。");
            else if (run.avgVerticalRatio >= 10) sb.append(" 垂直步幅比偏高，可练习步频与身体前倾以改善经济性。");
        }
        return sb.toString();
    }

    /** 本周 vs 上周：睡眠时长/评分/运动量的环比，主流健康 App 的核心洞察 */
    private String weekComparison() {
        if (days.size() < 4) return "";
        int n = days.size();
        int half = n / 2;
        // 后半段=最近几天，前半段=更早几天
        float recentH = 0, olderH = 0; int rc = 0, oc = 0;
        int recentScore = 0, olderScore = 0, rsc = 0, osc = 0;
        for (int i = 0; i < n; i++) {
            DayHealth d = days.get(i);
            boolean recent = i >= n - half;
            if (d.sleep != null && d.sleep.hasData()) {
                float h = d.sleep.sleepHours();
                int sc = SleepScorer.score(d.sleep).score;
                if (recent) { recentH += h; recentScore += sc; rc++; }
                else { olderH += h; olderScore += sc; oc++; }
            }
        }
        if (rc == 0 || oc == 0) return "";
        recentH /= rc; olderH /= oc;
        float dH = recentH - olderH;
        StringBuilder sb = new StringBuilder();
        sb.append("最近 ").append(rc).append(" 天较前 ").append(oc).append(" 天：日均睡眠 ").append(String.format("%.1f", recentH)).append(" 小时（");
        if (dH > 0.2f) sb.append("增加 ").append(String.format("%.1f", dH)).append(" 小时，在变好 ↗");
        else if (dH < -0.2f) sb.append("减少 ").append(String.format("%.1f", -dH)).append(" 小时，需留意 ↘");
        else sb.append("基本持平");
        sb.append("）。");
        // 运动环比
        int wCnt = weekExerciseCount(), wMin = weekExerciseMinutes();
        if (wCnt > 0) sb.append("近 7 天 ").append(wCnt).append(" 次锻炼、").append(wMin).append(" 分钟。");
        if (sq != null) sb.append(" ").append(sq.debtText);
        return sb.toString().trim();
    }

    /** 三步方案的可执行训练建议：红旗 → 分档结论 → 回补说明 */
    private String trainingAdvice() {
        StringBuilder sb = new StringBuilder();
        if (rs != null && rs.redFlag) {
            sb.append("红旗筛查命中，今天休息。").append(rs.redFlagText);
        } else {
            sb.append(todayAdvice());
        }
        // 睡眠债提醒
        if (sq != null && sq.debtMin > 180) sb.append(" 你还有较明显的睡眠债，今晚尽量提前 30–60 分钟入睡。");
        // 第3步：当天可回补（低分时给出回升空间，避免"废一天"心态）
        int rd = r0();
        if (rs != null && rd < 65) sb.append(" ").append(rs.reboundText);
        return sb.toString().trim();
    }

    private String qaLoad() {
        if (latest == null || latest.trainingLoad <= 0) return "暂无训练负荷数据，同步后我会评估你的负荷与恢复是否匹配。";
        int load = latest.trainingLoad;
        int hrv = sleep != null ? sleep.avgHrv : 0;
        StringBuilder sb = new StringBuilder();
        sb.append("今日训练负荷 ").append(load).append("（近 7 天累计 ").append(weekExerciseMinutes()).append(" 分钟）。");
        if (load < 40) sb.append("负荷偏低，身体有充足余量，可适当增加训练。");
        else if (load <= 70) sb.append("负荷适中，保持当前节奏即可。");
        else sb.append("负荷偏高");
        if (hrv > 0 && hrv < 40) sb.append("，同时昨夜 HRV 偏低，恢复不足，建议今天安排轻松活动或休息。");
        else sb.append("，恢复状态尚可，注意别连续叠加高强度课。");
        return sb.toString();
    }

    private String qaRunningEconomy() {
        if (data == null || data.exercises == null) return "暂无运动数据。";
        for (com.example.watchai.model.Exercise e : data.exercises) {
            if (!e.hasRunningDynamics()) continue;
            StringBuilder sb = new StringBuilder();
            sb.append("以你最近一场「").append(e.name).append("」为例：");
            sb.append("垂直振幅 ").append(String.format("%.1f", e.avgVerticalOscCm)).append(" cm");
            if (e.avgGroundContactMs > 0) sb.append("、平均触地时间 ").append(String.format("%.0f", e.avgGroundContactMs)).append(" ms");
            if (e.avgCadence > 0) sb.append("、平均步频 ").append(e.avgCadence).append(" 步/分");
            sb.append("。");
            if (e.avgVerticalRatio > 0) {
                sb.append("垂直步幅比 ").append(String.format("%.1f", e.avgVerticalRatio)).append("%：");
                sb.append(e.avgVerticalRatio < 8 ? "非常优秀，垂直晃动少、能量利用效率高。"
                        : e.avgVerticalRatio < 10 ? "处于良好区间，仍有提升空间。"
                        : "偏高，意味着更多能量用在上下起伏，建议把步频提高 5–10 步/分、落脚更靠近身体重心。");
            }
            if (e.avgCadence > 0 && e.avgCadence < 170) sb.append(" 你的步频略低，逐步提升到 170–180 步/分通常能降低触地冲击。");
            return sb.toString();
        }
        return "还没有带跑步动态的运动记录。用手表记录一次户外跑步并同步后，我就能分析你的垂直振幅、触地时间与跑步经济性。";
    }

    private String hhmm(long ms) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA);
        return f.format(new java.util.Date(ms));
    }

    private String qaRegularity() {
        if (sleep == null || !sleep.hasData()) return "暂时没有睡眠数据，同步后我才能评估作息是否规律。";
        StringBuilder sb = new StringBuilder();
        sb.append("作息规律是睡眠质量最重要的基础之一。");
        if (sq != null) sb.append(sq.regularityText);
        if (sleep.bedTimeMs > 0 && sleep.wakeTimeMs > 0)
            sb.append(" 昨晚 ").append(hhmm(sleep.bedTimeMs)).append(" 入睡、").append(hhmm(sleep.wakeTimeMs)).append(" 起床。");
        sb.append(" 建议每天（含周末）把起床时间固定在同一时段，入睡时间自然会更稳定。");
        return sb.toString();
    }

    private String qaDebt() {
        if (sq == null) return "同步睡眠数据后，我会帮你统计睡眠债。";
        StringBuilder sb = new StringBuilder();
        sb.append(sq.debtText);
        float h = Math.abs(sq.debtMin) / 60f;
        if (sq.debtMin > 120) sb.append(" 睡眠债会逐步影响白天的警觉度、情绪和运动恢复，建议连续几晚把睡眠补到 7 小时以上，而不是靠周末一次性补觉。");
        else if (sq.debtMin < -60) sb.append(" 睡眠储备充足，可以更有把握地安排稍高强度的训练。");
        return sb.toString();
    }

    private String qaWeek() {
        String wc = weekComparison();
        if (wc == null || wc.isEmpty()) return "再积累几天数据，我就能告诉你身体状态是在变好还是变差。";
        return wc;
    }

    private String qaSleep() {
        if (sleep == null || !sleep.hasData()) return "暂时没有睡眠记录。先在小米运动健康同步一次，我再帮你分析。";
        int n = 0, sum = 0, scoreSum = 0;
        for (DayHealth d : days) if (d.sleep != null && d.sleep.hasData()) { n++; sum += d.sleep.totalMinutes(); scoreSum += SleepScorer.score(d.sleep).score; }
        StringBuilder sb = new StringBuilder();
        sb.append("近 ").append(n).append(" 晚平均 ").append(String.format("%.1f", sum / 60f / n)).append(" 小时，平均评分 ").append(scoreSum / n).append(" 分。");
        sb.append("昨晚 ").append(String.format("%.1f", sleep.sleepHours())).append(" 小时，深睡占比 ")
          .append(pct(sleep.deepMinutes, sleep.totalMinutes())).append("%（理想 ≥20%），REM 占比 ")
          .append(pct(sleep.remMinutes, sleep.totalMinutes())).append("%（理想 ≥22%），夜间清醒 ")
          .append(sleep.awakeMinutes).append(" 分钟。");
        if (sleep.sleepHours() < 7) sb.append("主要短板是时长偏短，优先把入睡时间提前 30 分钟。");
        else if (pct(sleep.deepMinutes, sleep.totalMinutes()) < 15) sb.append("主要短板是深睡偏少，睡前减少屏幕与酒精、保持卧室凉爽。");
        else sb.append("结构较均衡，继续保持规律作息即可。");
        return sb.toString();
    }

    private String qaTraining() {
        int rd = r0();
        StringBuilder sb = new StringBuilder();
        int rd10 = Math.max(1, Math.min(10, Math.round(rd / 10f)));
        sb.append("你今天的准备度是 ").append(rd10).append("/10（").append(readinessLabel(rd)).append("）。");
        sb.append(todayAdvice());
        if (sq != null && sq.hrvText != null && sq.hrvText.length() > 0) sb.append(" ").append(sq.hrvText);
        if (latest != null && latest.trainingLoad > 70) sb.append(" 另外当前训练负荷偏高，注意不要再叠加大量高强度课。");
        return sb.toString();
    }

    private String qaDeep() {
        if (sleep == null || !sleep.hasData()) return "还没有睡眠数据，同步后我才能给针对性建议。";
        int deepPct = pct(sleep.deepMinutes, sleep.totalMinutes());
        StringBuilder sb = new StringBuilder();
        sb.append("你昨晚深睡占比 ").append(deepPct).append("%（").append(sleep.deepMinutes).append(" 分钟）。");
        if (deepPct >= 20) sb.append("已在理想区间，保持即可。");
        else {
            sb.append("偏低，可以从这几点入手：");
            sb.append("① 固定入睡与起床时间，避免熬夜；");
            sb.append("② 睡前 1 小时远离手机屏幕、避免酒精与咖啡因；");
            sb.append("③ 卧室保持凉爽、黑暗、安静；");
            if (weekExerciseMinutes() < 90) sb.append("④ 白天增加适度运动（你本周运动偏少），规律锻炼能显著提升深睡。");
            else sb.append("④ 睡前 3 小时避免剧烈运动，把训练安排在下午早些时候。");
        }
        return sb.toString();
    }

    private String qaExercise() {
        int cnt = weekExerciseCount(), min = weekExerciseMinutes();
        double km = weekDistanceKm();
        int steps = latest != null ? latest.steps : 0;
        StringBuilder sb = new StringBuilder();
        sb.append("近 7 天你记录了 ").append(cnt).append(" 次锻炼，累计 ").append(min).append(" 分钟");
        if (km > 0) sb.append("、").append(String.format("%.1f", km)).append(" 公里");
        sb.append("。世卫组织建议每周至少 150 分钟中等强度运动，");
        sb.append(min >= 150 ? "你已达标，做得很好。" : "你目前完成约 " + (min * 100 / 150) + "%，还差 " + Math.max(0, 150 - min) + " 分钟。");
        if (steps > 0) sb.append("今日 ").append(steps).append(" 步，").append(steps >= 8000 ? "日常活动量充足。" : "可再增加一些步行。");
        return sb.toString();
    }

    private String qaHeart() {
        StringBuilder sb = new StringBuilder();
        int hr = latest != null ? latest.avgHeartRate : 0;
        int hrv = sleep != null ? sleep.avgHrv : 0;
        int nightHr = sleep != null ? sleep.avgHeartRate : 0;
        if (hr > 0) sb.append("今日平均心率 ").append(hr).append(" bpm，范围 ").append(latest.minHeartRate).append("–").append(latest.maxHeartRate).append(" bpm。");
        if (nightHr > 0) sb.append("夜间平均心率 ").append(nightHr).append(" bpm（睡眠时通常更低）。");
        if (hrv > 0) {
            sb.append("夜间 HRV ").append(hrv).append(" ms：");
            sb.append(hrv >= 50 ? "处于较好水平，说明恢复充分、自主神经平衡良好。"
                    : hrv >= 35 ? "属于中等水平，注意睡眠与压力管理。"
                    : "偏低，可能提示疲劳、压力或睡眠不足，建议降低强度并保证休息。");
        }
        if (hr == 0 && hrv == 0) sb.append("暂时没有心率/HRV 数据，同步后我再分析。");
        return sb.toString();
    }

    private String qaStress() {
        if (latest == null || latest.stress <= 0) return "暂时没有压力数据，同步后我再分析。";
        int st = latest.stress;
        StringBuilder sb = new StringBuilder();
        sb.append("最近平均压力指数 ").append(st).append("/100，");
        sb.append(st < 35 ? "整体偏放松。" : st < 55 ? "处于中等水平。" : "偏高，需要主动放松。");
        if (sleep != null && sleep.hasData() && st >= 55 && pct(sleep.deepMinutes, sleep.totalMinutes()) < 15)
            sb.append(" 你的深睡占比也偏低，压力与深睡减少常常相关，建议睡前做 5–10 分钟深呼吸或冥想。");
        sb.append(" 日常可安排短暂走动、呼吸练习和规律睡眠来降低压力。");
        return sb.toString();
    }

    // ---------- 工具 ----------
    private int pct(int part, int total) { return total > 0 ? Math.round(part * 100f / total) : 0; }

    private long weekStartSec() {
        long now = System.currentTimeMillis() / 1000L;
        return now - 6L * 86400L;
    }

    private int weekExerciseCount() {
        if (data == null || data.exercises == null) return 0;
        int c = 0; long ws = weekStartSec();
        for (Exercise e : data.exercises) if (e.startSec >= ws) c++;
        return c;
    }

    private int weekExerciseMinutes() {
        if (data == null || data.exercises == null) return 0;
        int s = 0; long ws = weekStartSec();
        for (Exercise e : data.exercises) if (e.startSec >= ws) s += e.durationSec;
        return s / 60;
    }

    private double weekDistanceKm() {
        if (data == null || data.exercises == null) return 0;
        double m = 0; long ws = weekStartSec();
        for (Exercise e : data.exercises) if (e.startSec >= ws) m += e.distanceM;
        return m / 1000.0;
    }
}
