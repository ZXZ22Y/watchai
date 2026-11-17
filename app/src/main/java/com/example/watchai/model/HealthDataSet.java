package com.example.watchai.model;

import java.util.List;

/** 数据集：包含来源标记 */
public class HealthDataSet {
    public static final int SOURCE_MI_HEALTH = 1;

    public List<DayHealth> days;
    public List<Exercise> exercises = new java.util.ArrayList<Exercise>();  // 锻炼记录，最新在前
    public int source;               // SOURCE_*
    public String message;           // 读取状态说明

    public HealthDataSet() { this.days = new java.util.ArrayList<DayHealth>(); }

    public HealthDataSet(List<DayHealth> days, int source, String message) {
        this.days = days;
        this.source = source;
        this.message = message;
    }
}
