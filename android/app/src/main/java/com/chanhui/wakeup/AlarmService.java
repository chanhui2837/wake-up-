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

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 알람 상태 머신
 *  ring    : 소리 + 진동, 앱이 닫히면 다시 띄움
 *  mission : 소리 없음, 앱이 닫히면 다시 띄움
 *  wait    : 알람 꺼진 뒤 1~15분 랜덤 대기 → check
 *  check   : "완전히 깨어났나요?" 전체화면 + 알림, 30초 안에 '예' 없으면 ring
 * 밝기 감시(guard)는 위 상태와 별개로 돌아감:
 *  불 켜기 미션 통과 ~ 알람 꺼진 뒤 정한 시간까지, 기준의 60% 아래로 5초 넘게 어두우면 ring (미션 처음부터).
 *  알람을 삭제하거나 로그아웃해도 이 서비스가 계속 감시함.
 */
public class AlarmService extends Service {
    static final String ACTION_RING = "ring", ACTION_MISSION = "mission", ACTION_WAIT = "wait",
            ACTION_YES = "yes", ACTION_STOP = "stop", ACTION_GUARD = "guard";
    static final String CH_ALARM = "alarm2", CH_CHECK = "check2";
    static final int NID_FG = 1, NID_CHECK = 2;
    static final long CHECK_MS = 30000;

    final Handler h = new Handler(Looper.getMainLooper());
    SoundPlayer sp;
    Vibrator vib;
    PowerManager.WakeLock wl;

    String mode = "", alarmId = "", msg = "";
    long checkAt = 0, checkEnd = 0;
    float guardThr = -1;
    long guardUntil = 0, darkSince = 0;
    boolean lightOn = false;
    long lastVisible = System.currentTimeMillis();

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        sp = new SoundPlayer(this);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wakeup:alarm");
        wl.acquire(3 * 60 * 60 * 1000L);
        makeChannels(this);
        h.post(watchdog);
        h.post(guardLoop);
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
        k.setVibrationPattern(new long[]{0, 400, 150, 400, 150, 400});
        k.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(k);
    }

    /* ---------- 상태 저장/복구 ---------- */
    void persist() {
        if (mode.isEmpty() && guardThr < 0) { Store.setPending(this, null); return; }
        JSONObject p = new JSONObject();
        try {
            p.put("mode", mode); p.put("alarmId", alarmId); p.put("msg", msg);
            p.put("checkAt", checkAt); p.put("checkEnd", checkEnd);
            if (guardThr > 0) { p.put("guardThr", guardThr); p.put("guardUntil", guardUntil); }
        } catch (Exception ignored) { }
        Store.setPending(this, p);
    }

    void restore() {
        JSONObject p = Store.pending(this);
        mode = p.optString("mode", ""); alarmId = p.optString("alarmId", ""); msg = p.optString("msg", "");
        checkAt = p.optLong("checkAt", 0); checkEnd = p.optLong("checkEnd", 0);
        guardThr = (float) p.optDouble("guardThr", -1); guardUntil = p.optLong("guardUntil", 0);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { // 시스템이 서비스를 다시 살림 → 저장된 상태로 복구
            restore();
            if (mode.isEmpty() && guardThr < 0) { stopAll(); return START_NOT_STICKY; }
            if (mode.equals("ring") || mode.equals("mission") || mode.equals("check")) ring(alarmId, "🔁 처음부터 다시!", false);
            else {
                if (guardThr > 0) startLight();
                if (mode.equals("wait")) h.postDelayed(startCheck, Math.max(1000, checkAt - System.currentTimeMillis()));
                refreshFg();
            }
            return START_STICKY;
        }
        if (mode.isEmpty() && guardThr < 0) restore();
        String act = intent.getAction() == null ? ACTION_RING : intent.getAction();
        switch (act) {
            case ACTION_RING: {
                String id = intent.getStringExtra("alarmId");
                if (id == null || id.isEmpty()) id = alarmId;
                String m = intent.getStringExtra("msg");
                ring(id, m == null ? "" : m, intent.getBooleanExtra("fromWeb", false));
                break;
            }
            case ACTION_MISSION: mission(); break;
            case ACTION_GUARD: {
                guardThr = intent.getFloatExtra("thr", 80f);
                guardUntil = System.currentTimeMillis() + 3 * 60 * 60 * 1000L; // 미션 끝날 때 실제 시간으로 바뀜
                darkSince = 0;
                String id = intent.getStringExtra("alarmId");
                if (id != null && !id.isEmpty()) alarmId = id;
                startLight();
                persist();
                refreshFg();
                break;
            }
            case ACTION_WAIT: waitCheck(intent.getLongExtra("delay", 120000), intent.getIntExtra("guardMin", 0)); break;
            case ACTION_YES: yes(); break;
            case ACTION_STOP:
                mode = ""; guardThr = -1; persist(); stopAll(); break;
        }
        return START_STICKY;
    }

    /* ---------- 상태 ---------- */
    void ring(String id, String m, boolean fromWeb) {
        h.removeCallbacks(checkTimeout);
        h.removeCallbacks(startCheck);
        cancelCheckNotif();
        stopGuard(false);
        mode = "ring"; alarmId = id == null ? "" : id; msg = m;
        persist();
        goForeground("⏰ 알람이 울리고 있어요", "미션을 끝내야 꺼져요", true);
        playSound();
        if (!fromWeb) launchApp("ring");
    }

    void mission() {
        if (!(mode.equals("ring") || mode.equals("mission"))) return;
        mode = "mission";
        stopSound();
        persist();
        refreshFg();
    }

    void waitCheck(long delay, int guardMin) {
        mode = "wait";
        stopSound();
        checkAt = System.currentTimeMillis() + delay;
        if (guardThr > 0) {
            if (guardMin > 0) guardUntil = System.currentTimeMillis() + guardMin * 60000L;
            else stopGuard(false);
        }
        persist();
        refreshFg();
        h.removeCallbacks(startCheck);
        h.postDelayed(startCheck, delay);
    }

    final Runnable startCheck = new Runnable() {
        @Override public void run() {
            mode = "check";
            checkEnd = System.currentTimeMillis() + CHECK_MS;
            persist();
            goForeground("완전히 깨어났나요?", "30초 안에 '예'를 누르세요!", true);
            showCheckNotif();
            beepShort();
            try { vib.vibrate(new long[]{0, 400, 150, 400, 150, 400}, -1); } catch (Exception ignored) { }
            launchApp("check");
            MainActivity.js(AlarmService.this, "check", String.valueOf(checkEnd), "");
            h.postDelayed(checkTimeout, CHECK_MS);
        }
    };

    final Runnable checkTimeout = new Runnable() {
        @Override public void run() {
            if (!"check".equals(mode)) return;
            ring(alarmId, "😴 확인을 안 눌렀어요! 다시 미션 시작!", false);
        }
    };

    void yes() {
        if (!"check".equals(mode)) return;
        h.removeCallbacks(checkTimeout);
        cancelCheckNotif();
        mode = "";
        persist();
        MainActivity.js(this, "checkok", "", "");
        maybeStop();
    }

    /* ---------- 밝기 감시 ---------- */
    void startLight() { if (!lightOn) { LightMeter.start(this); lightOn = true; } }

    void stopGuard(boolean notify) {
        if (guardThr > 0 && notify) MainActivity.js(this, "guardend", "", "");
        guardThr = -1; guardUntil = 0; darkSince = 0;
        if (lightOn) { LightMeter.stop(this); lightOn = false; }
    }

    final Runnable guardLoop = new Runnable() {
        @Override public void run() {
            if (guardThr > 0) {
                long now = System.currentTimeMillis();
                if (now >= guardUntil) {
                    stopGuard(true);
                    persist();
                    maybeStop();
                } else {
                    startLight();
                    float lux = LightMeter.lux;
                    if (lux >= 0 && lux < guardThr * 0.6f) {   // 기준의 60% 아래 = 확실히 어두워짐
                        if (darkSince == 0) darkSince = now;
                        else if (now - darkSince > 5000) { ring(alarmId, "💡 방이 어두워졌어요! 미션을 처음부터 다시 해야 해요.", false); }
                    } else darkSince = 0;
                    if (mode.isEmpty() || mode.equals("wait")) {
                        if (now % 15000 < 1000) refreshFg();
                    }
                }
            }
            h.postDelayed(this, 1000);
        }
    };

    void maybeStop() {
        if (mode.isEmpty() && guardThr < 0) stopAll(); else refreshFg();
    }

    void stopAll() {
        mode = "";
        stopSound();
        stopGuard(false);
        h.removeCallbacksAndMessages(null);
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        stopSelf();
    }

    void refreshFg() {
        switch (mode) {
            case "ring": goForeground("⏰ 알람이 울리고 있어요", "미션을 끝내야 꺼져요", true); return;
            case "mission": goForeground("🔥 미션 진행 중", "시간 안에 미션을 끝내세요", true); return;
            case "check": goForeground("완전히 깨어났나요?", "30초 안에 '예'를 누르세요!", true); return;
        }
        if (guardThr > 0) {
            long left = Math.max(0, guardUntil - System.currentTimeMillis()) / 60000 + 1;
            goForeground("💡 밝기 유지 중 (약 " + left + "분 남음)", "불을 끄거나 폰을 덮으면 알람이 다시 울려요", false);
        } else goForeground("🌞 미션 성공!", "잠시 후 정말 깼는지 물어볼게요", false);
    }

    /* ---------- 앱 닫기 방지 ---------- */
    final Runnable watchdog = new Runnable() {
        @Override public void run() {
            boolean lockedNow = mode.equals("ring") || mode.equals("mission") || mode.equals("check");
            if (MainActivity.visible) lastVisible = System.currentTimeMillis();
            else if (lockedNow && System.currentTimeMillis() - lastVisible > 1200) {
                launchApp(null);
                lastVisible = System.currentTimeMillis();
            }
            h.postDelayed(this, 700);
        }
    };

    void launchApp(String event) {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        if (event != null) {
            i.putExtra("event", event); i.putExtra("alarmId", alarmId); i.putExtra("msg", msg);
            i.putExtra("checkEnd", checkEnd);
        }
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
                .setOnlyAlertOnce(true)
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
                .setContentText("30초 안에 '예'를 누르지 않으면 알람이 다시 울려요!")
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setFullScreenIntent(openPI(), true)
                .setContentIntent(openPI())
                .addAction(new Notification.Action.Builder(null, "예! 깼어요", yPi).build());
        if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(CHECK_MS + 1000);
        if (Build.VERSION.SDK_INT < 26) { b.setPriority(Notification.PRIORITY_MAX); b.setVibrate(new long[]{0, 400, 150, 400}); }
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
            JSONArray arr = new JSONArray(Store.alarms(this));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject a = arr.getJSONObject(i);
                if (alarmId.equals(a.optString("id"))) { sound = a.optString("sound", "system"); vol = a.optInt("volume", 80); }
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
            ToneGenerator t = new ToneGenerator(AudioManager.STREAM_ALARM, 90);
            t.startTone(ToneGenerator.TONE_PROP_BEEP2, 800);
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
        if (lightOn) { LightMeter.stop(this); lightOn = false; }
        h.removeCallbacksAndMessages(null);
        try { if (wl.isHeld()) wl.release(); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
