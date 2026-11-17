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

import com.example.watchai.model.Exercise;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 运动记录导出：把一场锻炼转成标准 TCX / GPX 文件文本，或导出全量原始 JSON。
 *  - TCX (Training Center XML) 是佳明 Garmin Connect 的原生导入格式，
 *    同时被 Strava、Endomondo、乐动力、华为运动健康等主流 App 识别；
 *  - GPX 1.1 是通用 GPS 交换格式，被两步路、行者、佳明等广泛支持；
 *  - 原始 JSON 保留手表解码出的每一个字段与每一条曲线点，做到数据零丢失。
 *
 * ★ 关键：导出必须用【原始 Exercise 对象】经 fromExercise() 构造的完整 JSON，
 *   绝不能用前端展示用的降采样数据（DataJsonBuilder.putCurve 会把曲线压到 160 点）。
 *   所有轨迹点、所有曲线点、所有汇总字段全部原样带出。
 */
public final class WorkoutExporter {

    private WorkoutExporter() { }

    // ==================== 完整对象构造（数据零丢失的核心）====================

    /** 把原始 Exercise 的完整字段 + 全量曲线 + 全量轨迹转成 JSONObject（不做任何降采样）。 */
    public static JSONObject fromExercise(Exercise e) {
        JSONObject o = new JSONObject();
        try {
            // 标识与分类
            put(o, "key", e.key); put(o, "category", e.category);
            put(o, "name", e.name); put(o, "badge", e.badge); o.put("colorIdx", e.colorIdx);
            // 时间与汇总
            o.put("startSec", e.startSec); o.put("endSec", e.endSec);
            o.put("durationSec", e.durationSec); o.put("distanceM", e.distanceM);
            o.put("calories", e.calories); o.put("totalCalories", e.totalCalories);
            o.put("steps", e.steps);
            o.put("avgHr", e.avgHr); o.put("maxHr", e.maxHr);
            o.put("avgCadence", e.avgCadence); o.put("maxCadence", e.maxCadence);
            o.put("paceSecPerKm", e.paceSecPerKm);
            put(o, "pace", e.paceText());
            // 海拔与速度
            o.put("ascentM", e.ascentM); o.put("descentM", e.descentM);
            o.put("avgAltM", e.avgAltM); o.put("minAltM", e.minAltM); o.put("maxAltM", e.maxAltM);
            o.put("avgStrideCm", e.avgStrideCm);
            o.put("avgSpeedKmh", e.avgSpeedKmh); o.put("maxSpeedKmh", e.maxSpeedKmh);
            // 跑步动态（全部字段）
            o.put("avgGroundContactMs", e.avgGroundContactMs);
            o.put("minGroundContactMs", e.minGroundContactMs);
            o.put("maxGroundContactMs", e.maxGroundContactMs);
            o.put("avgVerticalOscCm", e.avgVerticalOscCm);
            o.put("minVerticalOscCm", e.minVerticalOscCm);
            o.put("maxVerticalOscCm", e.maxVerticalOscCm);
            o.put("avgVerticalRatio", e.avgVerticalRatio);
            o.put("maxVerticalRatio", e.maxVerticalRatio);
            // 逐点解码汇总
            o.put("trackAscent", e.trackAscent); o.put("trackDescent", e.trackDescent);
            o.put("trackMaxSpeedKmh", e.trackMaxSpeedKmh);
            o.put("trackAvgHr", e.trackAvgHr); o.put("trackMaxHr", e.trackMaxHr);
            // 全量轨迹点 [lat, lng, speed(m/s), dist(m)]
            o.put("hasTrack", e.hasTrack);
            JSONArray tr = new JSONArray();
            if (e.track != null) {
                for (double[] p : e.track) {
                    JSONArray pt = new JSONArray();
                    if (p != null) {
                        for (double v : p) pt.put(v); // 全精度，不四舍五入
                    }
                    tr.put(pt);
                }
            }
            o.put("track", tr);
            // 全量曲线（不做 putCurve 的 160 点抽样 / 1 位小数压缩）
            curve(o, "hrCurve", e.hrCurve); curve(o, "paceCurve", e.paceCurve);
            curve(o, "altCurve", e.altCurve); curve(o, "cadCurve", e.cadCurve);
            curve(o, "strideCurve", e.strideCurve); curve(o, "ctCurve", e.ctCurve);
            curve(o, "vampCurve", e.vampCurve); curve(o, "spdCurve", e.spdCurve);
        } catch (Exception ignored) { }
        return o;
    }

    private static void put(JSONObject o, String k, String v) { try { o.put(k, v == null ? "" : v); } catch (Exception ignored) { } }
    private static void curve(JSONObject o, String k, double[] arr) {
        try {
            JSONArray a = new JSONArray();
            if (arr != null) for (double v : arr) a.put(v);
            o.put(k, a);
        } catch (Exception ignored) { }
    }

    /** 导出全量原始 JSON（格式化，人类可读，字段/曲线/轨迹一个不落）。 */
    public static String toRawJson(Exercise e) throws Exception {
        JSONObject o = fromExercise(e);
        JSONObject wrap = new JSONObject();
        try {
            wrap.put("format", "WatchAI workout raw export v1");
            wrap.put("exportedAt", iso(System.currentTimeMillis() / 1000L));
            wrap.put("note", "全量原始数据：含所有汇总字段、全量轨迹点与全部曲线，未做任何降采样。");
            wrap.put("workout", o);
        } catch (Exception ignored) { }
        return wrap.toString(2);
    }

    // ==================== TCX / GPX ====================

    /** 生成 TCX（佳明原生）。 */
    public static String toTcx(JSONObject e) throws Exception {
        double[] hr = arr(e, "hrCurve");
        double[] alt = arr(e, "altCurve");
        double[] cad = arr(e, "cadCurve");
        double[] spd = arr(e, "spdCurve");
        JSONArray track = e.optJSONArray("track");

        String sport = sportOf(e);
        long startSec = e.optLong("startSec", System.currentTimeMillis() / 1000L);
        int durSec = e.optInt("durationSec", 0);
        double distM = e.optDouble("distanceM", 0);
        int cal = e.optInt("totalCalories", 0);
        if (cal <= 0) cal = e.optInt("calories", 0);

        StringBuilder x = new StringBuilder(8192);
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        x.append("<TrainingCenterDatabase xmlns=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2\"")
         .append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"")
         .append(" xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v2\"")
         .append(" xsi:schemaLocation=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2 ")
         .append("http://www.garmin.com/xmlschemas/TrainingCenterDatabasev2.xsd ")
         .append("http://www.garmin.com/xmlschemas/TrackPointExtension/v2 ")
         .append("http://www.garmin.com/xmlschemas/TrackPointExtensionv2.xsd\">\n");
        x.append("  <Activities>\n");
        x.append("    <Activity Sport=\"").append(sport).append("\">\n");
        x.append("      <Id>").append(iso(startSec)).append("</Id>\n");
        x.append("      <Lap StartTime=\"").append(iso(startSec)).append("\">\n");
        x.append("        <TotalTimeSeconds>").append(durSec).append("</TotalTimeSeconds>\n");
        x.append("        <DistanceMeters>").append(f2(distM)).append("</DistanceMeters>\n");
        x.append("        <Calories>").append(cal).append("</Calories>\n");
        x.append("        <Intensity>Active</Intensity>\n");
        x.append("        <TriggerMethod>Manual</TriggerMethod>\n");
        x.append("        <Track>\n");

        int n = pointCount(track, hr, alt, cad);
        for (int i = 0; i < n; i++) {
            double frac = n > 1 ? (double) i / (n - 1) : 0;
            long t = startSec + Math.round(durSec * frac);
            double lat = 0, lng = 0, ptDist = distM * frac, ptSpeed = 0;
            boolean hasPos = false;
            if (track != null && i < track.length()) {
                try {
                    JSONArray p = track.getJSONArray(i);
                    lat = p.optDouble(0, 0); lng = p.optDouble(1, 0);
                    if (p.length() > 2) ptSpeed = p.optDouble(2, 0);   // 原始 m/s，全量带出
                    if (p.length() > 3) ptDist = p.optDouble(3, ptDist);
                    hasPos = validLat(lat) && validLng(lng);
                } catch (Exception ignored) { }
            }
            x.append("          <Trackpoint>\n");
            x.append("            <Time>").append(iso(t)).append("</Time>\n");
            if (hasPos) {
                x.append("            <Position>\n");
                x.append("              <LatitudeDegrees>").append(f6(lat)).append("</LatitudeDegrees>\n");
                x.append("              <LongitudeDegrees>").append(f6(lng)).append("</LongitudeDegrees>\n");
                x.append("            </Position>\n");
                x.append("            <AltitudeMeters>").append(f2(sample(alt, frac, e.optDouble("avgAltM", 0)))).append("</AltitudeMeters>\n");
            }
            x.append("            <DistanceMeters>").append(f2(ptDist)).append("</DistanceMeters>\n");
            int hrv = (int) Math.round(sample(hr, frac, e.optDouble("avgHr", 0)));
            if (hrv > 0) x.append("            <HeartRateBpm><Value>").append(hrv).append("</Value></HeartRateBpm>\n");
            int cadence = (int) Math.round(sample(cad, frac, e.optDouble("avgCadence", 0)));
            if (cadence > 0) x.append("            <Cadence>").append(cadence).append("</Cadence>\n");
            // 速度写入 Garmin 扩展字段（佳明/Strava 可识别），曲线与轨迹点任一有值即带出
            double speedMs = ptSpeed > 0 ? ptSpeed : sample(spd, frac, 0) / 3.6;
            if (speedMs > 0) {
                x.append("            <Extensions><gpxtpx:TrackPointExtension>")
                 .append("<gpxtpx:speed>").append(f4(speedMs)).append("</gpxtpx:speed>")
                 .append("</gpxtpx:TrackPointExtension></Extensions>\n");
            }
            x.append("          </Trackpoint>\n");
        }
        x.append("        </Track>\n");
        x.append("      </Lap>\n");
        x.append("      <Creator xsi:type=\"Device_t\">\n");
        x.append("        <Name>Xiaomi Watch (WatchAI)</Name>\n");
        x.append("        <UnitId>0</UnitId>\n");
        x.append("        <ProductID>0</ProductID>\n");
        x.append("        <Version><VersionMajor>1</VersionMajor><VersionMinor>0</VersionMinor></Version>\n");
        x.append("      </Creator>\n");
        x.append("    </Activity>\n");
        x.append("  </Activities>\n");
        x.append("  <Author xsi:type=\"Application_t\">\n");
        x.append("    <Name>WatchAI</Name>\n");
        x.append("    <Build><Version><VersionMajor>1</VersionMajor><VersionMinor>0</VersionMinor></Version></Build>\n");
        x.append("    <LangID>zh-CN</LangID>\n");
        x.append("    <PartNumber>000-WATCHAI-00</PartNumber>\n");
        x.append("  </Author>\n");
        x.append("</TrainingCenterDatabase>\n");
        return x.toString();
    }

    /** 生成 GPX 1.1（含心率、步频、速度扩展，佳明/两步路/行者通用）。 */
    public static String toGpx(JSONObject e) throws Exception {
        double[] hr = arr(e, "hrCurve");
        double[] alt = arr(e, "altCurve");
        double[] cad = arr(e, "cadCurve");
        double[] spd = arr(e, "spdCurve");
        JSONArray track = e.optJSONArray("track");
        long startSec = e.optLong("startSec", System.currentTimeMillis() / 1000L);
        int durSec = e.optInt("durationSec", 0);
        String name = e.optString("name", "运动");

        StringBuilder x = new StringBuilder(8192);
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        x.append("<gpx version=\"1.1\" creator=\"WatchAI\" xmlns=\"http://www.topografix.com/GPX/1/1\"")
         .append(" xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\"")
         .append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"")
         .append(" xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n");
        x.append("  <metadata><name>").append(esc(name)).append("</name></metadata>\n");
        x.append("  <trk>\n");
        x.append("    <name>").append(esc(name)).append("</name>\n");
        x.append("    <trkseg>\n");

        int n = pointCount(track, hr, alt, cad);
        for (int i = 0; i < n; i++) {
            double frac = n > 1 ? (double) i / (n - 1) : 0;
            long t = startSec + Math.round(durSec * frac);
            double lat = 0, lng = 0, ptSpeed = 0;
            boolean hasPos = false;
            if (track != null && i < track.length()) {
                try {
                    JSONArray p = track.getJSONArray(i);
                    lat = p.optDouble(0, 0); lng = p.optDouble(1, 0);
                    if (p.length() > 2) ptSpeed = p.optDouble(2, 0);
                    hasPos = validLat(lat) && validLng(lng);
                } catch (Exception ignored) { }
            }
            if (!hasPos) continue; // GPX 需要坐标，跳过无有效坐标点
            x.append("      <trkpt lat=\"").append(f6(lat)).append("\" lon=\"").append(f6(lng)).append("\">\n");
            x.append("        <ele>").append(f2(sample(alt, frac, e.optDouble("avgAltM", 0)))).append("</ele>\n");
            x.append("        <time>").append(iso(t)).append("</time>\n");
            int hrv = (int) Math.round(sample(hr, frac, e.optDouble("avgHr", 0)));
            int cadence = (int) Math.round(sample(cad, frac, e.optDouble("avgCadence", 0)));
            double speedMs = ptSpeed > 0 ? ptSpeed : sample(spd, frac, 0) / 3.6;
            if (hrv > 0 || cadence > 0 || speedMs > 0) {
                x.append("        <extensions><gpxtpx:TrackPointExtension>");
                if (hrv > 0) x.append("<gpxtpx:hr>").append(hrv).append("</gpxtpx:hr>");
                if (cadence > 0) x.append("<gpxtpx:cad>").append(cadence).append("</gpxtpx:cad>");
                if (speedMs > 0) x.append("<gpxtpx:speed>").append(f4(speedMs)).append("</gpxtpx:speed>");
                x.append("</gpxtpx:TrackPointExtension></extensions>\n");
            }
            x.append("      </trkpt>\n");
        }
        x.append("    </trkseg>\n");
        x.append("  </trk>\n");
        x.append("</gpx>\n");
        return x.toString();
    }

    // ==================== 工具 ====================

    private static int pointCount(JSONArray track, double[] a, double[] b, double[] c) {
        if (track != null && track.length() > 1) return track.length();
        int m = 0;
        if (a != null) m = Math.max(m, a.length);
        if (b != null) m = Math.max(m, b.length);
        if (c != null) m = Math.max(m, c.length);
        return m;
    }

    private static double sample(double[] arr, double frac, double fallback) {
        if (arr == null || arr.length == 0) return fallback;
        int i = (int) Math.round(frac * (arr.length - 1));
        if (i < 0) i = 0;
        if (i >= arr.length) i = arr.length - 1;
        double v = arr[i];
        return (v > 0 && isFinite(v)) ? v : fallback;
    }

    private static double[] arr(JSONObject e, String key) {
        JSONArray a = e.optJSONArray(key);
        if (a == null) return new double[0];
        double[] out = new double[a.length()];
        for (int i = 0; i < a.length(); i++) out[i] = a.optDouble(i, 0);
        return out;
    }

    private static String sportOf(JSONObject e) {
        String s = e.optString("badge", "") + e.optString("name", "");
        if (s.contains("游")) return "Swimming";
        if (s.contains("骑") || s.contains("自行车") || s.contains("单车")) return "Biking";
        if (s.contains("走") || s.contains("徒步") || s.contains("登山") || s.contains("健走")) return "Walking";
        if (s.contains("跑")) return "Running";
        return "Other";
    }

    /** 导出文件名，如 户外跑步_20261117_101500.tcx */
    public static String fileName(JSONObject e, String ext, long startSec) {
        String name = e.optString("name", "运动").replaceAll("[\\\\/:*?\"<>|\\s]", "");
        if (name.length() > 12) name = name.substring(0, 12);
        if (name.length() == 0) name = "运动";
        if (startSec <= 0) startSec = e.optLong("startSec", System.currentTimeMillis() / 1000L);
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA);
        return name + "_" + f.format(new Date(startSec * 1000L)) + "." + ext;
    }

    private static String iso(long sec) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(sec * 1000L));
    }

    private static boolean validLat(double v) { return v >= -90 && v <= 90 && v != 0 && isFinite(v); }
    private static boolean validLng(double v) { return v >= -180 && v <= 180 && v != 0 && isFinite(v); }
    private static boolean isFinite(double v) { return !Double.isNaN(v) && !Double.isInfinite(v); }
    private static String f2(double v) { return String.format(Locale.US, "%.2f", isFinite(v) ? v : 0); }
    private static String f4(double v) { return String.format(Locale.US, "%.4f", isFinite(v) ? v : 0); }
    private static String f6(double v) { return String.format(Locale.US, "%.6f", isFinite(v) ? v : 0); }
    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
