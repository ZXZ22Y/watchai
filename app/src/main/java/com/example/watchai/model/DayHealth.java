/*
 * ============================================================
 * 腕上智析 WatchAI  —  原创作品，请勿去除本声明
 * 原作者：酷安 @想吃薯片zz
 * 开源地址：https://github.com/ZXZ22Y/watchai
 * 本文件为原作者编写，二次修改/分发请保留出处与署名。
 * 溯源标识：WATCHAI-ORIGIN-ByCoolapk-XiangChiShuaiPianZZ-2026
 * ============================================================
 */
package com.example.watchai.model;

/** 某一天的健康汇总 */
public class DayHealth {
    public String date;              // yyyy-MM-dd
    public int steps;
    public int calories;             // 活动消耗 kcal
    public int exerciseMinutes;      // 锻炼分钟
    public int standHours;           // 站立小时
    public int avgHeartRate;
    public int minHeartRate;
    public int maxHeartRate;
    public int spo2;                 // 血氧 %
    public int stress;               // 压力 0-100
    public int weightX10;            // 体重 x10（如 685 = 68.5kg）
    public int bodyFatX10;           // 体脂 x10
    public int muscleX10;            // 肌肉量 x10
    public int trainingLoad;         // 训练负荷
    public SleepRecord sleep;

    // ---- 当天分时序列（小时 0-23，用于二级详情页展示"当天数据"）----
    public int[] hrByHour;           // 每小时心率均值，-1 表示该小时无数据
    public int[] stepsByHour;        // 每小时步数
    public int[] spo2ByHour;         // 每小时血氧均值，-1 无数据
    public int[] stressByHour;       // 每小时压力均值，-1 无数据
}
