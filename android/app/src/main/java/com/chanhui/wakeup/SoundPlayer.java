package com.chanhui.wakeup;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.io.File;

/**
 * 알람음 재생 + 소리 크기 고정.
 * sound: "system" | "beep" | "siren" | "chime" | "snd_xxx"(사용자 MP3)
 * lockVolume=true 이면 울리는 동안 볼륨 버튼·설정으로 크기를 바꿔도 즉시 원래대로 되돌림.
 */
public class SoundPlayer {
    final Context c;
    final AudioManager am;
    final Handler h = new Handler(Looper.getMainLooper());
    MediaPlayer mp;
    ToneGenerator tg;
    int orig = -1, target = -1;
    boolean lock = false;

    SoundPlayer(Context c) {
        this.c = c.getApplicationContext();
        am = (AudioManager) this.c.getSystemService(Context.AUDIO_SERVICE);
    }

    static File soundFile(Context c, String id) {
        String safe = id == null ? "" : id.replaceAll("[^A-Za-z0-9_]", "");
        File dir = new File(c.getFilesDir(), "sounds");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, safe);
    }

    void start(String sound, int volumePct, boolean lockVolume) {
        stop();
        if (sound == null || sound.isEmpty()) sound = "system";
        int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
        target = Math.max(1, Math.round(max * Math.max(10, Math.min(100, volumePct)) / 100f));
        orig = am.getStreamVolume(AudioManager.STREAM_ALARM);
        lock = lockVolume;
        setVol();
        h.post(volGuard);

        Uri uri = null;
        if (sound.startsWith("snd_")) {
            File f = soundFile(c, sound);
            if (f.exists() && f.length() > 0) uri = Uri.fromFile(f);
            else sound = "system";
        }
        if (sound.equals("system")) {
            uri = RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = Settings.System.DEFAULT_ALARM_ALERT_URI;
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        }
        if (uri != null) {
            try {
                mp = new MediaPlayer();
                mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                mp.setDataSource(c, uri);
                mp.setLooping(true);
                mp.setVolume(1f, 1f);
                mp.prepare();
                mp.start();
                return;
            } catch (Exception e) {
                try { if (mp != null) mp.release(); } catch (Exception ignored) { }
                mp = null;
                sound = "beep";
            }
        }
        startTone(sound);
    }

    void startTone(String sound) {
        final int t, gap;
        switch (sound) {
            case "siren": t = ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK; gap = 1000; break;
            case "chime": t = ToneGenerator.TONE_CDMA_ALERT_INCALL_LITE; gap = 900; break;
            default: t = ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD; gap = 1000;
        }
        try { tg = new ToneGenerator(AudioManager.STREAM_ALARM, 100); } catch (Exception e) { tg = null; return; }
        h.post(new Runnable() {
            @Override public void run() {
                if (tg == null) return;
                try { tg.startTone(t, gap - 50); } catch (Exception ignored) { }
                h.postDelayed(this, gap);
            }
        });
    }

    void setVol() {
        try {
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) != target)
                am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0);
        } catch (Exception ignored) { }
    }

    /** 소리 크기 고정: 0.25초마다 확인해서 바뀌었으면 되돌림 */
    final Runnable volGuard = new Runnable() {
        @Override public void run() {
            if (target < 0) return;
            if (lock) setVol();
            h.postDelayed(this, 250);
        }
    };

    boolean playing() { return mp != null || tg != null; }

    void stop() {
        h.removeCallbacksAndMessages(null);
        try { if (mp != null) { mp.stop(); mp.release(); } } catch (Exception ignored) { }
        mp = null;
        try { if (tg != null) tg.release(); } catch (Exception ignored) { }
        tg = null;
        if (orig >= 0) { try { am.setStreamVolume(AudioManager.STREAM_ALARM, orig, 0); } catch (Exception ignored) { } }
        orig = -1;
        target = -1;
        lock = false;
    }
}
