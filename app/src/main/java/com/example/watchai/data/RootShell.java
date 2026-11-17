package com.example.watchai.data;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;

/**
 * 常驻 root shell（严格照搬 mi-fit 验证过的方案）：
 * 用 su 起一个 sh，通过 stdin/stdout 下发指令，让 root 把需要的字节片段
 * dd 到 App 自己的 cache，App 再直接读回。不用 httpd、不复制整库。
 *
 * 关键：必须真正能看到 /data/data/com.mi.health 才算成功。
 * 之前"只要 uid=0 就算成功"的兜底会造成"给了 root 却读到全 0"的假象，已删除。
 */
public class RootShell {
    private Process p;
    private Writer in;
    private BufferedReader out;
    private File tmp;
    private boolean ok = false;
    private String mode = "";
    /** 启动过程逐条记录，供诊断面板展示 */
    public final StringBuilder trace = new StringBuilder();

    public static void cleanLeftovers(Context ctx) {
        File d = new File(ctx.getFilesDir(), "cache");
        for (String n : new String[]{"slice.bin", "pages.bin", "cat.bin"}) {
            File real = new File(d, n);
            if (real.exists()) real.delete();
        }
    }

    public synchronized boolean start(Context ctx) {
        if (ok) return true;
        try {
            tmp = new File(ctx.getFilesDir(), "cache");
            if (!tmp.exists()) tmp.mkdirs();
        } catch (Exception e) {
            trace.append("[X] 无法建立缓存目录: ").append(e.getMessage()).append('\n');
            return false;
        }
        trace.setLength(0);

        // 与 mi-fit 完全一致的候选顺序，并补充 KernelSU 的 su 绝对路径。
        // 判定标准只有一个：能否 ls 到 com.mi.health 私有目录。
        String[][] cands = new String[][]{
                {"su", "--mount-master", "-c", "sh"},
                {"su", "-c", "/system/bin/nsenter -t 1 -m -- sh"},
                {"su", "-c", "sh"},
                {"/data/adb/ksu/bin/su", "--mount-master", "-c", "sh"},
                {"/data/adb/ksu/bin/su", "-c", "sh"},
                {"su", "-M", "-c", "sh"}
        };
        for (String[] c : cands) {
            try {
                close();
                p = new ProcessBuilder(c).redirectErrorStream(false).start();
                in = new OutputStreamWriter(p.getOutputStream(), "UTF-8");
                out = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                String probe = cmd("id; ls /data/data/com.mi.health >/dev/null 2>&1 && echo :ok:");
                String id = probe == null ? "?" : firstLineContaining(probe, "uid=");
                boolean sees = probe != null && probe.contains(":ok:");
                trace.append("[--] ").append(join(c)).append("  ").append(id)
                        .append("  可见com.mi.health=").append(sees).append('\n');
                if (sees) {
                    ok = true;
                    mode = join(c);
                    trace.append("[OK] root 通道可用: ").append(mode).append('\n');
                    return true;
                }
            } catch (Exception e) {
                trace.append("[--] ").append(join(c)).append(" -> ")
                        .append(e.getMessage() == null ? e.toString() : e.getMessage()).append('\n');
            }
        }
        close();
        ok = false;
        trace.append("[X] 已取得 root 但都看不到 /data/data/com.mi.health。\n");
        trace.append("[X] KernelSU 用户：请在管理器里给本 App 开启「全局命名空间 / 挂载命名空间」。\n");
        trace.append("[X] 并确认小米运动健康已安装且其数据目录非空。\n");
        return false;
    }

    private static String firstLineContaining(String s, String key) {
        for (String l : s.split("\n")) if (l.contains(key)) return l.trim();
        return "uid=?";
    }

    private static String join(String[] c) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < c.length; i++) { if (i > 0) sb.append(' '); sb.append(c[i]); }
        return sb.toString();
    }

    public String mode() { return mode; }
    public String trace() { return trace.toString(); }
    public boolean ready() { return ok; }

    /** 直接跑一条命令并返回输出（诊断用） */
    public synchronized String raw(String c) {
        String r = cmd(c);
        return r == null ? "" : r.trim();
    }

    private String cmd(String c) {
        try {
            in.write(c + "\n");
            in.write("echo :END:\n");
            in.flush();
            StringBuilder sb = new StringBuilder();
            String line;
            int guard = 0;
            while ((line = out.readLine()) != null) {
                if (line.contains(":END:")) break;
                sb.append(line).append('\n');
                if (++guard > 8000) break;
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 让 root 把 [off, off+len) 写到 cache/slice.bin，然后读回来 */
    public synchronized byte[] slice(String path, long off, int len) throws Exception {
        if (!ok) throw new Exception("root shell 未就绪");
        File f = new File(tmp, "slice.bin");
        long bs, skip, cnt;
        if (off % 4096 == 0 && len % 4096 == 0) { bs = 4096; skip = off / 4096; cnt = len / 4096; }
        else if (off % 512 == 0 && len % 512 == 0) { bs = 512; skip = off / 512; cnt = len / 512; }
        else { bs = 1; skip = off; cnt = len; }
        if (f.exists()) f.delete();
        String out = cmd("/system/bin/dd if='" + path + "' of='" + f.getAbsolutePath() + "' bs=" + bs +
                " skip=" + skip + " count=" + cnt + " 2>&1; echo rc=$?");
        if (!f.exists() || f.length() == 0) {
            throw new Exception("dd 失败 @" + off + " [" + String.valueOf(out).replace('\n', ' ') + "]");
        }
        byte[] b = new byte[(int) f.length()];
        java.io.FileInputStream fis = new java.io.FileInputStream(f);
        int got = 0;
        while (got < b.length) {
            int n = fis.read(b, got, b.length - got);
            if (n <= 0) break;
            got += n;
        }
        fis.close();
        f.delete();
        if (got < len) {
            byte[] shorter = new byte[got];
            System.arraycopy(b, 0, shorter, 0, got);
            return shorter;
        }
        return b;
    }

    /** 一次 shell 往返读多个页 */
    public synchronized byte[] pages(String path, long[] pgs, int pageSize) throws Exception {
        if (!ok) throw new Exception("root shell 未就绪");
        if (pgs == null || pgs.length == 0) return new byte[0];
        File f = new File(tmp, "pages.bin");
        if (f.exists()) f.delete();
        StringBuilder sb = new StringBuilder(64 * pgs.length + 32);
        sb.append(": > '").append(f.getAbsolutePath()).append("'");
        for (long pg : pgs) {
            if (pg <= 0) continue;
            sb.append("; /system/bin/dd if='").append(path).append("' bs=").append(pageSize)
                    .append(" skip=").append(pg - 1).append(" count=1 >> '")
                    .append(f.getAbsolutePath()).append("' 2>/dev/null");
        }
        cmd(sb.toString());
        if (!f.exists() || f.length() == 0) return new byte[0];
        byte[] b = new byte[(int) f.length()];
        java.io.FileInputStream fis = new java.io.FileInputStream(f);
        int got = 0;
        while (got < b.length) {
            int n = fis.read(b, got, b.length - got);
            if (n <= 0) break;
            got += n;
        }
        fis.close();
        f.delete();
        return b;
    }

    public synchronized byte[] cat(String path) {
        if (!ok) return null;
        try {
            File f = new File(tmp, "cat.bin");
            if (f.exists()) f.delete();
            cmd("cat '" + path + "' > '" + f.getAbsolutePath() + "' 2>/dev/null");
            if (!f.exists() || f.length() == 0) return null;
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            int got = 0;
            while (got < b.length) {
                int n = fis.read(b, got, b.length - got);
                if (n <= 0) break;
                got += n;
            }
            fis.close();
            f.delete();
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized long size(String path) {
        if (!ok) return -1;
        String r = cmd("wc -c < '" + path + "' 2>/dev/null");
        if (r == null) return -1;
        r = r.trim();
        try { return Long.parseLong(r.split("\\s+")[0]); } catch (Exception e) { return -1; }
    }

    public synchronized String find(String args) {
        if (!ok) return "";
        String r = cmd("find " + args + " 2>/dev/null");
        return r == null ? "" : r;
    }

    public synchronized void stop() {
        close();
        ok = false;
    }

    private void close() {
        try { if (in != null) in.write("exit\n"); } catch (Exception ignored) { }
        try { if (in != null) in.flush(); } catch (Exception ignored) { }
        try { if (p != null) p.destroy(); } catch (Exception ignored) { }
        p = null; in = null; out = null;
    }
}
