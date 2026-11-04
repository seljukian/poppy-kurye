package com.poppy.courier;

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
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

public class GpsTrackerService extends Service implements LocationListener {
    private static final String SERVER_API_URL = "https://panel.poppyapartments.com/courier_api.php";
    private static final String GPS_CHANNEL_ID = "poppy_gps_silent_v2";
    private static final String ORDER_CHANNEL_ID = "poppy_courier_orders_v4";

    private LocationManager locationManager;
    private PowerManager.WakeLock serviceWakeLock;
    private WifiManager.WifiLock serviceWifiLock;
    private int courierId = 0;

    private static boolean isServiceOrderThreadRunning = false;
    private static boolean serviceOrderPollingEnabled = false;
    private static Set<String> serviceKnownOrderIds = new HashSet<>();
    private static boolean serviceFirstCheckDone = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences prefs = getSharedPreferences("poppy_prefs", MODE_PRIVATE);
        if (intent != null && intent.hasExtra("courier_id")) {
            courierId = intent.getIntExtra("courier_id", 0);
            prefs.edit().putInt("courier_id", courierId).apply();
        } else {
            courierId = prefs.getInt("courier_id", 0);
        }

        boolean fromBootOrBackground = (intent != null && intent.getBooleanExtra("from_boot", false));
        serviceOrderPollingEnabled = fromBootOrBackground;

        acquireLocks();
        startForegroundNotification();
        startLocationUpdates();
        startServiceOrderMonitor();

        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        // Uygulama son kullanılanlardan kaydırılıp kapatılsa bile sipariş takibini sürdür
        serviceOrderPollingEnabled = true;
        try {
            Intent restartIntent = new Intent(getApplicationContext(), GpsTrackerService.class);
            restartIntent.putExtra("from_boot", true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent);
            } else {
                startService(restartIntent);
            }
        } catch (Exception ignored) {
        }
    }

    private void acquireLocks() {
        try {
            if (serviceWakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    serviceWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PoppyCourier::ServiceWakeLock");
                    serviceWakeLock.acquire();
                }
            }
        } catch (Exception ignored) {
        }

        try {
            if (serviceWifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    serviceWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PoppyCourier::ServiceWifiLock");
                    serviceWifiLock.acquire();
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void startForegroundNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                // IMPORTANCE_MIN sayesinde üst çubukta simge kalabalığı yapmaz ve sessiz çalışır
                NotificationChannel gpsChannel = new NotificationChannel(
                        GPS_CHANNEL_ID,
                        "Poppy Kurye Arka Plan Servisi",
                        NotificationManager.IMPORTANCE_MIN
                );
                gpsChannel.setShowBadge(false);
                nm.createNotificationChannel(gpsChannel);

                NotificationChannel orderChannel = new NotificationChannel(
                        ORDER_CHANNEL_ID,
                        "Kurye Anlık Sipariş Bildirimleri",
                        NotificationManager.IMPORTANCE_HIGH
                );
                orderChannel.enableVibration(true);
                orderChannel.setVibrationPattern(new long[]{0, 500, 200, 500});
                Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                AudioAttributes audioAttributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build();
                orderChannel.setSound(soundUri, audioAttributes);
                nm.createNotificationChannel(orderChannel);
            }
            builder = new Notification.Builder(this, GPS_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
            builder.setPriority(Notification.PRIORITY_MIN);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setVisibility(Notification.VISIBILITY_SECRET);
        }

        Notification notification = builder
                .setContentTitle("Poppy Kurye")
                .setContentText("Arka plan sipariş ve konum sistemi hazır")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .build();

        startForeground(1, notification);
    }

    private void startServiceOrderMonitor() {
        if (isServiceOrderThreadRunning) return;
        isServiceOrderThreadRunning = true;

        Thread orderThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    if (serviceOrderPollingEnabled) {
                        HttpURLConnection conn = null;
                        try {
                            SharedPreferences prefs = getSharedPreferences("poppy_prefs", MODE_PRIVATE);
                            int activeCourierId = prefs.getInt("courier_id", courierId);

                            String checkUrl = SERVER_API_URL + "?action=get_orders&courier_id=" + activeCourierId + "&t=" + System.currentTimeMillis();
                            URL url = new URL(checkUrl);
                            conn = (HttpURLConnection) url.openConnection();
                            conn.setRequestMethod("GET");
                            conn.setRequestProperty("Connection", "close");
                            conn.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate");
                            conn.setConnectTimeout(3000);
                            conn.setReadTimeout(3000);
                            conn.setUseCaches(false);

                            if (conn.getResponseCode() == 200) {
                                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                                StringBuilder sb = new StringBuilder();
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    sb.append(line);
                                }
                                reader.close();

                                JSONObject json = new JSONObject(sb.toString());
                                if ("ok".equals(json.optString("status"))) {
                                    JSONArray orders = json.optJSONArray("orders");
                                    Set<String> currentIds = new HashSet<>();
                                    String newCustomer = "";
                                    String newAddress = "";
                                    boolean hasNew = false;

                                    if (orders != null) {
                                        for (int i = 0; i < orders.length(); i++) {
                                            JSONObject o = orders.getJSONObject(i);
                                            String key = o.optString("order_table", "pos") + "_" + o.optInt("id", 0);
                                            currentIds.add(key);

                                            if (serviceFirstCheckDone && !serviceKnownOrderIds.contains(key)) {
                                                hasNew = true;
                                                newCustomer = o.optString("customer_name", "Yeni Sipariş");
                                                newAddress = o.optString("customer_address", "Paketi kontrol edin");
                                            }
                                        }
                                    }

                                    if (hasNew) {
                                        sendOrderAlert("Yeni Sipariş Geldi! 🛵", newCustomer + " - " + newAddress);
                                    }

                                    serviceKnownOrderIds = currentIds;
                                    serviceFirstCheckDone = true;
                                }
                            }
                        } catch (Exception ignored) {
                        } finally {
                            if (conn != null) {
                                conn.disconnect();
                            }
                        }
                    }

                    try {
                        Thread.sleep(2500);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        });
        orderThread.setPriority(Thread.MAX_PRIORITY);
        orderThread.setDaemon(true);
        orderThread.start();
    }

    private void sendOrderAlert(String title, String message) {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                PowerManager.WakeLock screenWake = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "PoppyCourier::ServiceScreenWake"
                );
                screenWake.acquire(5000);
            }
        } catch (Exception ignored) {
        }

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, flags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, ORDER_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
            builder.setPriority(Notification.PRIORITY_HIGH);
            builder.setDefaults(Notification.DEFAULT_ALL);
        }

        builder.setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle(title)
                .setContentText(message)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(Notification.CATEGORY_CALL);
            builder.setVisibility(Notification.VISIBILITY_PUBLIC);
        }

        manager.notify((int) System.currentTimeMillis(), builder.build());
    }

    @SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager != null) {
            try {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 8000, 8, this);
            } catch (Exception ignored) {
            }
            try {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 8000, 8, this);
            } catch (Exception ignored) {
            }
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
            } catch (Exception ignored) {
            }
        }).start();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // TELEFON AÇILDIĞINDA VEYA YENİDEN BAŞLADIĞINDA OTOMATİK DEVREYE GİREN ALICI
    public static class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            try {
                Intent serviceIntent = new Intent(context, GpsTrackerService.class);
                serviceIntent.putExtra("from_boot", true);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            } catch (Exception ignored) {
            }
        }
    }
}
