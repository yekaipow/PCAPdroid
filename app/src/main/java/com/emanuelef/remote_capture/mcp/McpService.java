/*
 * This file is part of PCAPdroid.
 *
 * PCAPdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PCAPdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PCAPdroid. If not, see <http://www.gnu.org/licenses/>.
 */

package com.emanuelef.remote_capture.mcp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.activities.MainActivity;
import com.emanuelef.remote_capture.model.Prefs;

import java.io.IOException;

/* Foreground service hosting the MCP server. Keeping it in the foreground ensures the
 * HTTP endpoint remains available while a capture is running in the background.
 */
public class McpService extends Service {
    private static final String TAG = "McpService";
    private static final String NOTIFY_CHAN_MCP = "mcp_server";
    private static final int NOTIFY_ID_MCP = 20;

    private McpServer mServer;

    public static void start(Context context) {
        Intent intent = new Intent(context, McpService.class);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, McpService.class));
    }

    /* Whether the MCP server should be running according to the app settings. */
    public static boolean isEnabled(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return Prefs.isMcpServerEnabled(prefs);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        setupNotificationChannel();
        startForegroundNotification();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        startForegroundNotification();
        startServer();
        // Restart the service if it gets killed, as long as it is enabled
        return START_STICKY;
    }

    private void setupNotificationChannel() {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel chan = new NotificationChannel(NOTIFY_CHAN_MCP,
                    getString(R.string.mcp_server), NotificationManager.IMPORTANCE_LOW);
            chan.setShowBadge(false);
            nm.createNotificationChannel(chan);
        }
    }

    private void startForegroundNotification() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), Utils.getIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT));
        Notification notification = new NotificationCompat.Builder(this, NOTIFY_CHAN_MCP)
                .setSmallIcon(R.drawable.ic_logo)
                .setColor(ContextCompat.getColor(this, R.color.colorPrimary))
                .setContentIntent(pi)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentTitle(getString(R.string.mcp_server))
                .setContentText(getString(R.string.mcp_notification_text))
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startForeground(NOTIFY_ID_MCP, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else
            startForeground(NOTIFY_ID_MCP, notification);
    }

    private void startServer() {
        if(mServer != null)
            return;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int port = Prefs.getMcpServerPort(prefs);
        boolean bindAll = Prefs.isMcpServerBindAll(prefs);
        String token = Prefs.getMcpServerToken(prefs);

        mServer = new McpServer(this, port, bindAll, token);
        try {
            mServer.start();
        } catch(IOException e) {
            Log.e(TAG, "Could not start MCP server: " + e.getLocalizedMessage());
            mServer = null;
        }
    }

    @Override
    public void onDestroy() {
        if(mServer != null) {
            mServer.stop();
            mServer = null;
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
