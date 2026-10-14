package com.poppy.courier;

import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.location.*;
import android.os.*;
import java.net.HttpURLConnection;
import java.net.URL;

public class GpsTrackerService extends Service implements LocationListener {
    private static final String SERVER_API_URL = "https://panel.poppyapartments.com/courier_api.php";
    private LocationManager locationManager;
    private int courierId = 0;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences prefs = getSharedPreferences("poppy_prefs", MODE_PRIVATE);
        if (intent != null && intent.hasExtra("courier_id")) {
            courierId = intent.getIntExtra("courier_id", 0);
            prefs.edit().putInt("courier_id", courierId).apply();
        } else {
            courierId = prefs.getInt("courier_id", 0);
        }
        startForegroundNotification();
        startLocationUpdates();
        return START_STICKY;
    }

    private void startForegroundNotification() {
        String channelId = "poppy_gps_channel";
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId, "Poppy Kurye GPS Takip", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
            builder = new Notification.Builder(this, channelId);
        } else {
            builder = new Notification.Builder(this);
        }
        Notification notification = builder
                .setContentTitle("🛵 Poppy Kurye Aktif")
                .setContentText("Canlı konum takibi arka planda çalışıyor")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .build();
        startForeground(1, notification);
    }

    @SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager != null) {
            try { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 8000, 8, this); } catch (Exception ignored) {}
            try { locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 8000, 8, this); } catch (Exception ignored) {}
        }
    }

    @Override
    public void onLocationChanged(Location loc) {
        if (courierId <= 0 || loc == null) return;
        new Thread(() -> {
            try {
                BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
                int bat = bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : 100;
                String urlStr = SERVER_API_URL + "?action=ping_gps&courier_id=" + courierId
                        + "&lat=" + loc.getLatitude() + "&lng=" + loc.getLongitude() + "&battery=" + bat;
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception ignored) {}
        }).start();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}