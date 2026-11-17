/*
 * ============================================================
 * 腕上智析 WatchAI  —  原创作品，请勿去除本声明
 * 原作者：酷安 @想吃薯片zz
 * 开源地址：https://github.com/ZXZ22Y/watchai
 * 本文件为原作者编写，二次修改/分发请保留出处与署名。
 * 溯源标识：WATCHAI-ORIGIN-ByCoolapk-XiangChiShuaiPianZZ-2026
 * ============================================================
 */
package com.example.watchai.data;

import com.example.watchai.analysis.AIAnalyzer;
import com.example.watchai.analysis.SleepScorer;
import com.example.watchai.model.DayHealth;
import com.example.watchai.model.HealthDataSet;
import com.example.watchai.model.SleepRecord;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/** 把读取结果转成 app.html 期望的 JSON 结构 */
public class DataJsonBuilder {

    /** 身体资料，用于 VO2max 个体化估算 */
    public static class ProfileInput {
        public int age = 0;
        public int restHr = 0;
        public float weightKg = 0;
        public String gender = "male";
    }

    public static JSONObject dataJson(HealthDataSet data) {
        return dataJson(data, null);
    }

    public static JSONObject dataJson(HealthDataSet data, ProfileInput profile) {
        JSONObject o = new JSONObject();
        try {
            o.put("source", data.source);
            o.put("message", data.message == null ? "" : data.message);
            JSONArray days = new JSONArray();
            JSONArray scores = new JSONArray();
            if (data.days != null) {
                for (DayHealth d : data.days) {
                    JSONObject jd = new JSONObject();
                    jd.put("date", d.date == null ? "" : d.date);
                    jd.put("hasData", d.sleep != null && d.sleep.hasData() || d.steps > 0);
                    jd.put("steps", d.steps);
                    jd.put("exerciseMinutes", d.exerciseMinutes);
                    jd.put("standHours", d.standHours);
                    jd.put("avgHeartRate", d.avgHeartRate);
                    jd.put("minHeartRate", d.minHeartRate);
                    jd.put("maxHeartRate", d.maxHeartRate);
                    jd.put("spo2", d.spo2);
                    jd.put("stress", d.stress);
                    jd.put("trainingLoad", d.trainingLoad);
                    // 当天分时序列（24 小时），二级详情页用
                    JSONObject js2 = new JSONObject();
                    js2.put("hr", toArr(d.hrByHour));
                    js2.put("spo2", toArr(d.spo2ByHour));
                    js2.put("stress", toArr(d.stressByHour));
                    js2.put("steps", toArr(d.stepsByHour));
                    jd.put("series", js2);
                    if (d.sleep != null) {
                        SleepRecord s = d.sleep;
                        JSONObject js = new JSONObject();
                        js.put("deepMinutes", s.deepMinutes);
                        js.put("lightMinutes", s.lightMinutes);
                        js.put("remMinutes", s.remMinutes);
                        js.put("awakeMinutes", s.awakeMinutes);
                        js.put("totalMinutes", s.totalMinutes());
                        js.put("avgHeartRate", s.avgHeartRate);
                        js.put("avgHrv", s.avgHrv);
                        js.put("bedTimeMs", s.bedTimeMs);
                        js.put("wakeTimeMs", s.wakeTimeMs);
                        jd.put("sleep", js);
                        // 评分
                        SleepScorer.Result r = SleepScorer.score(s);
                        JSONObject sc = new JSONObject();
                        sc.put("score", r.score);
                        sc.put("level", r.level);
                        sc.put("durationScore", r.durationScore);
                        sc.put("deepScore", r.deepScore);
                        sc.put("remScore", r.remScore);
                        sc.put("continuityScore", r.continuityScore);
                        sc.put("recoveryScore", r.recoveryScore);
                        sc.put("advice", new JSONArray(java.util.Arrays.asList(r.advice)));
                        scores.put(sc);
                    } else {
                        SleepScorer.Result r = SleepScorer.score(null);
                        JSONObject sc = new JSONObject();
                        sc.put("score", r.score);
                        sc.put("level", r.level);
                        sc.put("durationScore", 0);
                        sc.put("deepScore", 0);
                        sc.put("remScore", 0);
                        sc.put("continuityScore", 0);
                        sc.put("recoveryScore", 0);
                        sc.put("advice", new JSONArray(java.util.Arrays.asList(r.advice)));
                        scores.put(sc);
                    }
                    days.put(jd);
                }
            }
            o.put("days", days);
            o.put("scores", scores);
            // AI 洞察
            JSONArray ins = new JSONArray();
            try {
                for (AIAnalyzer.Insight x : AIAnalyzer.analyze(data)) {
                    JSONObject ji = new JSONObject();
                    ji.put("icon", x.icon);
                    ji.put("title", x.title);
                    ji.put("detail", x.detail);
                    ji.put("color", x.color);
                    ins.put(ji);
                }
            } catch (Exception ignored) { }
            o.put("insights", ins);

            // 从手表真实数据自动提取的心率基线（静息心率 / 最大心率）
            com.example.watchai.analysis.HeartRateBaseline.Result baseline =
                    com.example.watchai.analysis.HeartRateBaseline.compute(data);
            JSONObject hb = new JSONObject();
            hb.put("restHr", baseline.restHr);
            hb.put("restSource", baseline.restSource);
            hb.put("restSamples", baseline.restSamples);
            hb.put("maxHr", baseline.maxHr);
            hb.put("maxSource", baseline.maxSource);
            hb.put("maxSamples", baseline.maxSamples);
            o.put("hrBaseline", hb);

            // 内置 AI 健康异常提示（基于个人基线的可解释偏离检测，仅健康提醒非诊断）
            try {
                com.example.watchai.analysis.HealthAnomalyDetector.Result al =
                        com.example.watchai.analysis.HealthAnomalyDetector.evaluate(data);
                JSONObject ja = new JSONObject();
                ja.put("level", al.level);
                ja.put("label", al.label);
                ja.put("riskScore", al.riskScore);
                ja.put("confidence", al.confidence);
                ja.put("hasAlert", al.hasAlert);
                ja.put("confounded", al.confounded);
                ja.put("confounderText", al.confounderText);
                ja.put("headline", al.headline);
                ja.put("advice", al.advice);
                ja.put("disclaimer", com.example.watchai.analysis.HealthAnomalyDetector.DISCLAIMER);
                ja.put("baselineNights", al.baselineNights);
                JSONArray jr = new JSONArray();
                for (com.example.watchai.analysis.HealthAnomalyDetector.Reason rs : al.reasons) {
                    JSONObject j = new JSONObject();
                    j.put("metric", rs.metric); j.put("detail", rs.detail); j.put("contribution", rs.contribution);
                    jr.put(j);
                }
                ja.put("reasons", jr);
                o.put("healthAlert", ja);
            } catch (Exception ignored) { }

            // 锻炼记录（含专业跑步动态 + 逐点轨迹/曲线）
            JSONArray exs = new JSONArray();
            if (data.exercises != null) {
                for (com.example.watchai.model.Exercise e : data.exercises) {
                    JSONObject je = new JSONObject();
                    je.put("name", e.name == null ? "运动" : e.name);
                    je.put("badge", e.badge == null ? "练" : e.badge);
                    je.put("colorIdx", e.colorIdx);
                    je.put("startSec", e.startSec);
                    je.put("durationSec", e.durationSec);
                    je.put("distanceM", e.distanceM);
                    je.put("calories", e.calories);
                    je.put("steps", e.steps);
                    je.put("avgHr", e.avgHr);
                    je.put("maxHr", e.maxHr);
                    je.put("avgCadence", e.avgCadence);
                    je.put("maxCadence", e.maxCadence);
                    je.put("pace", e.paceText());
                    je.put("ascentM", e.ascentM);
                    je.put("descentM", e.descentM);
                    je.put("avgAltM", e.avgAltM);
                    je.put("maxAltM", e.maxAltM);
                    je.put("avgStrideCm", e.avgStrideCm);
                    je.put("avgSpeedKmh", e.avgSpeedKmh);
                    je.put("maxSpeedKmh", e.maxSpeedKmh);
                    // 跑步动态
                    je.put("avgGroundContactMs", e.avgGroundContactMs);
                    je.put("minGroundContactMs", e.minGroundContactMs);
                    je.put("maxGroundContactMs", e.maxGroundContactMs);
                    je.put("avgVerticalOscCm", e.avgVerticalOscCm);
                    je.put("minVerticalOscCm", e.minVerticalOscCm);
                    je.put("maxVerticalOscCm", e.maxVerticalOscCm);
                    je.put("avgVerticalRatio", e.avgVerticalRatio);
                    je.put("maxVerticalRatio", e.maxVerticalRatio);
                    je.put("totalCalories", e.totalCalories);
                    // 逐点轨迹与曲线
                    je.put("hasTrack", e.hasTrack);
                    if (e.hasTrack) {
                        JSONArray tr = new JSONArray();
                        for (double[] p : e.track) {
                            JSONArray pt = new JSONArray();
                            pt.put(Math.round(p[0] * 1e6) / 1e6);   // lat
                            pt.put(Math.round(p[1] * 1e6) / 1e6);   // lng
                            pt.put(Math.round(p[2] * 100) / 100.0); // speed
                            pt.put(Math.round(p[3]));               // dist
                            tr.put(pt);
                        }
                        je.put("track", tr);
                        putCurve(je, "hrCurve", e.hrCurve);
                        putCurve(je, "paceCurve", e.paceCurve);
                        putCurve(je, "altCurve", e.altCurve);
                        putCurve(je, "cadCurve", e.cadCurve);
                        putCurve(je, "strideCurve", e.strideCurve);
                        putCurve(je, "ctCurve", e.ctCurve);
                        putCurve(je, "vampCurve", e.vampCurve);
                        putCurve(je, "spdCurve", e.spdCurve);
                        je.put("trackAscent", Math.round(e.trackAscent));
                        je.put("trackDescent", Math.round(e.trackDescent));
                        je.put("trackMaxSpeedKmh", Math.round(e.trackMaxSpeedKmh * 10) / 10.0);
                    }
                    exs.put(je);
                }
            }
            o.put("exercises", exs);

            // 专业跑步指标（VO2max / 跑力指数 / 成绩预测），基于真实跑步记录 + 身体资料个体化
            try {
                com.example.watchai.analysis.RunningMetrics.Profile rmp = new com.example.watchai.analysis.RunningMetrics.Profile();
                if (profile != null) {
                    rmp.age = profile.age; rmp.restHr = profile.restHr;
                    rmp.weightKg = profile.weightKg; rmp.gender = profile.gender;
                }
                // 静息心率未手填时，用手表自动提取的基线值参与心率储备计算
                if (rmp.restHr <= 0 && baseline.restHr > 0) rmp.restHr = baseline.restHr;
                com.example.watchai.analysis.RunningMetrics.Result perf =
                        com.example.watchai.analysis.RunningMetrics.analyze(data.exercises, rmp);
                JSONObject pf = new JSONObject();
                pf.put("hasData", perf.hasData);
                pf.put("vo2max", perf.vo2max);
                pf.put("vo2maxLevel", perf.vo2maxLevel);
                pf.put("vo2PeerText", perf.vo2PeerText);
                pf.put("runningIndex", perf.runningIndex);
                pf.put("riLevel", perf.riLevel);
                pf.put("hasPrediction", perf.hasPrediction);
                pf.put("pred5k", perf.pred5k);
                pf.put("pred10k", perf.pred10k);
                pf.put("predHalf", perf.predHalf);
                pf.put("predFull", perf.predFull);
                pf.put("basis", perf.basis);
                pf.put("refRace", perf.refRace);
                o.put("performance", pf);
            } catch (Exception ignored) { }

            // 内置 AI 教练
            try {
                com.example.watchai.analysis.AICoach.Report rep = com.example.watchai.analysis.AICoach.analyze(data);
                JSONObject c = new JSONObject();
                c.put("headline", rep.headline);
                c.put("summary", rep.summary);
                c.put("todayAdvice", rep.todayAdvice);
                c.put("readiness", rep.readiness);
                c.put("readiness10", rep.readiness10);
                // 三步方案字段
                c.put("redFlag", rep.redFlag);
                c.put("redFlagText", rep.redFlagText);
                c.put("recoveryScore", rep.recoveryScore);
                c.put("sleepScore", rep.sleepScore);
                c.put("loadScore", rep.loadScore);
                c.put("subjectiveMod", rep.subjectiveMod);
                c.put("hrvScore", rep.hrvScore); c.put("rhrScore", rep.rhrScore);
                c.put("respSpo2Score", rep.respSpo2Score); c.put("tempScore", rep.tempScore);
                c.put("sleepDurationScore", rep.sleepDurationScore);
                c.put("sleepStructureScore", rep.sleepStructureScore);
                c.put("sleepRegularScore", rep.sleepRegularScore);
                c.put("loadRatioScore", rep.loadRatioScore);
                c.put("yesterdayLoadScore", rep.yesterdayLoadScore);
                c.put("consecutiveLoadScore", rep.consecutiveLoadScore);
                c.put("rhrText", rep.rhrText); c.put("spo2Text", rep.spo2Text); c.put("tempText", rep.tempText);
                c.put("durText", rep.durText); c.put("structText", rep.structText); c.put("regText", rep.regText);
                c.put("loadRatioText", rep.loadRatioText); c.put("yestLoadText", rep.yestLoadText); c.put("consText", rep.consText);
                c.put("tier", rep.tier);
                c.put("tierAdvice", rep.tierAdvice);
                c.put("reboundText", rep.reboundText);
                c.put("detailText", rep.detailText);
                c.put("prevDate", rep.prevDate);
                // 兼容旧字段
                c.put("durationPart", rep.durationPart);
                c.put("deepPart", rep.deepPart);
                c.put("remPart", rep.remPart);
                c.put("efficiencyPart", rep.efficiencyPart);
                c.put("regularityPart", rep.regularityPart);
                c.put("hrvAdj", rep.hrvAdj);
                c.put("sleepPart", rep.sleepPart);
                c.put("sleepDebtMin", rep.sleepDebtMin);
                c.put("regularityText", rep.regularityText);
                c.put("debtText", rep.debtText);
                c.put("hrvText", rep.hrvText);
                c.put("sleepAnalysis", rep.sleepAnalysis);
                c.put("sportAnalysis", rep.sportAnalysis);
                c.put("weekComparison", rep.weekComparison);
                c.put("trainingAdvice", rep.trainingAdvice);
                JSONArray qa = new JSONArray();
                for (String[] p : rep.qa) {
                    JSONObject j = new JSONObject();
                    j.put("q", p[0]); j.put("a", p[1]);
                    qa.put(j);
                }
                c.put("qa", qa);
                o.put("coach", c);
            } catch (Exception ignored) { }
        } catch (Exception e) { }
        return o;
    }

    /** int[] 转 JSONArray（null → 空数组） */
    private static JSONArray toArr(int[] a) {
        JSONArray ja = new JSONArray();
        if (a == null) return ja;
        for (int v : a) ja.put(v);
        return ja;
    }

    /** 曲线数组写入 JSON，最多保留 160 点、保留 1 位小数，控制体积 */
    private static void putCurve(JSONObject o, String key, double[] arr) {
        if (arr == null || arr.length == 0) return;
        try {
            JSONArray ja = new JSONArray();
            int step = Math.max(1, arr.length / 160);
            for (int i = 0; i < arr.length; i += step)
                ja.put(Math.round(arr[i] * 10) / 10.0);
            o.put(key, ja);
        } catch (Exception ignored) { }
    }

    public static JSONObject diagJson(DiagReport g) {
        JSONObject o = new JSONObject();
        try {
            o.put("shellOk", g.shellOk);
            o.put("mode", g.shellMode == null ? "" : g.shellMode);
            o.put("miInstalled", g.miInstalled);
            o.put("dbFound", g.dbFound);
            o.put("dbPath", g.dbPath == null ? "" : g.dbPath);
            o.put("parsed", g.parsed);
            o.put("trace", g.shellTrace == null ? "" : g.shellTrace);
            o.put("lines", new JSONArray(g.lines));
            o.put("dbScan", g.dbScan == null ? "" : g.dbScan);
            o.put("tableScan", g.tableScan == null ? "" : g.tableScan);
        } catch (Exception e) { }
        return o;
    }
}
