package com.chanhui.wakeup;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;

import org.json.JSONObject;

/**
 * 알람 상태 머신
 * RING    : 소리 + 진동, 앱이 닫히면 다시 띄움
 * MISSION : 소리 없음, 앱이 닫히면 다시 띄움
 * WAIT    : 1~5분 대기 후 CHECK
 * CHECK   : "완전히 깨어났나요?" 알림, 10초 안에 '예' 없으면 RING
 */
public class AlarmService extends Service {
    static final String ACTION_RING = "ring", ACTION_MISSION = "mission", ACTION_WAIT = "wait",
            ACTION_YES = "yes", ACTION_STOP = "stop";
    static final String CH_ALARM = "alarm2", CH_CHECK = "check2";
    static final int NID_FG = 1, NID_CHECK = 2;

    final Handler h = new Handler(Looper.getMainLooper());
    SoundPlayer sp;
    Vibrator vib;
    PowerManager.WakeLock wl;
    String mode = "";
    String alarmId = "";
    long lastVisible = System.currentTimeMillis();

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        sp = new SoundPlayer(this);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wakeup:alarm");
        wl.acquire(60 * 60 * 1000L);
        makeChannels(this);
        h.post(watchdog);
    }

    static void makeChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel a = new NotificationChannel(CH_ALARM, "알람", NotificationManager.IMPORTANCE_HIGH);
        a.setSound(null, null);
        a.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(a);
        NotificationChannel k = new NotificationChannel(CH_CHECK, "기상 확인", NotificationManager.IMPORTANCE_HIGH);
        k.enableVibration(true);
        k.setVibrationPattern(new long[]{0, 300, 150, 300, 150, 300});
        k.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(k);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String act = intent != null && intent.getAction() != null ? intent.getAction() : ACTION_RING;
        if (intent == null) { // 시스템이 서비스 재시작 → 저장된 상태 복구
            String m = Store.mode(this);
            if (m.isEmpty()) { stopAll(); return START_NOT_STICKY; }
            act = m.equals("wait") ? ACTION_WAIT : ACTION_RING;
        }
        switch (act) {
            case ACTION_RING: {
                String id = intent != null ? intent.getStringExtra("alarmId") : null;
                if (id == null || id.isEmpty()) id = Store.pending(this).optString("alarmId", "");
                String msg = intent != null ? intent.getStringExtra("msg") : "";
                boolean fromWeb = intent != null && intent.getBooleanExtra("fromWeb", false);
                ring(id, msg == null ? "" : msg, fromWeb);
                break;
            }
            case ACTION_MISSION:
                mission();
                break;
            case ACTION_WAIT: {
                long delay = intent != null ? intent.getLongExtra("delay", 120000) : 60000;
                if (intent == null) delay = Math.max(1000, Store.pending(this).optLong("checkAt", 0) - System.currentTimeMillis());
                waitCheck(delay);
                break;
            }
            case ACTION_YES:
                yes();
                break;
            case ACTION_STOP:
                Store.setPending(this, null);
                stopAll();
                break;
        }
        return START_STICKY;
    }

    /* ---------- 상태 ---------- */
    void ring(String id, String msg, boolean fromWeb) {
        h.removeCallbacks(checkTimeout);
        h.removeCallbacks(startCheck);
        cancelCheckNotif();
        mode = ACTION_RING;
        alarmId = id;
        JSONObject p = new JSONObject();
        try { p.put("mode", "ring"); p.put("alarmId", id); p.put("msg", msg); } catch (Exception ignored) { }
        Store.setPending(this, p);
        goForeground("⏰ 알람이 울리고 있어요", "미션을 끝내야 꺼져요", true);
        playSound();
        if (!fromWeb) launchApp("ring", id, msg);
    }

    void mission() {
        if (!(mode.equals(ACTION_RING) || mode.equals(ACTION_MISSION) || Store.isLocked(this))) return;
        mode = ACTION_MISSION;
        stopSound();
        JSONObject p = Store.pending(this);
        try { p.put("mode", "mission"); } catch (Exception ignored) { }
        Store.setPending(this, p);
        goForeground("🔥 미션 진행 중", "시간 안에 미션을 끝내세요", true);
    }

    void waitCheck(long delay) {
        mode = ACTION_WAIT;
        stopSound();
        JSONObject p = Store.pending(this);
        try { p.put("mode", "wait"); p.put("checkAt", System.currentTimeMillis() + delay); } catch (Exception ignored) { }
        Store.setPending(this, p);
        goForeground("🌞 미션 성공!", "잠시 후 정말 깼는지 확인할게요", false);
        h.removeCallbacks(startCheck);
        h.postDelayed(startCheck, delay);
    }

    final Runnable startCheck = new Runnable() {
        @Override public void run() {
            mode = "check";
            long end = System.currentTimeMillis() + 10000;
            JSONObject p = Store.pending(AlarmService.this);
            try { p.put("mode", "check"); p.put("checkEnd", end); } catch (Exception ignored) { }
            Store.setPending(AlarmService.this, p);
            showCheckNotif();
            beepShort();
            MainActivity.js(AlarmService.this, "check", String.valueOf(end), "");
            h.postDelayed(checkTimeout, 10000);
        }
    };

    final Runnable checkTimeout = new Runnable() {
        @Override public void run() {
            if (!"check".equals(mode)) return;
            ring(Store.pending(AlarmService.this).optString("alarmId", alarmId), "😴 확인을 안 눌렀어요! 다시 미션 시작!", false);
        }
    };

    void yes() {
        if (!"check".equals(mode) && !"check".equals(Store.mode(this))) return;
        h.removeCallbacks(checkTimeout);
        Store.setPending(this, null);
        cancelCheckNotif();
        MainActivity.js(this, "checkok", "", "");
        stopAll();
    }

    void stopAll() {
        mode = "";
        stopSound();
        h.removeCallbacksAndMessages(null);
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        stopSelf();
    }

    /* ---------- 앱 닫기 방지: 알람/미션 중 앱이 안 보이면 다시 띄움 ---------- */
    final Runnable watchdog = new Runnable() {
        @Override public void run() {
            boolean lockedNow = mode.equals(ACTION_RING) || mode.equals(ACTION_MISSION);
            if (MainActivity.visible) lastVisible = System.currentTimeMillis();
            else if (lockedNow && System.currentTimeMillis() - lastVisible > 1200) {
                launchApp(null, null, null);
                lastVisible = System.currentTimeMillis();
            }
            h.postDelayed(this, 700);
        }
    };

    void launchApp(String event, String id, String msg) {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        if (event != null) { i.putExtra("event", event); i.putExtra("alarmId", id); i.putExtra("msg", msg); }
        try { startActivity(i); } catch (Exception ignored) { }
    }

    /* ---------- 알림 ---------- */
    PendingIntent openPI() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 10, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    void goForeground(String title, String text, boolean fullScreen) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH_ALARM) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title).setContentText(text)
                .setOngoing(true).setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(openPI());
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_MAX);
        if (fullScreen) b.setFullScreenIntent(openPI(), true);
        Notification n = b.build();
        n.flags |= Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT;
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID_FG, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NID_FG, n);
    }

    void showCheckNotif() {
        Intent y = new Intent(this, AlarmService.class);
        y.setAction(ACTION_YES);
        PendingIntent yPi = PendingIntent.getService(this, 20, y, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH_CHECK) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("완전히 깨어났나요?")
                .setContentText("10초 안에 '예'를 누르지 않으면 알람이 다시 울려요!")
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(yPi)
                .addAction(new Notification.Action.Builder(null, "예! 깼어요", yPi).build());
        if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(11000);
        if (Build.VERSION.SDK_INT < 26) { b.setPriority(Notification.PRIORITY_MAX); b.setVibrate(new long[]{0, 300, 150, 300}); }
        getSystemService(NotificationManager.class).notify(NID_CHECK, b.build());
    }

    void cancelCheckNotif() {
        try { getSystemService(NotificationManager.class).cancel(NID_CHECK); } catch (Exception ignored) { }
    }

    /* ---------- 소리 / 진동 ---------- */
    void playSound() {
        if (sp.playing()) return;
        String sound = "system";
        int vol = 80;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(Store.alarms(this));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject a = arr.getJSONObject(i);
                if (alarmId != null && alarmId.equals(a.optString("id"))) {
                    sound = a.optString("sound", "system");
                    vol = a.optInt("volume", 80);
                }
            }
        } catch (Exception ignored) { }
        sp.start(sound, vol, true);
        try {
            long[] pat = {0, 700, 400};
            if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createWaveform(pat, 0),
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
            else vib.vibrate(pat, 0);
        } catch (Exception ignored) { }
    }

    void beepShort() {
        try {
            ToneGenerator t = new ToneGenerator(AudioManager.STREAM_ALARM, 80);
            t.startTone(ToneGenerator.TONE_PROP_BEEP2, 600);
            h.postDelayed(t::release, 1500);
        } catch (Exception ignored) { }
    }

    void stopSound() {
        try { sp.stop(); } catch (Exception ignored) { }
        try { vib.cancel(); } catch (Exception ignored) { }
    }

    @Override
    public void onDestroy() {
        stopSound();
        h.removeCallbacksAndMessages(null);
        try { if (wl.isHeld()) wl.release(); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
