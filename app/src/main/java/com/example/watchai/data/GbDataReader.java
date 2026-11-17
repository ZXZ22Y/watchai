package com.example.watchai.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import com.example.watchai.model.DayHealth;
import com.example.watchai.model.HealthDataSet;
import com.example.watchai.model.SleepRecord;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 从 Gadgetbridge 的 SQLite 数据库读取真实健康数据。
 * 数据库：/data/data/nodomain.freeyourgadget.gadgetbridge/databases/gadgetbridge
 * 表结构来自 Gadgetbridge 源码 GBDaoGenerator（greenDAO 默认表名=类名转大写下划线）。
 * 时间戳为毫秒。
 */
public class GbDataReader {
    public DiagReport diag;

    private static final String GB_PKG = "nodomain.freeyourgadget.gadgetbridge";
    private static final String[] DB_PATHS = {
            "/data/data/" + GB_PKG + "/databases/gadgetbridge",
            "/data/user/0/" + GB_PKG + "/databases/gadgetbridge",
            "/data/data/" + GB_PKG + "/databases/gadgetbridge.db"
    };

    public HealthDataSet read(Context ctx) {
        diag = new DiagReport();
        HealthDataSet out = new HealthDataSet();
        out.source = HealthDataSet.SOURCE_MI_HEALTH;

        RootShell.cleanLeftovers(ctx);
        RootShell sh = new RootShell();
        boolean ok = sh.start(ctx);
        diag.shellOk = ok;
        diag.shellMode = sh.mode();
        diag.shellTrace = sh.trace();
        diag.ok("Root 通道", ok ? sh.mode() : "未取得 root");

        if (!ok) {
            diag.err("数据库", "未取得 root，无法读取 Gadgetbridge 数据库");
            out.message = "未取得 root 权限";
            return out;
        }

        // 1) 定位数据库
        String srcDb = null;
        for (String p : DB_PATHS) {
            long sz = sh.size(p);
            diag.dbScan += p + " -> " + (sz >= 0 ? sz + " 字节" : "不存在") + "\n";
            if (sz > 0) { srcDb = p; break; }
        }
        if (srcDb == null) {
            String found = sh.raw("find /data/data/" + GB_PKG + " /data/user/0/" + GB_PKG
                    + " -type f 2>/dev/null | grep -iE 'gadgetbridge|databases' | head -30");
            diag.dbScan += "\n【find 兜底】\n" + found + "\n";
            for (String line : found.split("\n")) {
                String s = line.trim();
                if ((s.endsWith("/gadgetbridge") || s.endsWith("/gadgetbridge.db")) && sh.size(s) > 0) { srcDb = s; break; }
            }
        }
        if (srcDb == null) {
            diag.err("数据库", "未找到 Gadgetbridge 数据库。请确认已安装 Gadgetbridge 并至少同步过一次。");
            out.message = "未找到 Gadgetbridge 数据库";
            sh.stop();
            return out;
        }
        diag.ok("数据库", "找到 " + srcDb);

        // 2) 复制数据库（含 -wal/-shm 一起复制，避免读到空库）
        byte[] bytes = sh.cat(srcDb);
        // 尝试合并 wal（若有）：简单起见，若主库很小而 wal 很大则提示
        long walSize = sh.size(srcDb + "-wal");
        sh.stop();
        if (bytes == null || bytes.length == 0) {
            diag.err("数据库", "数据库复制失败（命名空间受限？请在 KernelSU 开启全局命名空间）");
            out.message = "数据库复制失败";
            return out;
        }
        if (walSize > 0) diag.no("WAL", "检测到 -wal (" + walSize + " 字节)，数据可能尚未合并入库");
        File local = new File(ctx.getFilesDir(), "gb_copy.db");
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(local);
            fos.write(bytes);
            fos.close();
            diag.ok("复制", bytes.length + " 字节 -> " + local.getName());
        } catch (Exception e) {
            diag.err("复制", e.getMessage() == null ? e.toString() : e.getMessage());
            out.message = "数据库落盘失败";
            return out;
        }

        // 3) SQLite 查询
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(local.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            StringBuilder tables = new StringBuilder();
            Cursor tc = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", null);
            while (tc.moveToNext()) {
                String t = tc.getString(0);
                int rows = -1;
                try {
                    Cursor cc = db.rawQuery("SELECT count(*) FROM \"" + t + "\"", null);
                    if (cc.moveToNext()) rows = cc.getInt(0);
                    cc.close();
                } catch (Exception ignored) { }
                tables.append(t).append(" = ").append(rows).append(" 行\n");
            }
            tc.close();
            diag.tableScan = tables.toString();

            List<DayHealth> days = readDailySummary(db);
            readSleep(db, days);
            out.days = days;
            diag.ok("解析", "日摘要 " + days.size() + " 天");

            if (days.isEmpty()) {
                diag.err("数据", "健康表为空——请在 Gadgetbridge 首页点设备名下方的同步图标，把手表数据拉进来");
                out.message = "数据库为空，请先在 Gadgetbridge 点同步";
            } else {
                out.message = "已读取 Gadgetbridge 真实数据";
            }
        } catch (SQLiteException e) {
            diag.err("SQLite", e.getMessage() == null ? e.toString() : e.getMessage());
            out.message = "数据库解析失败";
        } finally {
            if (db != null) try { db.close(); } catch (Exception ignored) { }
            if (local.exists()) local.delete();
        }
        return out;
    }

    private List<DayHealth> readDailySummary(SQLiteDatabase db) {
        List<DayHealth> list = new ArrayList<DayHealth>();
        String sql = "SELECT TIMESTAMP,STEPS,HR_RESTING,HR_MAX,HR_MIN,HR_AVG,STRESS_AVG,STRESS_MAX,STRESS_MIN,"
                + "SPO2_AVG,SPO2_MAX,SPO2_MIN,STANDING,CALORIES,TRAINING_LOAD_DAY,VITALITY_CURRENT "
                + "FROM XIAOMI_DAILY_SUMMARY_SAMPLE ORDER BY TIMESTAMP ASC";
        Cursor c = null;
        try {
            c = db.rawQuery(sql, null);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            while (c.moveToNext()) {
                DayHealth d = new DayHealth();
                long ts = c.getLong(0);
                d.date = sdf.format(new Date(ts));
                d.steps = getInt(c, 1);
                d.avgHeartRate = getInt(c, 5);
                d.minHeartRate = getInt(c, 4);
                d.maxHeartRate = getInt(c, 3);
                d.stress = getInt(c, 6);
                d.spo2 = getInt(c, 9);
                d.standHours = getInt(c, 12);
                d.calories = getInt(c, 13);
                d.trainingLoad = getInt(c, 14);
                d.exerciseMinutes = getInt(c, 14) > 0 ? (int) (getInt(c, 14) / 10f) : 0; // 训练负荷近似
                list.add(d);
            }
        } catch (Exception e) {
            diag.err("日摘要表", e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            if (c != null) c.close();
        }
        return list;
    }

    private void readSleep(SQLiteDatabase db, List<DayHealth> days) {
        String sql = "SELECT TIMESTAMP,WAKEUP_TIME,TOTAL_DURATION,DEEP_SLEEP_DURATION,"
                + "LIGHT_SLEEP_DURATION,REM_SLEEP_DURATION,AWAKE_DURATION FROM XIAOMI_SLEEP_TIME_SAMPLE ORDER BY TIMESTAMP ASC";
        Cursor c = null;
        try {
            c = db.rawQuery(sql, null);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            while (c.moveToNext()) {
                long ts = c.getLong(0);
                long wake = c.getLong(1);
                int total = getInt(c, 2), deep = getInt(c, 3), light = getInt(c, 4), rem = getInt(c, 5), awake = getInt(c, 6);
                if (total <= 0 && deep + light + rem <= 0) continue;
                SleepRecord s = new SleepRecord();
                long wakeMs = wake > ts ? wake : ts + total * 60000L;
                s.date = sdf.format(new Date(wakeMs));
                s.deepMinutes = deep;
                s.lightMinutes = light;
                s.remMinutes = rem;
                s.awakeMinutes = awake;
                s.bedTimeMs = ts;
                s.wakeTimeMs = wakeMs;
                s.avgHrv = 0; // Gadgetbridge 该表无 HRV，留 0（评分里恢复项按心率算）
                s.avgHeartRate = 0;
                // 匹配到起床日
                for (DayHealth d : days) {
                    if (d.date.equals(s.date)) { d.sleep = s; break; }
                }
            }
        } catch (Exception e) {
            diag.err("睡眠表", e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            if (c != null) c.close();
        }
    }

    private static int getInt(Cursor c, int i) {
        try { return c.isNull(i) ? 0 : c.getInt(i); } catch (Exception e) { return 0; }
    }
}
