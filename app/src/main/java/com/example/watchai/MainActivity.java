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
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.example.watchai.data.CloudAI;
import com.example.watchai.data.DataJsonBuilder;
import com.example.watchai.data.DiagReport;
import com.example.watchai.data.MiHealthReader;
import com.example.watchai.model.HealthDataSet;

import org.json.JSONObject;

/** 主容器：WebView 加载 Apple 风格界面，后台用 root 从「小米运动健康」私有库读真实数据 */
public class MainActivity extends Activity {

    private WebView web;
    private String lastDataJson = "null";
    private String lastDiagJson = "null";
    private long lastUpdateMs = 0L;

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
