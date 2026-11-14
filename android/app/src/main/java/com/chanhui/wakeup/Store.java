package com.chanhui.wakeup;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

/** 알람 목록 / 현재 상태 저장 + AlarmManager 예약 */
public class Store {
    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("wakeup", Context.MODE_PRIVATE);
    }

    static String alarms(Context c) { return sp(c).getString("alarms", "[]"); }

    static void saveAlarms(Context c, String json) { sp(c).edit().putString("alarms", json).apply(); }

    // pending: {"mode":"ring|mission|wait|check","alarmId":"..","msg":"..","checkEnd":123}
    static JSONObject pending(Context c) {
        try { return new JSONObject(sp(c).getString("pending", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    static String mode(Context c) { return pending(c).optString("mode", ""); }

    static void setPending(Context c, JSONObject o) { sp(c).edit().putString("pending", o == null ? "{}" : o.toString()).apply(); }

    static boolean isLocked(Context c) {
        String m = mode(c);
        return m.equals("ring") || m.equals("mission");
    }

    /* ---------- 예약 ---------- */
    static PendingIntent firePI(Context c, String id) {
        Intent i = new Intent(c, AlarmReceiver.class);
        i.setAction("com.chanhui.wakeup.FIRE");
        i.setData(Uri.parse("wakeup://alarm/" + Uri.encode(id)));
        i.putExtra("alarmId", id);
        return PendingIntent.getBroadcast(c, id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static long nextTrigger(JSONObject a) {
        try {
            String[] hm = a.getString("time").split(":");
            int h = Integer.parseInt(hm[0]), m = Integer.parseInt(hm[1]);
            JSONArray days = a.optJSONArray("days");
            Set<Integer> ds = new HashSet<>();
            if (days != null) for (int i = 0; i < days.length(); i++) ds.add(days.getInt(i));
            long now = System.currentTimeMillis();
            for (int d = 0; d <= 7; d++) {
                Calendar cal = Calendar.getInstance();
                cal.add(Calendar.DAY_OF_YEAR, d);
                cal.set(Calendar.HOUR_OF_DAY, h);
                cal.set(Calendar.MINUTE, m);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                if (cal.getTimeInMillis() <= now + 1000) continue;
                int dow = cal.get(Calendar.DAY_OF_WEEK) - 1; // 0=일
                if (ds.isEmpty() || ds.contains(dow)) return cal.getTimeInMillis();
            }
        } catch (Exception ignored) { }
        return -1;
    }

    static void scheduleAll(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        SharedPreferences p = sp(c);
        // 기존 예약 취소
        for (String id : p.getStringSet("scheduled", new HashSet<>())) am.cancel(firePI(c, id));
        Set<String> now = new HashSet<>();
        try {
            JSONArray arr = new JSONArray(alarms(c));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject a = arr.getJSONObject(i);
                if (!a.optBoolean("on", false)) continue;
                long t = nextTrigger(a);
                if (t < 0) continue;
                String id = a.getString("id");
                PendingIntent pi = firePI(c, id);
                Intent show = new Intent(c, MainActivity.class);
                PendingIntent showPi = PendingIntent.getActivity(c, 1, show, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                try {
                    am.setAlarmClock(new AlarmManager.AlarmClockInfo(t, showPi), pi);
                } catch (SecurityException se) {
                    if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi);
                    else am.set(AlarmManager.RTC_WAKEUP, t, pi);
                }
                now.add(id);
            }
        } catch (Exception ignored) { }
        p.edit().putStringSet("scheduled", now).apply();
    }

    /** 한 번만 울리는 알람이면 꺼 두기 */
    static void markFired(Context c, String id) {
        try {
            JSONArray arr = new JSONArray(alarms(c));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject a = arr.getJSONObject(i);
                if (id.equals(a.optString("id"))) {
                    JSONArray d = a.optJSONArray("days");
                    if (d == null || d.length() == 0) a.put("on", false);
                }
            }
            saveAlarms(c, arr.toString());
        } catch (Exception ignored) { }
    }
}
