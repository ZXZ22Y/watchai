package com.example.watchai.data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从「小米运动健康 / Mi Fitness」的同步日志里提取蓝牙认证密钥（AuthKey / token）。
 *
 * 官方文档给出的提取规则：
 *   grep -Eo '(encryptKey|token|authKey|huamiAuthKey)[" :=]+[[:xdigit:]]{32}' \
 *     /sdcard/Android/data/{com.xiaomi.wearable,com.mi.health}/files/log/*.log
 * 官方强调：日志里可能有多个 key，要用「最后一个（最靠近文件末尾）」；不行再试其他。
 *
 * 本类额外记录每个 key 的上下文（附近的设备名/model/sn），帮助用户认出哪个是自己的手表。
 * 纯字符串处理，可在无 Android 环境下单元测试。
 */
public final class TokenExtractor {

    // 字段名 + 分隔符 + 32 位十六进制
    private static final Pattern KV = Pattern.compile(
            "(encryptKey|authKey|huamiAuthKey|token)[\"'\\s:=]+([0-9a-fA-F]{32})");
    // 兜底：裸的 32 位 hex
    private static final Pattern HEX32 = Pattern.compile("(?i)\\b[0-9a-f]{32}\\b");
    // 上下文里能标识设备的关键词
    private static final Pattern DEVICE_HINT = Pattern.compile(
            "(?i)(deviceName|model|productId|deviceId|sn|device_type|mac|name)[\"':=\\s]+([A-Za-z0-9_/,.: -]{2,40}?)(?=[,\"}\\s]|$)");

    /** 一条候选结果 */
    public static class Hit {
        public final String field;   // 命中的字段名
        public final String value;   // 32 位十六进制小写
        public final String source;  // 来源日志文件
        public String context = "";  // 附近设备信息（辅助识别）
        public Hit(String f, String v, String s) { field = f; value = v; source = s; }
    }

    /**
     * 从一批日志文本中提取候选 key。按出现顺序，按 value 去重。
     * 最后一个出现的排在最前（官方推荐优先尝试）。
     */
    public static List<Hit> extract(String[][] logs) {
        Set<String> seen = new LinkedHashSet<String>();
        List<Hit> out = new ArrayList<Hit>();
        if (logs == null) return out;
        for (String[] log : logs) {
            if (log == null || log.length < 2 || log[1] == null) continue;
            String name = log[0] == null ? "" : log[0];
            String text = log[1];
            Matcher m = KV.matcher(text);
            while (m.find()) {
                String field = m.group(1);
                String val = m.group(2).toLowerCase();
                if (seen.add(val)) {
                    Hit h = new Hit(field, val, name);
                    h.context = context(text, m.start(), m.end());
                    out.add(h);
                } else {
                    // 同一个 key 再次出现：更新为更靠后的上下文（更接近末尾、更可能是当前设备）
                    for (Hit h : out) if (h.value.equals(val)) h.context = context(text, m.start(), m.end());
                }
            }
        }
        if (out.isEmpty()) {
            for (String[] log : logs) {
                if (log == null || log.length < 2 || log[1] == null) continue;
                String name = log[0] == null ? "" : log[0];
                Matcher m = HEX32.matcher(log[1]);
                while (m.find()) {
                    String val = m.group().toLowerCase();
                    if (seen.add(val)) {
                        Hit h = new Hit("裸32位hex", val, name);
                        h.context = context(log[1], m.start(), m.end());
                        out.add(h);
                    }
                }
            }
        }
        return out;
    }

    /** 抓取命中位置前后各 180 字符里的设备标识，拼成一行提示 */
    private static String context(String text, int start, int end) {
        // 只看命中点往前 140 字符（设备信息通常和 key 在同一条记录里、紧邻其前）
        int a = Math.max(0, start - 140);
        String win = text.substring(a, Math.min(text.length(), end + 20))
                .replace("\n", " ").replace("\r", " ");
        Matcher m = DEVICE_HINT.matcher(win);
        StringBuilder sb = new StringBuilder();
        while (m.find() && sb.length() < 100) {
            String seg = m.group(1) + "=" + m.group(2).trim();
            if (sb.length() > 0) sb.append(" · ");
            sb.append(seg);
        }
        return sb.toString();
    }

    /** 生成 Gadgetbridge 需要的输入格式：0x + key */
    public static String toGadgetbridge(String key) {
        if (key == null) return "";
        String k = key.trim().toLowerCase();
        if (k.startsWith("0x")) return k;
        return "0x" + k;
    }
}
