package com.poppy.courier;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.RingtoneManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    private WebView webView;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private static boolean isMonitorRunning = false;
    private static boolean isKeepAliveRunning = false;
    private static Set<String> knownOrderIds = new HashSet<>();
    private static boolean firstCheckDone = false;
    private static int currentCourierId = 0;

    private static final String CHANNEL_ID = "poppy_courier_orders_v5";
    private static final String APP_URL = "https://panel.poppyapartments.com/courier_api.php";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true);
                setTurnScreenOn(true);
            } else {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
            }
        } catch (Throwable ignored) {
        }

        System.setProperty("http.keepAlive", "false");

        SharedPreferences prefs = getSharedPreferences("poppy_prefs", MODE_PRIVATE);
        currentCourierId = prefs.getInt("courier_id", 0);

        keepCpuAndWifiAwake();
        startSilentAudioKeepAlive();
        createOrderNotificationChannel();
        requestAllRequiredPermissions();
        startCourierGpsServiceIfPermitted();

        webView = new WebView(this) {
            @Override
            protected void onWindowVisibilityChanged(int visibility) {
                super.onWindowVisibilityChanged(View.VISIBLE);
            }
        };
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.clearCache(true);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true);
        }

        WebAppInterface bridge = new WebAppInterface(this);
        webView.addJavascriptInterface(bridge, "AndroidBridge");
        webView.addJavascriptInterface(bridge, "PoppyAndroid");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("tel:") || url.startsWith("whatsapp:") || url.startsWith("geo:") || url.contains("maps.google.com") || url.contains("google.com/maps")) {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                    return true;
                }
                if (url.endsWith(".apk")) {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                    return true;
                }
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse(url));
                startActivity(intent);
            }
        });

        webView.loadUrl(APP_URL + "?t=" + System.currentTimeMillis());

        startNativeOrderMonitor(getApplicationContext());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Kullanıcı konum iznini onayladığı anda GPS servisini güvenli başlat
        startCourierGpsServiceIfPermitted();
    }

    private void startCourierGpsServiceIfPermitted() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                if (!fine && !coarse) return;
            }
            Intent serviceIntent = new Intent(this, GpsTrackerService.class);
            serviceIntent.putExtra("courier_id", currentCourierId);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Throwable ignored) {
        }
    }

    private void startSilentAudioKeepAlive() {
        if (isKeepAliveRunning) return;
        isKeepAliveRunning = true;

        Thread audioThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int sampleRate = 8000;
                    int bufSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    if (bufSize <= 0) bufSize = sampleRate;

                    AudioTrack track = new AudioTrack(
                            AudioManager.STREAM_MUSIC,
                            sampleRate,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufSize,
                            AudioTrack.MODE_STREAM
                    );
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        track.setVolume(0.0f);
                    } else {
                        track.setStereoVolume(0.0f, 0.0f);
                    }
                    track.play();

                    byte[] silence = new byte[bufSize];
                    while (true) {
                        track.write(silence, 0, silence.length);
                        Thread.sleep(1000);
                    }
                } catch (Throwable ignored) {
                }
            }
        });
        audioThread.setDaemon(true);
        audioThread.start();
    }

    private void startNativeOrderMonitor(final Context appContext) {
        if (isMonitorRunning) return;
        isMonitorRunning = true;

        Thread monitorThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    HttpURLConnection conn = null;
                    try {
                        String checkUrl = APP_URL + "?action=get_orders&courier_id=" + currentCourierId + "&t=" + System.currentTimeMillis();
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

                                        if (firstCheckDone && !knownOrderIds.contains(key)) {
                                            hasNew = true;
                                            newCustomer = o.optString("customer_name", "Yeni Sipariş");
                                            newAddress = o.optString("customer_address", "Paketi kontrol edin");
                                        }
                                    }
                                }

                                if (hasNew) {
                                    triggerNativeNotification(appContext, "Yeni Sipariş Geldi! 🛵", newCustomer + " - " + newAddress);
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (webView != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                                                webView.evaluateJavascript("if(typeof fetchOrders === 'function') { fetchOrders(); }", null);
                                            }
                                        }
                                    });
                                }

                                knownOrderIds = currentIds;
                                firstCheckDone = true;
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        if (conn != null) {
                            conn.disconnect();
                        }
                    }

                    try {
                        Thread.sleep(2500);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        });
        monitorThread.setPriority(Thread.MAX_PRIORITY);
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    private void triggerNativeNotification(Context context, String title, String message) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                PowerManager.WakeLock screenWake = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "PoppyCourier::OrderScreenWake"
                );
                screenWake.acquire(5000);
            }
        } catch (Throwable ignored) {
        }

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, flags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(context, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(context);
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

    private void keepCpuAndWifiAwake() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PoppyCourier::BackgroundWatchLock");
                wakeLock.acquire();
            }
        } catch (Throwable ignored) {
        }

        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PoppyCourier::WifiWatchLock");
                wifiLock.acquire();
            }
        } catch (Throwable ignored) {
        }
    }

    private void requestAllRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> permissionsNeeded = new ArrayList<>();

            if (Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS);
                }
            }

            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            }

            if (!permissionsNeeded.isEmpty()) {
                requestPermissions(permissionsNeeded.toArray(new String[0]), 101);
            }

            // Pil optimizasyonu muafiyeti
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
            } catch (Throwable ignored) {
            }

            // Telefon yeniden başladığında uygulamanın otomatik açılabilmesi için üstte gösterme izni
            try {
                if (!Settings.canDrawOverlays(this)) {
                    Intent overlayIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(overlayIntent);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void createOrderNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Kurye Anlık Sipariş Bildirimleri",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Gecikmesiz yeni paket uyarıları");
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 500, 200, 500});

            Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build();
            channel.setSound(soundUri, audioAttributes);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    public class WebAppInterface {
        Context mContext;

        WebAppInterface(Context c) {
            mContext = c;
        }

        @JavascriptInterface
        public void setCourier(int id, String name) {
            currentCourierId = id;
            getSharedPreferences("poppy_prefs", MODE_PRIVATE)
                    .edit()
                    .putInt("courier_id", id)
                    .apply();
            startCourierGpsServiceIfPermitted();
        }

        @JavascriptInterface
        public void sendNotification(String title, String message) {
            // Bildirimler Java arka plan motoru tarafından anlık gönderilir
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            moveTaskToBack(true);
        }
    }
}
