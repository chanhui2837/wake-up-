package com.chanhui.wakeup;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 알람 시각이 되면 호출됨 */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String id = intent.getStringExtra("alarmId");
        if (id == null) return;
        // 이미 다른 알람 미션 중이면 겹치지 않게 무시
        String m = Store.mode(c);
        Store.markFired(c, id);
        Store.scheduleAll(c);
        if (m.equals("ring") || m.equals("mission") || m.equals("check")) return;

        Intent s = new Intent(c, AlarmService.class);
        s.setAction(AlarmService.ACTION_RING);
        s.putExtra("alarmId", id);
        s.putExtra("msg", "");
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s); else c.startService(s);
    }
}
