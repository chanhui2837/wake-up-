package com.chanhui.wakeup;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 재부팅/업데이트 후 알람 다시 예약. 끄기 전에 재부팅했으면 다시 울림 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        Store.scheduleAll(c);
        String m = Store.mode(c);
        org.json.JSONObject p = Store.pending(c);
        boolean guard = p.optDouble("guardThr", -1) > 0 && p.optLong("guardUntil", 0) > System.currentTimeMillis();
        if (m.equals("ring") || m.equals("mission") || m.equals("check") || m.equals("wait") || guard) {
            Intent s = new Intent(c, AlarmService.class);
            s.setAction(AlarmService.ACTION_RING);
            s.putExtra("alarmId", Store.pending(c).optString("alarmId", ""));
            s.putExtra("msg", "🔁 재부팅해도 소용없어요. 처음부터 다시!");
            try {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s); else c.startService(s);
            } catch (Exception ignored) { }
        }
    }
}
