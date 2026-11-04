package com.poppy.courier;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.URL;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;

import java.net.HttpURLConnection;

public class GpsTrackerService extends Service implements LocationListener {
    private static final String SERVER_API_URL = "https://panel.poppyapartments.com/courier_api.php";
    private static final String GPS_CHANNEL_ID = "poppy_gps_silent_v3";

    private LocationManager locationManager;
    private int courierId = 0;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            // Konum izni henüz verilmediyse servisi başlatıp uygulamayı çökertme
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                if (!fine && !coarse) {
                    stopSelf();
                    return START_NOT_STICKY;
                }
            }

            SharedPreferences prefs = getSharedPreferences("poppy_prefs", MODE_PRIVATE);
            if (intent != null && intent.hasExtra("courier_id")) {
                courierId = intent.getIntExtra("courier_id", 0);
                prefs.edit().putInt("courier_id", courierId).apply();
            } else {
                courierId = prefs.getInt("courier_id", 0);
            }

            if (startForegroundNotificationSafe()) {
                startLocationUpdates();
            } else {
                stopSelf();
                return START_NOT_STICKY;
            }
        } catch (Throwable ignored) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private boolean startForegroundNotificationSafe() {
        try {
            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) {
                    NotificationChannel channel = new NotificationChannel(
                            GPS_CHANNEL_ID,
                            "Poppy Kurye Arka Plan Konum",
                            NotificationManager.IMPORTANCE_MIN
                    );
                    channel.setShowBadge(false);
                    nm.createNotificationChannel(channel);
                }
                builder = new Notification.Builder(this, GPS_CHANNEL_ID);
            } else {
                builder = new Notification.Builder(this);
                builder.setPriority(Notification.PRIORITY_MIN);
            }

            Intent openIntent = new Intent(this, MainActivity.class);
            int pFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openIntent, pFlags);

            Notification notification = builder
                    .setContentTitle("Poppy Kurye Aktif")
                    .setContentText("Sipariş ve konum sistemi çalışıyor")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentIntent(pendingIntent)
                    .build();

            startForeground(1, notification);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        try {
            locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (locationManager != null) {
                try {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 8000, 8, this);
                } catch (Throwable ignored) {
                }
                try {
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 8000, 8, this);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onLocationChanged(Location loc) {
        SharedPreferences prefs = getSharedPreferences("poppy_prefs", MODE_PRIVATE);
        int activeId = prefs.getInt("courier_id", courierId);
        if (activeId <= 0 || loc == null) return;

        new Thread(() -> {
            try {
                BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
                int bat = bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : 100;
                String urlStr = SERVER_API_URL + "?action=ping_gps&courier_id=" + activeId
                        + "&lat=" + loc.getLatitude() + "&lng=" + loc.getLongitude() + "&battery=" + bat;
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.getResponseCode();
                conn.disconnect();
            } catch (Throwable ignored) {
            }
        }).start();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // TELEFON AÇILDIĞINDA UYGULAMAYI OTOMATİK AÇAN BAŞLATICI
    public static class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                if (intent == null || intent.getAction() == null) return;
                Intent launchIntent = new Intent(context, MainActivity.class);
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(launchIntent);
            } catch (Throwable ignored) {
            }
        }
    }
}
