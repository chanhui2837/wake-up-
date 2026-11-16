package com.chanhui.wakeup;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/** 조도 센서(lux). 여러 곳에서 같이 쓰므로 참조 카운트로 켜고 끔. lux: -1 = 아직 값 없음, -2 = 센서 없음 */
public class LightMeter {
    static volatile float lux = -1;
    static volatile long updatedAt = 0;
    private static int refs = 0;
    private static SensorEventListener listener;

    static synchronized void start(Context c) {
        refs++;
        if (listener != null) return;
        SensorManager sm = (SensorManager) c.getApplicationContext().getSystemService(Context.SENSOR_SERVICE);
        Sensor s = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (s == null) { lux = -2; return; }
        listener = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) { lux = e.values[0]; updatedAt = System.currentTimeMillis(); }
            @Override public void onAccuracyChanged(Sensor sensor, int a) { }
        };
        sm.registerListener(listener, s, SensorManager.SENSOR_DELAY_NORMAL);
    }

    static synchronized void stop(Context c) {
        if (refs > 0) refs--;
        if (refs > 0 || listener == null) return;
        SensorManager sm = (SensorManager) c.getApplicationContext().getSystemService(Context.SENSOR_SERVICE);
        if (sm != null) sm.unregisterListener(listener);
        listener = null;
        lux = -1;
    }
}
