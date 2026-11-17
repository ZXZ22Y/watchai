package com.example.watchai.model;

/** 一晚睡眠记录 */
public class SleepRecord {
    public String date;          // yyyy-MM-dd（起床日）
    public int deepMinutes;      // 深睡
    public int lightMinutes;     // 浅睡
    public int remMinutes;       // 快速眼动
    public int awakeMinutes;     // 清醒
    public int avgHeartRate;     // 平均心率
    public int avgHrv;           // 平均 HRV
    public long bedTimeMs;       // 入睡时间戳
    public long wakeTimeMs;      // 起床时间戳

    public int totalMinutes() {
        return deepMinutes + lightMinutes + remMinutes;
    }

    public float sleepHours() {
        return totalMinutes() / 60f;
    }

    public boolean hasData() {
        return totalMinutes() > 0;
    }
}
