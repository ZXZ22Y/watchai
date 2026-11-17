/*
 * ============================================================
 * 腕上智析 WatchAI  —  原创作品，请勿去除本声明
 * 原作者：酷安 @想吃薯片zz
 * 开源地址：https://github.com/ZXZ22Y/watchai
 * 本文件为原作者编写，二次修改/分发请保留出处与署名。
 * 溯源标识：WATCHAI-ORIGIN-ByCoolapk-XiangChiShuaiPianZZ-2026
 * ============================================================
 */
package com.example.watchai;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.example.watchai.data.CloudAI;
import com.example.watchai.data.DataJsonBuilder;
import com.example.watchai.data.DiagReport;
import com.example.watchai.data.MiHealthReader;
import com.example.watchai.data.WorkoutExporter;
import com.example.watchai.model.Exercise;
import com.example.watchai.model.HealthDataSet;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.List;

/** 主容器：WebView 加载 Apple 风格界面，后台用 root 从「小米运动健康」私有库读真实数据 */
public class MainActivity extends Activity {

    /** 原创溯源构建指纹：二改者抹掉注释后仍可从 dex 中检索到原始出处 */
    public static final String ORIGIN_FINGERPRINT =
            "WATCHAI-ORIGIN-ByCoolapk-XiangChiShuaiPianZZ-2026|github.com/ZXZ22Y/watchai|酷安@想吃薯片zz";

    private WebView web;
    private String lastDataJson = "null";
    private String lastDiagJson = "null";
    private long lastUpdateMs = 0L;
    /** 完整原始锻炼对象（未降采样），专供导出使用，保证数据零丢失 */
    private volatile List<Exercise> rawExercises = null;

    private volatile boolean loading = false;

    @Override
    protected void onResume() {
        super.onResume();
        applySystemBars();
        pushTheme();
        // 每次点开 App（进入前台）自动同步一次真实数据；不做后台轮询
        loadAsync();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applySystemBars();
        pushTheme();
    }

    /** 接管系统返回键（含手势导航）：先在 App 内逐级退回，已在主页才真正退出 */
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("javascript:window.__canGoBack?window.__canGoBack():false", value -> {
            boolean canBack = "true".equals(value);
            if (canBack) {
                web.evaluateJavascript("javascript:window.__doBack&&window.__doBack()", null);
            } else {
                MainActivity.super.onBackPressed();
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applySystemBars();

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.setBackgroundColor(isNight() ? Color.rgb(0, 0, 0) : Color.rgb(0xf5, 0xf5, 0xf7));
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) { pushTheme(); push(); }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        // 先载入上次真实读取的缓存，实现秒开；onPageFinished 会 push 缓存
        loadCache();
        web.loadUrl("file:///android_asset/app/app.html");
        // 首次读取交给 onResume 统一触发，避免重复
    }

    /** 从本地缓存恢复上次真实读取的数据（不做任何伪造） */
    private void loadCache() {
        SharedPreferences sp = getSharedPreferences("watchai_cache", MODE_PRIVATE);
        lastDataJson = sp.getString("data", "null");
        lastDiagJson = sp.getString("diag", "null");
        lastUpdateMs = sp.getLong("ts", 0L);
    }

    /** 保存本次真实读取结果到本地缓存 */
    private void saveCache() {
        try {
            SharedPreferences sp = getSharedPreferences("watchai_cache", MODE_PRIVATE);
            long ts = System.currentTimeMillis();
            lastUpdateMs = ts;
            sp.edit().putString("data", lastDataJson)
                    .putString("diag", lastDiagJson)
                    .putLong("ts", ts).apply();
        } catch (Exception ignored) { }
    }

    private class Bridge {
        @JavascriptInterface
        public void reload() { loadAsync(); }

        /** 手动立即同步：直接重读数据源并刷新界面 */
        @JavascriptInterface
        public void syncNow() { runOnUiThread(() -> loadAsync()); }

        @JavascriptInterface
        public void copyDiag() {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("诊断报告", lastDiagJson.replace("\\n", "\n")));
                Toast.makeText(MainActivity.this, "诊断报告已复制", Toast.LENGTH_SHORT).show();
            });
        }

        /** 打开小米运动健康（先在那边同步一次手表数据） */
        @JavascriptInterface
        public void openMiHealth() {
            runOnUiThread(() -> {
                String[] pkgs = {"com.mi.health", "com.xiaomi.wearable"};
                for (String p : pkgs) {
                    try {
                        Intent i = getPackageManager().getLaunchIntentForPackage(p);
                        if (i != null) { startActivity(i); return; }
                    } catch (Exception ignored) { }
                }
                Toast.makeText(MainActivity.this, "未检测到小米运动健康，请先安装", Toast.LENGTH_LONG).show();
            });
        }

        /** 打开本 App 的系统设置页（KernelSU/Magisk 授权入口） */
        @JavascriptInterface
        public void openAppSettings() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "无法打开设置页", Toast.LENGTH_SHORT).show();
                }
            });
        }

        /**
         * 导出单场运动（佳明 Garmin Connect / Strava / 两步路 / 行者可识别）。
         * 优先用【完整原始 Exercise 对象】生成，保证轨迹点、曲线、所有字段一个不落；
         * format: "tcx"=佳明原生 / "gpx"=通用 / "json"=全量原始数据。
         * @param index 锻炼在列表中的序号（对应前端 exercises()[i]）
         */
        @JavascriptInterface
        public void exportWorkout(final int index, final String format) {
            new Thread(() -> {
                String toast;
                try {
                    List<Exercise> exs = rawExercises;
                    if (exs == null || index < 0 || index >= exs.size()) {
                        toast = "数据尚未就绪，请先同步后重试";
                    } else {
                        Exercise ex = exs.get(index);
                        JSONObject e = WorkoutExporter.fromExercise(ex); // 完整对象，零降采样
                        String fmt = format == null ? "tcx" : format.toLowerCase(java.util.Locale.US);
                        String content, ext, mime;
                        if ("json".equals(fmt)) {
                            content = WorkoutExporter.toRawJson(ex); ext = "json"; mime = "application/json";
                        } else if ("gpx".equals(fmt)) {
                            content = WorkoutExporter.toGpx(e); ext = "gpx"; mime = "application/gpx+xml";
                        } else {
                            content = WorkoutExporter.toTcx(e); ext = "tcx"; mime = "application/vnd.garmin.tcx+xml";
                        }
                        String fileName = WorkoutExporter.fileName(e, ext, ex.startSec);
                        Uri uri = writeExport(fileName, content.getBytes("UTF-8"), mime);
                        if (uri != null) {
                            shareFile(uri, mime);
                            toast = "已导出全量数据：" + fileName;
                        } else {
                            toast = "导出失败，请重试";
                        }
                    }
                } catch (Exception ex2) {
                    toast = "导出失败：" + (ex2.getMessage() == null ? "数据异常" : ex2.getMessage());
                }
                final String t = toast;
                runOnUiThread(() -> Toast.makeText(MainActivity.this, t, Toast.LENGTH_LONG).show());
            }).start();
        }

        /** 写导出文件：API29+ 用 MediaStore 落到公共 Download；低版本写外部私有目录。返回可分享 Uri。 */
        private Uri writeExport(String fileName, byte[] bytes, String mime) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.content.ContentValues cv = new android.content.ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Files.getContentUri("external"), cv);
                    if (uri != null) {
                        OutputStream os = getContentResolver().openOutputStream(uri);
                        if (os != null) { os.write(bytes); os.flush(); os.close(); return uri; }
                    }
                }
            } catch (Exception ignored) { }
            // 回退：应用外部私有目录 Download/
            try {
                File dir = new File(getExternalFilesDir(null), "Download");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, fileName);
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(bytes); fos.flush(); fos.close();
                try {
                    android.content.ContentValues cv = new android.content.ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    return MediaStore.Files.getContentUri("external");
                } catch (Exception ignored) { }
                return Uri.fromFile(f);
            } catch (Exception e) { return null; }
        }

        /** 系统分享导出文件（可发给佳明/微信/网盘，或另存）。 */
        private void shareFile(Uri uri, String mime) {
            runOnUiThread(() -> {
                try {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType(mime);
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "导出运动文件"));
                } catch (Exception ignored) { }
            });
        }

        /** 读取联网 AI 配置，返回 JSON 字符串 */
        @JavascriptInterface
        public String getAiConfig() {
            SharedPreferences sp = getSharedPreferences("watchai_ai", MODE_PRIVATE);
            JSONObject o = new JSONObject();
            try {
                o.put("endpoint", sp.getString("endpoint", CloudAI.FREE_ENDPOINT));
                o.put("model", sp.getString("model", "free"));
                o.put("key", sp.getString("key", ""));
            } catch (Exception ignored) { }
            return o.toString();
        }

        /** 逆地理编码：用系统 Geocoder 把轨迹起点坐标转成详细地址（免费、无需 key），结果回调 window.__geoResult */
        @JavascriptInterface
        public void reverseGeocode(final double lat, final double lng) {
            new Thread(() -> {
                String addr = "";
                try {
                    android.location.Geocoder geocoder = new android.location.Geocoder(
                            MainActivity.this, java.util.Locale.CHINA);
                    java.util.List<android.location.Address> list = geocoder.getFromLocation(lat, lng, 1);
                    if (list != null && !list.isEmpty()) {
                        android.location.Address a = list.get(0);
                        StringBuilder sb = new StringBuilder();
                        String country = a.getCountryName();
                        String province = a.getAdminArea();
                        String city = a.getLocality();
                        if (city == null || city.isEmpty()) city = a.getSubAdminArea();
                        String district = a.getSubLocality();
                        String street = a.getThoroughfare();
                        String feature = a.getFeatureName();
                        // 由中国到小地点逐级拼接，去掉重复项
                        String[] parts = {country, province, city, district, street, feature};
                        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<String>();
                        for (String p : parts) {
                            if (p == null || p.trim().isEmpty()) continue;
                            String q = p.trim();
                            boolean dup = false;
                            for (String s : seen) if (s.equals(q) || q.contains(s) && q.length() - s.length() <= 2) { dup = true; break; }
                            if (!dup) seen.add(q);
                        }
                        for (String s : seen) { if (sb.length() > 0) sb.append(" "); sb.append(s); }
                        addr = sb.toString();
                    }
                } catch (Exception e) {
                    addr = "";
                }
                final String res = addr == null ? "" : addr;
                runOnUiThread(() -> web.evaluateJavascript(
                        "javascript:window.__geoResult&&window.__geoResult(" + JSONObject.quote(res) + ")", null));
            }).start();
        }

        /** 保存联网 AI 配置 */
        @JavascriptInterface
        public void setAiConfig(String endpoint, String model, String key) {
            SharedPreferences sp = getSharedPreferences("watchai_ai", MODE_PRIVATE);
            sp.edit().putString("endpoint", endpoint == null ? "" : endpoint.trim())
                    .putString("model", model == null ? "" : model.trim())
                    .putString("key", key == null ? "" : key.trim()).apply();
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "AI 配置已保存", Toast.LENGTH_SHORT).show());
        }

        /** 读取身体资料（年龄/身高/体重/性别/静息心率），返回 JSON 字符串 */
        @JavascriptInterface
        public String getProfile() {
            SharedPreferences sp = getSharedPreferences("watchai_profile", MODE_PRIVATE);
            JSONObject o = new JSONObject();
            try {
                o.put("age", sp.getInt("age", 0));
                o.put("height", sp.getInt("height", 0));
                o.put("weight", sp.getFloat("weight", 0f));
                o.put("gender", sp.getString("gender", "male"));
                o.put("restHr", sp.getInt("restHr", 0));
            } catch (Exception ignored) { }
            return o.toString();
        }

        /** 保存身体资料 */
        @JavascriptInterface
        public void setProfile(int age, int height, float weight, String gender, int restHr) {
            SharedPreferences sp = getSharedPreferences("watchai_profile", MODE_PRIVATE);
            sp.edit().putInt("age", age).putInt("height", height).putFloat("weight", weight)
                    .putString("gender", gender == null ? "male" : gender).putInt("restHr", restHr).apply();
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "身体资料已保存，正在更新指标", Toast.LENGTH_SHORT).show();
                loadAsync(); // 用新身体资料重算 VO2max 等指标
            });
        }

        /** AI 跑步教练：基于近 5 次真实跑步 + 身体资料，分析并给出「下一次怎么跑」的具体方案 */
        @JavascriptInterface
        public void aiRunPlan() {
            new Thread(() -> {
                String result;
                try {
                    SharedPreferences asp = getSharedPreferences("watchai_ai", MODE_PRIVATE);
                    String endpoint = asp.getString("endpoint", CloudAI.FREE_ENDPOINT);
                    String model = asp.getString("model", "free");
                    String key = asp.getString("key", "");
                    SharedPreferences psp = getSharedPreferences("watchai_profile", MODE_PRIVATE);
                    JSONObject prof = new JSONObject();
                    prof.put("age", psp.getInt("age", 0));
                    prof.put("height", psp.getInt("height", 0));
                    prof.put("weight", psp.getFloat("weight", 0f));
                    prof.put("gender", psp.getString("gender", "male"));
                    prof.put("restHr", psp.getInt("restHr", 0));
                    String system = "你是一位资深跑步教练兼运动生理学家，擅长用「近期训练负荷 + 身体恢复 + 个体差异」制定下一次训练方案。"
                            + "用户从手表真实采集的近 5 次跑步数据与身体资料已在下方给出。\n"
                            + "请严格按以下五部分输出，每部分用一行「【标题】」开头，标题后换行写内容：\n"
                            + "【近五次解读】概括这 5 次跑步的距离、配速、心率趋势，指出进步或停滞的信号，必须引用具体数值。\n"
                            + "【恢复状态】结合昨天的准备度、睡眠、HRV 与睡眠债，判断今天身体是否适合跑步、适合多大强度。\n"
                            + "【下一次目标】给出明确的距离、目标配速区间、目标心率区间（若给了年龄/静息心率就用储备心率法估算），并说明为什么这样定。\n"
                            + "【怎么跑】给出热身、主体、放松的具体安排，配速节奏（匀速/间歇/节奏跑）与步频建议。\n"
                            + "【风险与调整】指出需要留意的地方（如连续高强度、恢复不足、天气/场地），以及什么情况下应降低强度或改休息。\n"
                            + "要求：全部基于给出的数据，引用具体数值；建议具体可执行；考虑用户的年龄/体重/性别差异；"
                            + "用简短段落，不要用 markdown 的 # 或 ** 符号。";
                    String user = "近五次跑步与身体资料（真实采集）：\n"
                            + CloudAI.runDigest(lastDataJson, prof.toString());
                    result = CloudAI.smartChat(endpoint, key, model, system, user);
                } catch (Exception e) {
                    result = "【联网分析失败】" + (e.getMessage() == null ? e.toString() : e.getMessage())
                            + "\n\n免费通道可能有临时限流，请稍后重试；或在 API 设置里填写自己的 Key。";
                }
                final String res = result;
                runOnUiThread(() -> web.evaluateJavascript(
                        "javascript:window.__aiRunResult&&window.__aiRunResult(" + JSONObject.quote(res) + ")", null));
            }).start();
        }

        /** 联网深度报告：无需用户输入，自动基于 App 内全部真实数据生成比本地更详细的解读；结果通过 window.__aiResult 回调 */
        @JavascriptInterface
        public void aiAnalyze(String question) {
            new Thread(() -> {
                String result;
                try {
                    SharedPreferences sp = getSharedPreferences("watchai_ai", MODE_PRIVATE);
                    String endpoint = sp.getString("endpoint", CloudAI.FREE_ENDPOINT);
                    String model = sp.getString("model", "free");
                    String key = sp.getString("key", "");
                    String digest = CloudAI.digest(lastDataJson);
                    String system = "你是一位资深运动医学、睡眠科学与运动表现教练，正在为用户撰写一份「个人健康深度报告」。"
                            + "用户从智能手表真实采集的数据已在下方给出，App 内只能展示基础数值，你要基于这些数值做更进一步、更详细的专业解读——"
                            + "发现数据之间的关联、趋势和意义，给出 App 无法给出的洞察与可执行的方案。\n"
                            + "请严格按以下六个部分输出，每部分用一行「【标题】」开头，标题后换行写内容：\n"
                            + "【总体结论】用 3-4 句概括当前身体状态，点明最值得关注的一件事。\n"
                            + "【睡眠与恢复】结合睡眠时长、深睡/REM 占比、睡眠效率、睡眠债、作息规律与 HRV 相对个人基线的恢复情况，深入解读睡得怎么样、身体恢复是否充分。\n"
                            + "【今日状态】解释今天的准备度为什么高或低，前一天的运动/疲劳是否影响恢复，今天适合什么强度。\n"
                            + "【运动表现】结合锻炼记录的距离、配速、心率、跑步动态（垂直振幅/触地时间/步频等）解读训练质量与进步空间。\n"
                            + "【风险提示】指出数据里任何异常、持续偏低/偏高的指标，以及可能的健康隐患，语气客观不过度吓人。\n"
                            + "【行动建议】给出未来 3-7 天具体到「做什么、多久、什么时候」的可执行计划。\n"
                            + "要求：必须引用数据里的具体数值（如时长、百分比、心率、HRV 等）；解释数值背后的原因与关联，而不只是复述数字；"
                            + "语气专业、有温度，像私人教练写给学员的报告；用简短段落，不要使用 markdown 的 # 或 ** 符号。";
                    SharedPreferences psp = getSharedPreferences("watchai_profile", MODE_PRIVATE);
                    String profTxt = "";
                    int page = psp.getInt("age", 0), ph = psp.getInt("height", 0), prhr = psp.getInt("restHr", 0);
                    float pw = psp.getFloat("weight", 0f);
                    if (page > 0 || ph > 0 || pw > 0) {
                        profTxt = "\n【身体资料】年龄" + (page > 0 ? page + "岁" : "未提供")
                                + "，身高" + (ph > 0 ? ph + "cm" : "未提供")
                                + "，体重" + (pw > 0 ? String.format("%.1f", pw) + "kg" : "未提供")
                                + "，静息心率" + (prhr > 0 ? prhr + "bpm" : "未提供") + "。";
                    }
                    String user = "这是我最近从手表采集的全部真实健康数据，请据此生成上面的六部分深度报告：\n"
                            + digest + profTxt;
                    // 默认免 Key：内置免费网关 + 多模型自动降级；也可填自己的 Key
                    result = CloudAI.smartChat(endpoint, key, model, system, user);
                } catch (Exception e) {
                    result = "【联网分析失败】" + (e.getMessage() == null ? e.toString() : e.getMessage())
                            + "\n\n免费通道可能有临时限流，请稍后重试；或在 API 设置里填写自己的 Key。失败时请继续使用本地 AI 分析。";
                }
                final String res = result;
                runOnUiThread(() -> web.evaluateJavascript(
                        "javascript:window.__aiResult&&window.__aiResult(" + JSONObject.quote(res) + ")", null));
            }).start();
        }

        /** 联网睡眠专项分析：基于近 7 晚真实分期/HRV/作息/睡眠债，生成睡眠深度报告 */
        @JavascriptInterface
        public void aiSleepAnalyze() {
            new Thread(() -> {
                String result;
                try {
                    SharedPreferences sp = getSharedPreferences("watchai_ai", MODE_PRIVATE);
                    String endpoint = sp.getString("endpoint", CloudAI.FREE_ENDPOINT);
                    String model = sp.getString("model", "free");
                    String key = sp.getString("key", "");
                    String sleepDigest = CloudAI.sleepDigest(lastDataJson);
                    String system = "你是一位睡眠医学与神经恢复专家，正在为用户撰写一份「睡眠深度分析报告」。"
                            + "用户从智能手表真实采集的最近多晚睡眠数据已在下方给出。你要基于这些数值做专业、深入且可执行的解读，"
                            + "发现数据之间的关联与趋势，而不是简单复述数字。\n"
                            + "请严格按以下五个部分输出，每部分用一行「【标题】」开头，标题后换行写内容：\n"
                            + "【睡眠总评】用 3-4 句概括这几晚睡眠的整体质量，点明最值得关注的一个问题。\n"
                            + "【结构分析】结合每晚深睡、浅睡、REM、清醒的时长与占比，解读深睡与 REM 是否达标、睡眠结构是否合理，必须引用具体分钟数与百分比。\n"
                            + "【恢复质量】结合夜间 HRV 与夜间心率的相对较高低及趋势，判断自主神经系统恢复得如何、是否处于疲劳或压力状态。\n"
                            + "【作息与睡眠债】结合入睡/起床时间、作息规律性与睡眠债，判断生物钟是否稳定、是否需要补觉或调整作息。\n"
                            + "【今晚怎么睡】给出今晚具体到「几点上床、睡前做什么、卧室环境如何调整」的可执行建议，针对前面发现的问题。\n"
                            + "要求：必须引用数据里的具体数值（分钟、百分比、HRV、心率、时间）；解释数值背后的生理意义；"
                            + "语气专业有温度；用简短段落，不要使用 markdown 的 # 或 ** 符号。";
                    String user = "这是我最近从手表采集的真实睡眠数据，请据此生成上面的五部分睡眠深度报告：\n" + sleepDigest;
                    result = CloudAI.smartChat(endpoint, key, model, system, user);
                } catch (Exception e) {
                    result = "【睡眠分析失败】" + (e.getMessage() == null ? e.toString() : e.getMessage())
                            + "\n\n免费通道可能有临时限流，请稍后重试；或在 AI 引擎设置里填写自己的 Key。";
                }
                final String res = result;
                runOnUiThread(() -> web.evaluateJavascript(
                        "javascript:window.__aiSleepResult&&window.__aiSleepResult(" + JSONObject.quote(res) + ")", null));
            }).start();
        }
    }

    /** 跟随系统：当前是否深色模式。优先用 -night 资源布尔值（随系统 uiMode 自动选择），再退回 uiMode 判断。 */
    private boolean isNight() {
        try {
            // values/values-night/bools.xml 由系统根据 UI_MODE_NIGHT 自动挑选，最可靠
            return getResources().getBoolean(R.bool.is_night);
        } catch (Exception ignored) { }
        int ui = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return ui == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 状态栏 / 导航栏跟随系统明暗 */
    private void applySystemBars() {
        boolean night = isNight();
        int bar = night ? Color.rgb(0, 0, 0) : Color.rgb(0xf5, 0xf5, 0xf7);
        getWindow().setStatusBarColor(bar);
        getWindow().setNavigationBarColor(bar);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.view.View v = getWindow().getDecorView();
            int flags = v.getSystemUiVisibility();
            if (night) flags &= ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            else flags |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            v.setSystemUiVisibility(flags);
        }
    }

    private void push() {
        web.evaluateJavascript("javascript:window.loadData(" + lastDataJson + "," + lastDiagJson + "," + lastUpdateMs + ")", null);
    }

    /** 把当前系统明暗主题推送给前端 */
    private void pushTheme() {
        web.evaluateJavascript("javascript:window.__setNight&&window.__setNight(" + isNight() + ")", null);
    }

    private void loadAsync() {
        if (loading) return;
        loading = true;
        new Thread(() -> {
            HealthDataSet data;
            DiagReport diag;
            try {
                MiHealthReader reader = new MiHealthReader();
                data = reader.read(this);
                diag = reader.diag;
                // 缓存完整原始锻炼对象（导出用，绝不降采样）
                rawExercises = data != null ? data.exercises : null;
                // 读取身体资料，用于 VO2max 个体化校正
                SharedPreferences psp = getSharedPreferences("watchai_profile", MODE_PRIVATE);
                DataJsonBuilder.ProfileInput prof = new DataJsonBuilder.ProfileInput();
                prof.age = psp.getInt("age", 0);
                prof.restHr = psp.getInt("restHr", 0);
                prof.weightKg = psp.getFloat("weight", 0f);
                prof.gender = psp.getString("gender", "male");
                lastDataJson = JSONObject.quote(DataJsonBuilder.dataJson(data, prof).toString());
                lastDiagJson = JSONObject.quote(DataJsonBuilder.diagJson(diag).toString());
            } catch (Exception e) {
                lastDataJson = "null";
                try {
                    JSONObject o = new JSONObject();
                    o.put("shellOk", false);
                    o.put("lines", new org.json.JSONArray());
                    o.put("trace", "读取异常: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                    lastDiagJson = JSONObject.quote(o.toString());
                } catch (Exception ignored) { lastDiagJson = "null"; }
            }
            runOnUiThread(() -> {
                saveCache();
                push();
                loading = false;
                // 通知前端刷新“最后更新时间”与同步状态
                web.evaluateJavascript("javascript:window.__touch&&window.__touch()", null);
                web.evaluateJavascript("javascript:window.__syncDone&&window.__syncDone()", null);
            });
        }).start();
    }
}
