package com.example.watchai.data;

import android.content.Context;

import com.example.watchai.model.DayHealth;
import com.example.watchai.model.Exercise;
import com.example.watchai.model.HealthDataSet;
import com.example.watchai.model.SleepRecord;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * 从「小米运动健康」私有库读取真实数据 —— 数据与算法完全照搬 lissjr/mi-fit：
 *   /data/data/com.mi.health/databases/<账号ID>/cn/fitness_data
 * 用 root 常驻 shell + 自解析 SQLite 页结构（不依赖 sqlite3），按东八区聚合最近 7 天。
 *
 * 这是本 App 唯一的数据来源。不接 Gadgetbridge、不使用任何演示数据。
 */
public class MiHealthReader {

    public final DiagReport diag = new DiagReport();

    private static final String DB_GLOB = "/data/data/com.mi.health/databases/*/cn/fitness_data";

    private final SimpleDateFormat sdf;
    {
        sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("GMT+08:00"));
    }

    private String dateOf(long tsSec) { return sdf.format(new Date(tsSec * 1000L)); }

    /** 东八区所在小时 0-23 */
    private static int hourOf(long tsSec) { return (int) (((tsSec + 8L * 3600) / 3600) % 24); }

    /** 累加一个分时均值桶：sum[24]、cnt[24] 并行；无数据保持 -1 */
    private static void addHour(int[] sum, int[] cnt, long tsSec, double val) {
        int h = hourOf(tsSec);
        sum[h] += (int) Math.round(val);
        cnt[h]++;
    }

    /** 把 sum/cnt 桶转成均值序列（无数据的小时为 -1） */
    private static int[] finish(int[] sum, int[] cnt) {
        int[] out = new int[24];
        for (int i = 0; i < 24; i++) out[i] = cnt[i] > 0 ? sum[i] / cnt[i] : -1;
        return out;
    }

    /**
     * 轻量数据源指纹：只读取主库及其 -wal 文件的修改时间与大小，用于判断
     * 「小米运动健康」是否已同步了新数据。任一变化都会返回不同的字符串。
     * 失败或未找到返回空串。用完即关 shell，成本很低。
     */
    public static String fingerprint(Context ctx) {
        RootShell sh = new RootShell();
        try {
            if (!sh.start(ctx)) return "";
            String dbPath = null;
            String ls = sh.raw("ls -1t " + DB_GLOB + " 2>/dev/null");
            for (String line : ls.split("\n")) {
                String s = line.trim();
                if (s.startsWith("/data/data/") && s.endsWith("/cn/fitness_data")) { dbPath = s; break; }
            }
            if (dbPath == null) {
                String f = sh.raw("find /data/data/com.mi.health/databases -name fitness_data 2>/dev/null | head -5");
                for (String line : f.split("\n")) {
                    String s = line.trim();
                    if (s.endsWith("/cn/fitness_data")) { dbPath = s; break; }
                }
            }
            if (dbPath == null) return "";
            String main = sh.raw("stat -c '%Y:%s' '" + dbPath + "' 2>/dev/null").trim();
            String wal = sh.raw("stat -c '%Y:%s' '" + dbPath + "-wal' 2>/dev/null").trim();
            return main + "|" + wal;
        } catch (Exception e) {
            return "";
        } finally {
            sh.stop();
        }
    }

    public HealthDataSet read(Context ctx) {
        HealthDataSet data = new HealthDataSet();
        data.source = HealthDataSet.SOURCE_MI_HEALTH;
        data.days = new ArrayList<DayHealth>();

        RootShell.cleanLeftovers(ctx);
        RootShell sh = new RootShell();
        boolean started = sh.start(ctx);
        diag.shellOk = started;
        diag.shellMode = sh.mode();
        diag.shellTrace = sh.trace.toString();
        diag.miInstalled = true;
        diag.add(started ? "[OK] root 通道就绪" : "[X] root 通道不可用");
        for (String l : sh.trace.toString().split("\n")) if (!l.trim().isEmpty()) diag.add(l);
        if (!started) {
            data.message = "无法取得能读取小米运动健康私有目录的 root 通道";
            diag.err("读取中止", data.message);
            return data;
        }

        // 1) 定位账号库
        String dbPath = null;
        String ls = sh.raw("ls -1t " + DB_GLOB + " 2>/dev/null");
        diag.dbScan = ls;
        for (String line : ls.split("\n")) {
            String s = line.trim();
            if (s.startsWith("/data/data/") && s.endsWith("/cn/fitness_data")) { dbPath = s; break; }
        }
        if (dbPath == null) {
            String f = sh.raw("find /data/data/com.mi.health/databases -name fitness_data 2>/dev/null | head -20");
            if (!f.trim().isEmpty()) diag.dbScan += "\n[find]\n" + f;
            for (String line : f.split("\n")) {
                String s = line.trim();
                if (s.endsWith("/cn/fitness_data")) { dbPath = s; break; }
            }
        }
        if (dbPath == null) {
            data.message = "未找到小米运动的健身数据库（cn/fitness_data）。请确认小米运动健康已安装、登录账号并同步过数据。";
            diag.err("定位数据库", data.message);
            sh.stop();
            return data;
        }
        diag.dbFound = true;
        diag.dbPath = dbPath;
        diag.ok("数据库", dbPath);

        // 2) 打开库（自解析页结构）
        Db db = new Db(sh, dbPath);
        try {
            db.open();
            diag.ok("打开数据库", "pageSize=" + db.pageSize() + " 字节");
        } catch (Exception e) {
            data.message = "数据库打开失败：" + e.getMessage();
            diag.err("打开数据库", e.getMessage());
            sh.stop();
            return data;
        }

        try {
            data.days = aggregate(db);
            data.exercises = readExercises(db);
            applyExerciseMinutes(data.days, data.exercises);
            // 尝试为每场锻炼匹配逐点轨迹文件（GPS 轨迹 + 跑步动态曲线）
            try { attachTracks(sh, data.exercises); }
            catch (Exception e) { diag.no("轨迹解码", e.getMessage()); }
            diag.parsed = !data.days.isEmpty() || !data.exercises.isEmpty();
            data.message = diag.parsed
                    ? "已从你的小米运动健康真实数据读取 " + data.days.size() + " 天记录 · " + data.exercises.size() + " 场锻炼"
                    : "数据库已打开，但最近没有解析到健康记录";
            diag.ok("解析完成", data.message);
        } catch (Exception e) {
            data.message = "解析数据出错：" + e.getMessage();
            diag.err("解析数据", e.getMessage());
        } finally {
            sh.stop();
        }
        return data;
    }

    /** 各表独立 try，某张表坏掉不影响其它表 */
    private List<Map<String, Object>> tail(Db db, String table, int limit) {
        try {
            List<Map<String, Object>> rows = db.readTail(table, limit);
            diag.add("表 " + table + " → " + rows.size() + " 行");
            return rows;
        } catch (Exception e) {
            diag.no("表 " + table, e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<DayHealth> aggregate(Db db) {
        LinkedHashMap<String, DayHealth> byDay = new LinkedHashMap<String, DayHealth>();

        // ---- 心率：{bpm, time(秒)} ----
        for (Map<String, Object> row : tail(db, "hr_record", 12000)) {
            JSONObject o = obj(row);
            if (o == null) continue;
            double bpm = o.optDouble("bpm", 0);
            long ts = o.optLong("time", 0);
            if (bpm <= 0 || ts <= 0) continue;
            DayHealth d = day(byDay, ts);
            int v = (int) bpm;
            if (d.minHeartRate == 0) d.minHeartRate = v;
            d.minHeartRate = Math.min(d.minHeartRate, v);
            d.maxHeartRate = Math.max(d.maxHeartRate, v);
            String dayStr = dateOf(ts);
            HRAgg a = hrAgg(dayStr);
            a.sum += bpm; a.n++;
            d.avgHeartRate = (int) Math.round(a.sum / a.n);
            addHour(arr(hrHSum, dayStr), arr(hrHCnt, dayStr), ts, bpm);
        }

        // ---- 血氧：{spo2, time} ----
        for (Map<String, Object> row : tail(db, "spo2_record", 8000)) {
            JSONObject o = obj(row);
            if (o == null) continue;
            double s = o.optDouble("spo2", 0);
            long ts = o.optLong("time", 0);
            if (s <= 0 || ts <= 0) continue;
            String dayStr = dateOf(ts);
            SpAgg a = spAgg(dayStr);
            a.sum += s; a.n++;
            day(byDay, ts).spo2 = (int) Math.round(a.sum / a.n);
            addHour(arr(spHSum, dayStr), arr(spHCnt, dayStr), ts, s);
        }

        // ---- 步数/卡路里：同一分钟取较大值，避免重复累加 ----
        for (Map<String, Object> row : tail(db, "step_record", 12000)) {
            JSONObject o = obj(row);
            if (o == null) continue;
            long ts = o.optLong("time", 0);
            if (ts <= 0) continue;
            int st = (int) o.optDouble("steps", 0);
            int cl = (int) o.optDouble("calories", 0);
            HashMap<Long, int[]> perMin = stepAgg(dateOf(ts));
            int[] cur = perMin.get(ts);
            if (cur == null) perMin.put(ts, new int[]{st, cl});
            else if (st > cur[0]) { cur[0] = st; cur[1] = cl; }
        }
        for (Map.Entry<String, DayHealth> e : byDay.entrySet()) {
            HashMap<Long, int[]> perMin = stepMap.get(e.getKey());
            if (perMin == null) continue;
            int[] hours = arr(stepH, e.getKey());
            for (Map.Entry<Long, int[]> pe : perMin.entrySet()) {
                int[] p = pe.getValue();
                e.getValue().steps += p[0]; e.getValue().calories += p[1];
                hours[hourOf(pe.getKey())] += p[0];
            }
        }

        // ---- 压力：{stress, time} ----
        for (Map<String, Object> row : tail(db, "stress_record", 8000)) {
            JSONObject o = obj(row);
            if (o == null) continue;
            long ts = o.optLong("time", 0);
            if (ts <= 0) continue;
            String dayStr = dateOf(ts);
            double sv = o.optDouble("stress", 0);
            StAgg a = stAgg(dayStr);
            a.sum += sv; a.n++;
            day(byDay, ts).stress = (int) Math.round(a.sum / a.n);
            addHour(arr(ssHSum, dayStr), arr(ssHCnt, dayStr), ts, sv);
        }

        // ---- 站立：{start_time} ----
        for (Map<String, Object> row : tail(db, "stand_record", 600)) {
            JSONObject o = obj(row);
            if (o == null) continue;
            long st = o.optLong("start_time", 0);
            if (st <= 0) continue;
            DayHealth d = day(byDay, st);
            Set<Integer> hours = standHours(dateOf(st));
            hours.add((int) ((st + 8L * 3600) / 3600 % 24));
            d.standHours = hours.size();
        }

        // ---- 睡眠：sleep_segment，归属到"起床日" ----
        for (Map<String, Object> row : tail(db, "sleep_segment", 40)) {
            JSONObject o = obj(row);
            if (o == null) continue;
            int deep = o.optInt("sleep_deep_duration", 0);
            int light = o.optInt("sleep_light_duration", 0);
            int rem = o.optInt("sleep_rem_duration", -1);
            if (deep == 0 && light == 0) continue;
            long wake = o.optLong("wake_up_time", o.optLong("time", 0));
            if (wake <= 0) continue;
            String date = dateOf(wake);
            DayHealth d = dayByDate(byDay, date);
            SleepRecord s = new SleepRecord();
            s.date = date;
            s.deepMinutes = deep;
            s.lightMinutes = light;
            s.remMinutes = Math.max(0, rem);
            s.awakeMinutes = o.optInt("sleep_awake_duration", 0);
            s.avgHeartRate = o.optInt("avg_hr", 0);
            s.avgHrv = o.optInt("avg_hrv", 0);
            s.wakeTimeMs = wake * 1000L;
            int total = o.optInt("duration", s.totalMinutes());
            s.bedTimeMs = (wake - total * 60L) * 1000L;
            d.sleep = s;
        }

        // ---- 体重：列式 weight_item_record ----
        try {
            List<Map<String, Object>> rows = db.readTableObjects("weight_item_record");
            diag.add("表 weight_item_record → " + rows.size() + " 行");
            Map<String, Long> newestTs = new HashMap<String, Long>();
            for (Map<String, Object> row : rows) {
                Object w = row.get("weight"), t = row.get("time");
                if (!(w instanceof Number) || !(t instanceof Number)) continue;
                double kg = ((Number) w).doubleValue();
                long ts = ((Number) t).longValue();
                if (kg <= 0 || ts <= 0) continue;
                String date = dateOf(ts);
                Long prev = newestTs.get(date);
                if (prev != null && prev >= ts) continue;
                newestTs.put(date, ts);
                DayHealth d = dayByDate(byDay, date);
                d.weightX10 = (int) Math.round(kg * 10);
                if (row.get("bodyFatRate") instanceof Number) d.bodyFatX10 = (int) Math.round(((Number) row.get("bodyFatRate")).doubleValue() * 10);
                if (row.get("muscleMass") instanceof Number) {
                    int mx = (int) Math.round(((Number) row.get("muscleMass")).doubleValue() * 10);
                    if (mx > 0) d.muscleX10 = mx;
                }
            }
        } catch (Exception e) { diag.no("表 weight_item_record", e.getMessage()); }

        // ---- 训练负荷：{date_time, current_day_train_load} ----
        for (Map<String, Object> row : tail(db, "training_load_record", 120)) {
            JSONObject o = obj(row);
            if (o == null) continue;
            long ts = o.optLong("date_time", 0);
            if (ts <= 0) continue;
            double load = o.optDouble("current_day_train_load", -1);
            if (load >= 0) day(byDay, ts).trainingLoad = (int) Math.round(load);
        }

        // ---- 取最近 7 天，升序 ----
        List<String> dates = new ArrayList<String>(byDay.keySet());
        Collections.sort(dates);
        List<DayHealth> out = new ArrayList<DayHealth>();
        int start = Math.max(0, dates.size() - 7);
        for (int i = start; i < dates.size(); i++) {
            DayHealth d = byDay.get(dates.get(i));
            fillSeries(d);
            out.add(d);
        }
        return out;
    }

    /** 把每场锻炼的时长按东八区日期累加到当天 exerciseMinutes（今日活动时间的真实来源） */
    private void applyExerciseMinutes(List<DayHealth> days, List<Exercise> exercises) {
        if (days == null || exercises == null) return;
        java.util.Map<String, Integer> mins = new HashMap<String, Integer>();
        for (Exercise e : exercises) {
            if (e.startSec <= 0 || e.durationSec <= 0) continue;
            String date = dateOf(e.startSec);
            Integer cur = mins.get(date);
            mins.put(date, (cur == null ? 0 : cur) + e.durationSec / 60);
        }
        for (DayHealth d : days) {
            Integer m = mins.get(d.date);
            if (m != null) d.exerciseMinutes += m;
        }
    }

    // ==================== 锻炼记录（sport_report，与 mi-fit 同字段） ====================

    /** key/category → {中文名, 徽章, 配色索引} */
    private static final HashMap<String, String[]> TYPE = new HashMap<String, String[]>();
    static {
        String[][] m = {
            {"outdoor_running","户外跑步","跑","0"},{"indoor_running","室内跑步","跑","0"},
            {"treadmill_running","跑步机","跑","0"},{"trail_running","越野跑","跑","0"},
            {"playground_running","操场跑步","跑","0"},{"free_running","自由跑步","跑","0"},
            {"outdoor_riding","户外骑行","骑","1"},{"indoor_riding","室内骑行","骑","1"},
            {"spinning","动感单车","骑","1"},
            {"walking","步行","步","2"},{"outdoor_walking","户外步行","步","2"},{"indoor_walking","室内步行","步","2"},
            {"hiking","徒步","徒","2"},{"climbing","爬山","山","2"},{"mountaineering","登山","山","2"},
            {"strength_training","力量训练","力","3"},{"physical_training","体能训练","体","3"},
            {"free_training","自由训练","练","3"},{"high_interval_training","高强度间歇","HI","3"},
            {"core_training","核心训练","核","3"},{"step_training","台阶训练","阶","3"},
            {"indoor_fitness","室内健身","健","3"},{"rope_skipping","跳绳","绳","3"},
            {"rowing_machine","划船机","划","4"},{"elliptical","椭圆机","椭","4"},
            {"yoga","瑜伽","瑜","4"},{"stretching","拉伸","拉","4"},{"taichi","太极","极","4"},
            {"dance","舞蹈","舞","4"},{"boxing","拳击","拳","4"},
            {"basketball","篮球","篮","5"},{"football","足球","足","5"},{"volleyball","排球","排","5"},
            {"badminton","羽毛球","羽","5"},{"pingpong","乒乓球","乒","5"},{"tennis","网球","网","5"},
            {"swimming","游泳","泳","1"},{"skiing","滑雪","雪","1"},{"rock_climbing","攀岩","攀","2"}
        };
        for (String[] p : m) TYPE.put(p[0], new String[]{p[1], p[2], p[3]});
    }

    private static void applyType(Exercise ex) {
        String[] t = ex.key != null ? TYPE.get(ex.key) : null;
        if (t == null && ex.category != null) t = TYPE.get(ex.category);
        if (t != null) {
            ex.name = t[0]; ex.badge = t[1]; ex.colorIdx = Integer.parseInt(t[2]);
        } else {
            ex.name = (ex.key == null || ex.key.isEmpty()) ? "运动" : ex.key.replace('_', ' ');
            ex.badge = "练"; ex.colorIdx = 3;
        }
    }

    private static String str(Object o) { return o instanceof String ? (String) o : null; }

    private List<Exercise> readExercises(Db db) {
        List<Exercise> out = new ArrayList<Exercise>();
        List<Map<String, Object>> rows;
        try {
            rows = db.readTail("sport_report", 250);
            diag.add("表 sport_report → " + rows.size() + " 行");
        } catch (Exception e) {
            diag.no("表 sport_report", e.getMessage());
            return out;
        }
        Set<String> seen = new HashSet<String>();
        for (Map<String, Object> row : rows) {
            Object del = row.get("isDeleted");
            if (del instanceof Number && ((Number) del).longValue() == 1) continue;
            JSONObject o = obj(row);
            if (o == null || !o.has("start_time")) continue;
            Exercise ex = new Exercise();
            ex.key = str(row.get("key"));
            ex.category = str(row.get("category"));
            ex.startSec = o.optLong("start_time", 0);
            if (ex.startSec <= 0) continue;
            ex.durationSec = (int) o.optDouble("duration", 0);
            ex.endSec = o.has("end_time") ? o.optLong("end_time") : ex.startSec + ex.durationSec;
            ex.distanceM = (int) o.optDouble("distance", 0);
            ex.calories = (int) o.optDouble("calories", 0);
            ex.steps = (int) o.optDouble("steps", 0);
            ex.avgHr = (int) o.optDouble("avg_hrm", 0);
            ex.maxHr = (int) o.optDouble("max_hrm", 0);
            ex.avgCadence = (int) o.optDouble("avg_cadence", 0);
            ex.maxCadence = (int) o.optDouble("max_cadence", 0);
            ex.ascentM = o.optDouble("rise_height", 0);
            ex.descentM = o.optDouble("fall_height", 0);
            ex.avgAltM = o.optDouble("avg_height", 0);
            ex.minAltM = o.optDouble("min_height", 0);
            ex.maxAltM = o.optDouble("max_height", 0);
            // 单位与 mi-fit 源码一致，直接用值，不做换算（原代码误乘 100/3.6 导致步幅 11100cm、速度虚高）
            ex.avgStrideCm = o.optDouble("avg_stride", 0);
            ex.avgSpeedKmh = o.optDouble("avg_speed", 0);
            ex.maxSpeedKmh = o.optDouble("max_speed", 0);
            // 跑步动态专业字段
            ex.avgGroundContactMs = o.optDouble("avg_touchdown_duration", 0);
            ex.minGroundContactMs = o.optDouble("min_touchdown_duration", 0);
            ex.maxGroundContactMs = o.optDouble("max_contact_time", 0);
            ex.avgVerticalOscCm = o.optDouble("avg_vertical_amplitude", 0);
            ex.minVerticalOscCm = o.optDouble("min_vertical_amplitude", 0);
            ex.maxVerticalOscCm = o.optDouble("max_vertical_amplitude", 0);
            ex.avgVerticalRatio = o.optDouble("avg_vertical_stride_ratio", 0);
            ex.maxVerticalRatio = o.optDouble("max_vertical_stride_ratio", 0);
            ex.totalCalories = o.optDouble("total_cal", o.optDouble("calories", 0));
            ex.paceSecPerKm = o.optDouble("avg_pace", 0);
            if (ex.paceSecPerKm <= 0 && ex.distanceM > 0 && ex.durationSec > 0)
                ex.paceSecPerKm = ex.durationSec / (ex.distanceM / 1000.0);
            if (ex.avgSpeedKmh <= 0 && ex.paceSecPerKm > 0) ex.avgSpeedKmh = 3600.0 / ex.paceSecPerKm;
            applyType(ex);
            String dedup = ex.key + "@" + ex.startSec;
            if (seen.contains(dedup)) continue;
            seen.add(dedup);
            out.add(ex);
        }
        Collections.sort(out, new java.util.Comparator<Exercise>() {
            public int compare(Exercise a, Exercise b) { return Long.compare(b.startSec, a.startSec); }
        });
        if (out.size() > 60) out = new ArrayList<Exercise>(out.subList(0, 60));
        return out;
    }

    /** 为锻炼匹配逐点轨迹文件（GPS 轨迹 + 心率/步频/垂直振幅曲线） */
    private void attachTracks(RootShell sh, List<Exercise> exercises) {
        if (exercises == null || exercises.isEmpty()) return;
        Map<Long, TrackIdx.File2> idx = TrackIdx.scan(sh);
        diag.add("轨迹文件 → " + idx.size() + " 个");
        int attached = 0;
        for (Exercise ex : exercises) {
            TrackIdx.File2 f = TrackIdx.match(idx, ex.startSec);
            if (f == null) continue;
            byte[] rec = sh.cat(f.recordPath);
            byte[] gps = sh.cat(f.gpsPath);
            Track t = Track.analyze(rec, gps);
            if (t == null) continue;
            ex.hasTrack = true;
            ex.track = t.track;
            ex.hrCurve = t.hr;
            ex.paceCurve = t.pace;
            ex.altCurve = t.alt;
            ex.cadCurve = t.cad;
            ex.strideCurve = t.stride;
            ex.ctCurve = t.ct;
            ex.vampCurve = t.vamp;
            ex.spdCurve = t.spd;
            if (t.ascent > 0) ex.ascentM = t.ascent;
            if (t.descent > 0) ex.descentM = t.descent;
            if (t.maxSpeed > 0) ex.maxSpeedKmh = t.maxSpeed * 3.6;
            if (t.avgHr > 0) ex.trackAvgHr = t.avgHr;
            if (t.maxHr > 0) ex.trackMaxHr = t.maxHr;
            attached++;
        }
        diag.add("轨迹已解码 → " + attached + " 场");
    }

    private JSONObject obj(Map<String, Object> row) {
        Object v = row.get("value");
        if (!(v instanceof String)) return null;
        try { return new JSONObject((String) v); } catch (Exception e) { return null; }
    }

    private DayHealth day(Map<String, DayHealth> m, long ts) { return dayByDate(m, dateOf(ts)); }

    private DayHealth dayByDate(Map<String, DayHealth> m, String date) {
        DayHealth d = m.get(date);
        if (d == null) { d = new DayHealth(); d.date = date; m.put(date, d); }
        return d;
    }

    private final Map<String, HRAgg> hrMap = new HashMap<String, HRAgg>();
    private final Map<String, SpAgg> spMap = new HashMap<String, SpAgg>();
    private final Map<String, StAgg> stMap = new HashMap<String, StAgg>();
    private final Map<String, HashMap<Long, int[]>> stepMap = new HashMap<String, HashMap<Long, int[]>>();
    private final Map<String, Set<Integer>> standMap = new HashMap<String, Set<Integer>>();

    // 当天分时桶（按日期）：心率/血氧/压力为 sum+cnt，步数为直接累加
    private final Map<String, int[]> hrHSum = new HashMap<String, int[]>(), hrHCnt = new HashMap<String, int[]>();
    private final Map<String, int[]> spHSum = new HashMap<String, int[]>(), spHCnt = new HashMap<String, int[]>();
    private final Map<String, int[]> ssHSum = new HashMap<String, int[]>(), ssHCnt = new HashMap<String, int[]>();
    private final Map<String, int[]> stepH = new HashMap<String, int[]>();

    private static int[] arr(Map<String, int[]> m, String k) {
        int[] a = m.get(k);
        if (a == null) { a = new int[24]; m.put(k, a); }
        return a;
    }

    /** 把某天的小时桶结果写入 DayHealth */
    private void fillSeries(DayHealth d) {
        if (hrHCnt.containsKey(d.date)) d.hrByHour = finish(arr(hrHSum, d.date), arr(hrHCnt, d.date));
        if (spHCnt.containsKey(d.date)) d.spo2ByHour = finish(arr(spHSum, d.date), arr(spHCnt, d.date));
        if (ssHCnt.containsKey(d.date)) d.stressByHour = finish(arr(ssHSum, d.date), arr(ssHCnt, d.date));
        if (stepH.containsKey(d.date)) d.stepsByHour = arr(stepH, d.date).clone();
    }

    private HRAgg hrAgg(String k) { HRAgg a = hrMap.get(k); if (a == null) { a = new HRAgg(); hrMap.put(k, a); } return a; }
    private SpAgg spAgg(String k) { SpAgg a = spMap.get(k); if (a == null) { a = new SpAgg(); spMap.put(k, a); } return a; }
    private StAgg stAgg(String k) { StAgg a = stMap.get(k); if (a == null) { a = new StAgg(); stMap.put(k, a); } return a; }
    private HashMap<Long, int[]> stepAgg(String k) { HashMap<Long, int[]> a = stepMap.get(k); if (a == null) { a = new HashMap<Long, int[]>(); stepMap.put(k, a); } return a; }
    private Set<Integer> standHours(String k) { Set<Integer> a = standMap.get(k); if (a == null) { a = new HashSet<Integer>(); standMap.put(k, a); } return a; }

    private static class HRAgg { double sum; int n; }
    private static class SpAgg { double sum; int n; }
    private static class StAgg { double sum; int n; }
}
