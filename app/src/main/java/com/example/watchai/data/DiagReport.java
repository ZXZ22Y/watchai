package com.example.watchai.data;

import java.util.ArrayList;
import java.util.List;

/** 数据读取全流程诊断，直接展示在 App「设备」页，便于定位读不到数据的原因 */
public class DiagReport {
    public final List<String> lines = new ArrayList<String>();
    public boolean shellOk = false;
    public boolean miInstalled = false;
    public boolean dbFound = false;
    public boolean parsed = false;
    public String shellMode = "";
    public String shellTrace = "";
    public String dbPath = "";
    public String dbScan = "";       // 目录扫描原始输出
    public String tableScan = "";    // 各表行数原始输出

    public void add(String s) { lines.add(s); }
    public void ok(String label, String detail) {
        lines.add("[OK] " + label + (detail == null || detail.isEmpty() ? "" : " - " + detail));
    }
    public void no(String label, String detail) {
        lines.add("-- " + label + (detail == null || detail.isEmpty() ? "" : " - " + detail));
    }
    public void err(String label, String detail) {
        lines.add("[X] " + label + (detail == null || detail.isEmpty() ? "" : " - " + detail));
    }

    public String fullText() {
        StringBuilder sb = new StringBuilder();
        sb.append("腕上智析 · 数据读取诊断\n");
        sb.append("========================\n");
        for (String l : lines) sb.append(l).append('\n');
        if (dbScan != null && !dbScan.isEmpty()) {
            sb.append("\n---- 目录扫描原始输出 ----\n").append(dbScan).append('\n');
        }
        if (tableScan != null && !tableScan.isEmpty()) {
            sb.append("\n---- 各表行数 ----\n").append(tableScan).append('\n');
        }
        return sb.toString();
    }
}
