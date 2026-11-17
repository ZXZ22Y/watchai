package com.example.watchai.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 联网 AI 深度解读。
 * 默认内置「免费免注册免 Key」网关（Kilo Gateway），开箱即用；多个免费模型自动降级。
 * 用户也可在设置里填自己的 OpenAI 兼容端点/Key（DeepSeek、智谱等）。
 * 数据仅在用户主动点击"联网深度解读"时发送；全部失败时由调用方降级到本地分析。
 */
public class CloudAI {

    /** 内置免费网关：免注册、免 Key（OpenAI 兼容）。免费档有 IP 限流，故多模型降级。 */
    public static final String FREE_ENDPOINT = "https://api.kilo.ai/api/gateway/v1/chat/completions";
    /**
     * 免费模型候选（按优先级）；均为 :free，cost=0。
     * 2026-11 实测：只收录能稳定返回「中文正文 content」的非推理模型。
     * 之前首选的 ernie/nemotron-liquid 等是推理模型，content=null、token 全耗在 reasoning，
     * 导致正文为空 —— 这正是「AI 分析不了」的根因。推理模型的 reasoning 是英文思考过程，绝不可当正文返回。
     */
    private static final String[] FREE_MODELS = {
            "nvidia/nemotron-3-ultra-550b-a55b:free",   // 550B 强模型，长报告完整、格式规范（首选）
            "particle-ai/particle-1:free",              // 干净直接，响应快
            "dots-studio/dots-3-note-preview:free",    // 表达自然
            "cohere/north-mini-code:free",             // 备选
            "openrouter/free",                          // 网关自动路由兜底
            "kilo-auto/free"
    };

    /** 同步请求；请在子线程调用。成功返回模型文本，失败抛异常。apiKey 可为空（免费网关）。 */
    public static String chat(String endpoint, String apiKey, String model, String system, String user) throws Exception {
        if (endpoint == null || endpoint.trim().isEmpty()) endpoint = FREE_ENDPOINT;
        if (model == null || model.trim().isEmpty()) model = FREE_MODELS[0];
        return request(endpoint.trim(), apiKey, model.trim(), system, user);
    }

    /**
     * 智能调用：免 Key 时用内置免费网关并对多个免费模型自动降级；
     * 用户配置了 Key 时优先用其自定义端点，失败再退回免费网关。
     */
    public static String smartChat(String endpoint, String apiKey, String model, String system, String user) throws Exception {
        boolean hasKey = apiKey != null && !apiKey.trim().isEmpty();
        boolean custom = endpoint != null && !endpoint.trim().isEmpty()
                && !endpoint.contains("kilo.ai") && !"free".equals(model);

        Exception lastErr = null;

        // 1) 用户自定义端点（带 Key）
        if (custom && hasKey) {
            try {
                return request(endpoint.trim(), apiKey.trim(), model == null ? "deepseek-chat" : model.trim(), system, user);
            } catch (Exception e) { lastErr = e; }
        }

        // 2) 内置免费网关：model 指定则先用它，否则遍历全部候选
        String[] models;
        if (model != null && !model.trim().isEmpty() && !"free".equals(model.trim())) {
            models = new String[]{model.trim()};
        } else {
            models = FREE_MODELS;
        }
        for (String m : models) {
            try {
                String r = request(FREE_ENDPOINT, "", m, system, user);
                if (r != null && r.trim().length() > 0) return r;
            } catch (Exception e) { lastErr = e; }
        }
        if (lastErr != null) throw lastErr;
        throw new RuntimeException("所有免费模型均不可用");
    }

    private static String request(String endpoint, String apiKey, String model, String system, String user) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", 0.5);
        // 六段式深度报告较长，给足预算避免被 length 截断导致内容不完整
        body.put("max_tokens", 2000);
        JSONArray msgs = new JSONArray();
        JSONObject m1 = new JSONObject(); m1.put("role", "system"); m1.put("content", system); msgs.put(m1);
        JSONObject m2 = new JSONObject(); m2.put("role", "user"); m2.put("content", user); msgs.put(m2);
        body.put("messages", msgs);

        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (apiKey != null && !apiKey.trim().isEmpty())
            conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        OutputStream os = conn.getOutputStream();
        os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        os.flush(); os.close();

        int code = conn.getResponseCode();
        BufferedReader br = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(); String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        String resp = sb.toString();

        if (code < 200 || code >= 300) {
            String msg = resp;
            try {
                JSONObject j = new JSONObject(resp);
                if (j.has("error")) {
                    Object e = j.get("error");
                    msg = e instanceof JSONObject ? ((JSONObject) e).optString("message", resp) : String.valueOf(e);
                }
            } catch (Exception ignored) { }
            throw new RuntimeException("API " + code + ": " + msg);
        }
        JSONObject j = new JSONObject(resp);
        JSONArray choices = j.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new RuntimeException("模型未返回内容");
        JSONObject msgObj = choices.getJSONObject(0).getJSONObject("message");
        String content = msgObj.optString("content", "");
        // content 为空说明命中了推理模型（token 全耗在 reasoning）。reasoning 是英文思考过程，
        // 绝不能当正文返回给用户，否则就是一堆看不懂的文字。直接抛异常，让 smartChat 降级到下一个模型。
        if (content == null || content.trim().isEmpty())
            throw new RuntimeException("模型返回正文为空（推理模型）");
        return content.trim();
    }

    /** 把本地健康数据摘要成给模型的输入（只含结构化要点，控制体积） */
    public static String digest(String dataJson) {
        try {
            JSONObject d = new JSONObject(dataJson);
            StringBuilder s = new StringBuilder();
            JSONArray days = d.optJSONArray("days");
            if (days != null) {
                s.append("【最近 ").append(days.length()).append(" 天数据】\n");
                for (int i = 0; i < days.length(); i++) {
                    JSONObject x = days.getJSONObject(i);
                    s.append(x.optString("date")).append(": ")
                            .append("步数").append(x.optInt("steps")).append(", ")
                            .append("锻炼").append(x.optInt("exerciseMinutes")).append("分钟, ")
                            .append("平均心率").append(x.optInt("avgHeartRate")).append(", ")
                            .append("血氧").append(x.optInt("spo2")).append(", ")
                            .append("压力").append(x.optInt("stress")).append(", ");
                    JSONObject sl = x.optJSONObject("sleep");
                    if (sl != null) s.append("睡眠").append(String.format("%.1f", sl.optInt("totalMinutes") / 60f))
                            .append("h(深").append(sl.optInt("deepMinutes")).append("/浅").append(sl.optInt("lightMinutes"))
                            .append("/REM").append(sl.optInt("remMinutes")).append("), HRV").append(sl.optInt("avgHrv"));
                    s.append("\n");
                }
            }
            JSONArray exs = d.optJSONArray("exercises");
            if (exs != null && exs.length() > 0) {
                s.append("\n【锻炼记录】\n");
                int n = Math.min(8, exs.length());
                for (int i = 0; i < n; i++) {
                    JSONObject e = exs.getJSONObject(i);
                    s.append(e.optString("name")).append(": ")
                            .append(String.format("%.2f", e.optDouble("distanceM") / 1000.0)).append("km, ")
                            .append(e.optInt("durationSec") / 60).append("分钟, 均心率").append(e.optInt("avgHr"))
                            .append(", 消耗").append(e.optInt("calories")).append("kcal");
                    if (e.optDouble("avgVerticalRatio", 0) > 0)
                        s.append(", 垂直步幅比").append(String.format("%.1f%%", e.optDouble("avgVerticalRatio")));
                    s.append("\n");
                }
            }
            JSONObject c = d.optJSONObject("coach");
            if (c != null) {
                s.append("\n【准备度与恢复评估】\n准备度 ").append(c.optInt("readiness"))
                        .append("/100（").append(c.optInt("readiness10")).append("/10）");
                s.append("\n睡眠债 ").append(c.optInt("sleepDebtMin")).append(" 分钟，HRV 恢复修正 ")
                        .append(c.optInt("hrvAdj")).append(" 分");
                String[] texts = {"debtText", "regularityText", "hrvText", "weekComparison",
                        "sleepAnalysis", "sportAnalysis", "trainingAdvice"};
                for (String k : texts) {
                    String v = c.optString(k, "");
                    if (v != null && v.length() > 0) s.append("\n").append(v);
                }
            }
            return s.toString();
        } catch (Exception e) {
            return "数据解析失败：" + e.getMessage();
        }
    }

    /** 睡眠专项数据摘要：多晚分期/HRV/作息/评分，供 AI 睡眠深度分析使用 */
    public static String sleepDigest(String dataJson) {
        try {
            JSONObject d = new JSONObject(dataJson);
            StringBuilder s = new StringBuilder();
            java.util.List<String> nights = new java.util.ArrayList<String>();
            JSONArray days = d.optJSONArray("days");
            if (days != null) {
                for (int i = 0; i < days.length(); i++) {
                    JSONObject x = days.getJSONObject(i);
                    JSONObject sl = x.optJSONObject("sleep");
                    if (sl == null || sl.optInt("totalMinutes", 0) <= 0) continue;
                    int deep = sl.optInt("deepMinutes"), light = sl.optInt("lightMinutes");
                    int rem = sl.optInt("remMinutes"), awake = sl.optInt("awakeMinutes");
                    int tot = deep + light + rem + awake; if (tot <= 0) tot = sl.optInt("totalMinutes");
                    String line = x.optString("date") + ": 睡眠" + String.format("%.1f", sl.optInt("totalMinutes") / 60f)
                            + "h(深" + deep + "/浅" + light + "/REM" + rem + "/清醒" + awake + "分钟)"
                            + ", 睡眠效率" + (tot > 0 ? Math.round((deep + light + rem) * 100f / tot) : 0) + "%"
                            + ", 深睡占比" + (tot > 0 ? Math.round(deep * 100f / tot) : 0) + "%"
                            + ", REM占比" + (tot > 0 ? Math.round(rem * 100f / tot) : 0) + "%"
                            + ", 夜间心率" + sl.optInt("avgHeartRate") + "bpm, HRV" + sl.optInt("avgHrv") + "ms";
                    if (sl.optLong("bedTimeMs", 0) > 0)
                        line += ", 入睡" + hhmm(sl.optLong("bedTimeMs")) + " 起床" + hhmm(sl.optLong("wakeTimeMs"));
                    nights.add(line);
                }
            }
            if (nights.isEmpty()) return "暂无睡眠数据";
            s.append("【最近 ").append(nights.size()).append(" 晚真实睡眠数据（最近在前）】\n");
            for (String l : nights) s.append(l).append("\n");
            JSONArray scores = d.optJSONArray("scores");
            if (scores != null && scores.length() > 0) {
                s.append("\n【睡眠评分记录】\n");
                int n = Math.min(7, scores.length());
                for (int i = 0; i < n; i++) {
                    JSONObject sc = scores.getJSONObject(i);
                    s.append(sc.optString("date")).append(": ").append(sc.optInt("score")).append("分, ")
                            .append(sc.optString("level", ""));
                    JSONArray adv = sc.optJSONArray("advice");
                    if (adv != null && adv.length() > 0) {
                        s.append(" [");
                        for (int k = 0; k < adv.length(); k++) s.append(adv.optString(k)).append(k < adv.length() - 1 ? "；" : "");
                        s.append("]");
                    }
                    s.append("\n");
                }
            }
            JSONObject c = d.optJSONObject("coach");
            if (c != null) {
                s.append("\n【睡眠债与恢复】\n睡眠债 ").append(c.optInt("sleepDebtMin")).append(" 分钟，HRV 恢复修正 ")
                        .append(c.optInt("hrvAdj")).append(" 分\n");
                String[] ks = {"debtText", "regularityText", "hrvText", "sleepAnalysis", "weekComparison"};
                for (String k : ks) { String v = c.optString(k, ""); if (v != null && v.length() > 0) s.append(v).append("\n"); }
            }
            return s.toString();
        } catch (Exception e) { return "睡眠数据解析失败：" + e.getMessage(); }
    }

    private static String hhmm(long ms) {
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        cal.setTimeInMillis(ms);
        return String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE));
    }

    /**
     * 跑步教练专用数据摘要：提取近 5 次跑步的距离/配速/心率/跑步动态与趋势，
     * 附身体资料，供 AI 生成「下一次怎么跑」方案。
     */
    public static String runDigest(String dataJson, String profileJson) {
        try {
            JSONObject d = new JSONObject(dataJson);
            StringBuilder s = new StringBuilder();
            // 身体资料
            if (profileJson != null) {
                try {
                    JSONObject p = new JSONObject(profileJson);
                    int age = p.optInt("age"), h = p.optInt("height"), rhr = p.optInt("restHr");
                    float w = (float) p.optDouble("weight");
                    String gender = "male".equals(p.optString("gender")) ? "男" : "女";
                    s.append("【身体资料】").append(gender).append("性");
                    if (age > 0) s.append("，").append(age).append("岁");
                    if (h > 0) s.append("，身高").append(h).append("cm");
                    if (w > 0) s.append("，体重").append(String.format("%.1f", w)).append("kg");
                    if (rhr > 0) s.append("，静息心率").append(rhr).append("bpm");
                    s.append("\n");
                    if (age > 0 && rhr > 0) {
                        int hrmax = 220 - age;
                        int ehr = (int) (rhr + (hrmax - rhr) * 0.6), fhr = (int) (rhr + (hrmax - rhr) * 0.8);
                        s.append("参考最大心率约").append(hrmax).append("bpm，有氧心率区间约")
                                .append(ehr).append("–").append(fhr).append("bpm（储备心率法估算）\n");
                    }
                } catch (Exception ignored) { }
            }
            // 近 5 次跑步（按数组顺序即最近在前）
            JSONArray exs = d.optJSONArray("exercises");
            if (exs != null && exs.length() > 0) {
                s.append("\n【近五次跑步记录（最近在前）】\n");
                int n = Math.min(5, exs.length());
                for (int i = 0; i < n; i++) {
                    JSONObject e = exs.getJSONObject(i);
                    String key = e.optString("key", "");
                    if (!key.toLowerCase().contains("running")) continue;
                    double distKm = e.optDouble("distanceM") / 1000.0;
                    int mins = e.optInt("durationSec") / 60;
                    double pace = e.optDouble("paceSecPerKm");
                    String paceTxt = pace > 0 ? (int) (pace / 60) + "'" + String.format("%02d", (int) pace % 60) + "/km" : "--";
                    s.append("第").append(i + 1).append("次: ").append(String.format("%.2f", distKm)).append("km, ")
                            .append(mins).append("分钟, 配速").append(paceTxt)
                            .append(", 均心率").append(e.optInt("avgHr")).append("/最大").append(e.optInt("maxHr"))
                            .append(", 步频").append(e.optInt("avgCadence"));
                    if (e.optDouble("avgVerticalRatio", 0) > 0)
                        s.append(", 垂直步幅比").append(String.format("%.1f%%", e.optDouble("avgVerticalRatio")));
                    s.append(", 消耗").append(e.optInt("calories")).append("kcal\n");
                }
            }
            // 恢复状态：准备度 / 睡眠 / HRV / 睡眠债
            JSONObject c = d.optJSONObject("coach");
            if (c != null) {
                s.append("\n【当前恢复状态】准备度").append(c.optInt("readiness10")).append("/10");
                s.append("，睡眠债").append(c.optInt("sleepDebtMin")).append("分钟，HRV修正")
                        .append(c.optInt("hrvAdj")).append("分\n");
                String[] ks = {"debtText", "hrvText", "regularityText", "sportAnalysis", "trainingAdvice"};
                for (String k : ks) {
                    String v = c.optString(k, "");
                    if (v != null && v.length() > 0) s.append(v).append("\n");
                }
            }
            // 今日活动
            JSONArray days = d.optJSONArray("days");
            if (days != null && days.length() > 0) {
                JSONObject t = days.getJSONObject(0);
                s.append("\n【最近一天】步数").append(t.optInt("steps"))
                        .append("，锻炼").append(t.optInt("exerciseMinutes")).append("分钟")
                        .append("，压力").append(t.optInt("stress")).append("\n");
            }
            return s.toString();
        } catch (Exception e) {
            return "数据解析失败：" + e.getMessage();
        }
    }
}
