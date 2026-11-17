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

import java.util.ArrayList;
import java.util.List;

/** 一场锻炼记录（来自小米运动健康 sport_report 表的 value JSON + 逐点轨迹文件），字段与 mi-fit 一致 */
public class Exercise {
    public String key;          // outdoor_running 等
    public String category;
    public String name;         // 中文名
    public String badge;        // 文字徽章（跑/骑/游…）
    public int colorIdx;        // 徽章配色索引

    public long startSec;
    public long endSec;
    public int durationSec;
    public int distanceM;
    public int calories;
    public int steps;
    public int avgHr;
    public int maxHr;
    public int avgCadence;
    public int maxCadence;
    public double paceSecPerKm; // 秒/公里
    public double ascentM;
    public double descentM;
    public double avgAltM;
    public double minAltM;
    public double maxAltM;
    public double avgStrideCm;  // 平均步幅 cm
    public double avgSpeedKmh;  // 平均速度 km/h
    public double maxSpeedKmh;  // 最大速度 km/h

    // ---- 跑步动态（来自 value JSON，单位与 mi-fit 一致）----
    public double avgGroundContactMs;   // avg_touchdown_duration 平均触地时间 ms
    public double minGroundContactMs;   // min_touchdown_duration
    public double maxGroundContactMs;   // max_contact_time
    public double avgVerticalOscCm;     // avg_vertical_amplitude 平均垂直振幅 cm
    public double minVerticalOscCm;     // min_vertical_amplitude
    public double maxVerticalOscCm;     // max_vertical_amplitude
    public double avgVerticalRatio;     // avg_vertical_stride_ratio 垂直步幅比 %
    public double maxVerticalRatio;     // max_vertical_stride_ratio
    public double totalCalories;        // total_cal

    // ---- 逐点轨迹与曲线（来自 _gps / _record 二进制文件解码，可能为空）----
    public boolean hasTrack = false;
    /** 地图轨迹点 [lat, lng, speed(m/s), dist(m)] */
    public List<double[]> track = new ArrayList<double[]>();
    /** 曲线（等长，抽样约 300 点）：心率 / 配速(秒/km) / 海拔 / 步频 / 步幅 / 触地 / 垂直振幅 / 速度(km/h) */
    public double[] hrCurve, paceCurve, altCurve, cadCurve, strideCurve, ctCurve, vampCurve, spdCurve;
    /** 逐点解码出的汇总（比官方 value 更细） */
    public double trackAscent, trackDescent, trackMaxSpeedKmh, trackAvgHr, trackMaxHr;

    public double distanceKm() { return distanceM / 1000.0; }

    /** mm:ss /公里，仅跑步/步行/骑行等有意义 */
    public String paceText() {
        if (paceSecPerKm < 60 || paceSecPerKm > 3600) return "";
        int t = (int) Math.round(paceSecPerKm);
        return (t / 60) + "'" + String.format("%02d", t % 60) + "\"";
    }

    /** 是否有专业跑步动态数据 */
    public boolean hasRunningDynamics() {
        return avgVerticalOscCm > 0 || avgGroundContactMs > 0 || avgVerticalRatio > 0;
    }
}
