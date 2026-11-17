package com.example.watchai.data;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * 读取小米运动健康 / Mi Fitness 的同步日志并提取 AuthKey。
 * 日志目录位于 /sdcard/Android/data/<pkg>/files/log/ —— 属外部存储，
 * 即使 KSU 挂载命名空间受限，root 依然能读（这正是绕开之前 /data/data 卡点的关键）。
 */
public class TokenReader {
    public Result last;

    public static class Result {
        public boolean rootOk;
        public String shellMode = "";
        public String shellTrace = "";
        public String scan = "";          // 原始扫描输出，供诊断
        public List<TokenExtractor.Hit> hits = new ArrayList<TokenExtractor.Hit>();
        public String error = "";
    }

    // 已知的运动健康包名（国行 + 国际版 + 旧版）
    private static final String[] PKGS = {
            "com.mi.health",            // 国行「小米运动健康」
            "com.xiaomi.wearable",      // 国际版 Mi Fitness / Xiaomi Wear
            "com.xiaomi.hm.health",     // 旧版 Zepp Life / Mi Fit
            "com.huami.watch.hmwatchmanager" // 旧版 Amazfit
    };

    public Result read(Context ctx) {
        Result r = new Result();
        RootShell.cleanLeftovers(ctx);
        RootShell sh = new RootShell();
        boolean ok = sh.start(ctx);
        r.rootOk = ok;
        r.shellMode = sh.mode();
        r.shellTrace = sh.trace();

        if (!ok) {
            r.error = "未取得 root 权限。请在 KernelSU 管理器里给本 App 授权后重试。";
            return r;
        }

        try {
            // 1) 扫描所有候选包 + 全盘 find 兜底，找日志文件
            StringBuilder scan = new StringBuilder();
            List<String> logFiles = new ArrayList<String>();
            // /sdcard 路径 + /storage/emulated/0 路径都试
            String[] roots = {"/sdcard/Android/data", "/storage/emulated/0/Android/data"};
            for (String pkg : PKGS) {
                for (String root : roots) {
                    String dir = root + "/" + pkg + "/files/log";
                    String listing = sh.raw("ls -1 '" + dir + "' 2>/dev/null");
                    if (listing != null && listing.length() > 0) {
                        scan.append("【").append(dir).append("】\n").append(listing).append('\n');
                        for (String line : listing.split("\n")) {
                            String f = line.trim();
                            if (f.toLowerCase().endsWith(".log")) logFiles.add(dir + "/" + f);
                        }
                    }
                }
            }
            // 全盘 find 兜底（防止包名/路径不在已知列表）
            String found = sh.raw("find /sdcard/Android/data /storage/emulated/0/Android/data "
                    + "-type f -name '*.log' 2>/dev/null | grep -iE 'xiaomi|mi\\.health|wearable|mifit|huami|health|fit' | head -40");
            if (found != null && found.length() > 0) {
                scan.append("【全盘 find 兜底】\n").append(found).append('\n');
                for (String line : found.split("\n")) {
                    String f = line.trim();
                    if (f.length() > 0 && !logFiles.contains(f)) logFiles.add(f);
                }
            }
            r.scan = scan.toString();

            if (logFiles.isEmpty()) {
                r.error = "未找到运动健康的日志文件。请确认：\n"
                        + "① 已安装「小米运动健康」或「Mi Fitness」\n"
                        + "② 已登录账号并让手表同步过一次数据\n"
                        + "③ 应用产生过同步日志（可在手表同步后重试）";
                return r;
            }

            // 2) 读日志原文（每个文件最多 2MB，避免过大）
            String[][] logs = new String[logFiles.size()][2];
            for (int i = 0; i < logFiles.size(); i++) {
                String path = logFiles.get(i);
                byte[] data = sh.cat(path);
                String name = path.substring(path.lastIndexOf('/') + 1);
                String text = data == null ? "" : new String(data, "UTF-8");
                if (text.length() > 2_000_000) text = text.substring(text.length() - 2_000_000);
                logs[i][0] = name;
                logs[i][1] = text;
            }

            // 3) 提取 token
            r.hits = TokenExtractor.extract(logs);
            if (r.hits.isEmpty()) {
                r.error = "读到了 " + logFiles.size() + " 个日志文件，但没找到 32 位密钥。\n"
                        + "请先让手表与运动健康完整同步一次，再重试。";
            }
        } catch (Exception e) {
            r.error = "读取异常: " + (e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            sh.stop();
        }
        return r;
    }
}
