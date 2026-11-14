package com.chanhui.wakeup;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

public class MainActivity extends Activity {
    static volatile boolean visible = false;
    static MainActivity instance;
    WebView web;
    boolean pageReady = false;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        instance = this;
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        AlarmService.makeChannels(this);

        web = new WebView(this);
        web.setBackgroundColor(0xFF0F1222);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.addJavascriptInterface(new Bridge(), "AndroidAlarm");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) { pageReady = true; }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url.startsWith("file:")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) { }
                return true;
            }
        });
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        Store.scheduleAll(this);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        String ev = i.getStringExtra("event");
        if ("ring".equals(ev)) callJs("ring", i.getStringExtra("alarmId"), i.getStringExtra("msg"));
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        updateKeepOn();
        if (!pageReady) return;
        JSONObject p = Store.pending(this);
        String m = p.optString("mode", "");
        if (m.equals("check")) callJs("check", String.valueOf(p.optLong("checkEnd")), "");
        else if (m.isEmpty()) callJs("checkok", "", "");
        callJs("perm", "", "");
    }

    @Override protected void onPause() { super.onPause(); visible = false; }

    @Override protected void onDestroy() { if (instance == this) instance = null; visible = false; super.onDestroy(); }

    void updateKeepOn() {
        if (Store.isLocked(this)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /* 뒤로가기: 알람/미션 중엔 막기, 평소엔 앱을 백그라운드로 */
    @Override public void onBackPressed() {
        if (Store.isLocked(this)) { Toast.makeText(this, "미션을 끝내야 알람이 꺼져요!", Toast.LENGTH_SHORT).show(); return; }
        moveTaskToBack(true);
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (Store.isLocked(this) && (code == KeyEvent.KEYCODE_BACK)) return true;
        return super.onKeyDown(code, e);
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) { callJs("perm", "", ""); }

    /* ---------- 네이티브 → 웹 ---------- */
    void callJs(String ev, String a, String b) {
        final String js = "window.onNative&&window.onNative(" + JSONObject.quote(ev) + "," + JSONObject.quote(a == null ? "" : a) + "," + JSONObject.quote(b == null ? "" : b) + ")";
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }

    static void js(Context c, String ev, String a, String b) {
        MainActivity m = instance;
        if (m != null) m.callJs(ev, a, b);
    }

    /* ---------- 웹 → 네이티브 ---------- */
    void svc(String action, Intent extra) {
        Intent s = extra != null ? extra : new Intent();
        s.setClass(this, AlarmService.class);
        s.setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(s); else startService(s);
        } catch (Exception e) { try { startService(s); } catch (Exception ignored) { } }
    }

    class Bridge {
        @JavascriptInterface public void setAlarms(String json) {
            Store.saveAlarms(MainActivity.this, json);
            Store.scheduleAll(MainActivity.this);
        }

        @JavascriptInterface public void startRing(String id, String msg) {
            Intent i = new Intent();
            i.putExtra("alarmId", id); i.putExtra("msg", msg); i.putExtra("fromWeb", true);
            svc(AlarmService.ACTION_RING, i);
            runOnUiThread(MainActivity.this::updateKeepOn);
        }

        @JavascriptInterface public void stopRing() {
            if (!Store.isLocked(MainActivity.this)) return;
            svc(AlarmService.ACTION_MISSION, null);
        }

        @JavascriptInterface public void missionDone(long delayMs) {
            Intent i = new Intent();
            i.putExtra("delay", Math.max(5000, delayMs));
            svc(AlarmService.ACTION_WAIT, i);
            runOnUiThread(() -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        }

        @JavascriptInterface public void checkYes() { svc(AlarmService.ACTION_YES, null); }

        @JavascriptInterface public String getPending() { return Store.pending(MainActivity.this).toString(); }

        @JavascriptInterface public String permissions() {
            JSONObject o = new JSONObject();
            try {
                Context c = MainActivity.this;
                o.put("notif", Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
                o.put("overlay", Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c));
                o.put("exact", Build.VERSION.SDK_INT < 31 || ((AlarmManager) c.getSystemService(ALARM_SERVICE)).canScheduleExactAlarms());
                o.put("battery", Build.VERSION.SDK_INT < 23 || ((PowerManager) c.getSystemService(POWER_SERVICE)).isIgnoringBatteryOptimizations(getPackageName()));
            } catch (Exception ignored) { }
            return o.toString();
        }

        @JavascriptInterface public void requestPermission(String name) {
            runOnUiThread(() -> {
                try {
                    Uri pkg = Uri.parse("package:" + getPackageName());
                    switch (name) {
                        case "notif":
                            if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
                            break;
                        case "overlay":
                            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg));
                            break;
                        case "exact":
                            if (Build.VERSION.SDK_INT >= 31) startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg));
                            break;
                        case "battery":
                            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg));
                            break;
                    }
                } catch (Exception e) {
                    try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) { }
                }
            });
        }
    }
}
