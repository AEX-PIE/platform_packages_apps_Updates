/*
 * Copyright (C) 2012 The CyanogenMod Project
 * Copyright (C) 2017 The LineageOS Project
 * Copyright (C) 2018 Pixel Experience (jhenrique09)
 *
 * * Licensed under the GNU GPLv2 license
 *
 * The text of the license can be found in the LICENSE file
 * or at https://www.gnu.org/licenses/gpl-2.0.txt
 */
package com.aospextended.ota.service;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.VolleyLog;

import org.json.JSONException;
import org.json.JSONObject;
import com.aospextended.ota.R;
import com.aospextended.ota.UpdaterApplication;
import com.aospextended.ota.activities.UpdaterActivity;
import com.aospextended.ota.misc.Constants;
import com.aospextended.ota.misc.State;
import com.aospextended.ota.misc.UpdateInfo;
import com.aospextended.ota.requests.UpdatesJsonObjectRequest;
import com.aospextended.ota.utils.Utils;

import java.net.URI;
import java.util.Date;
import java.util.LinkedList;

public class UpdateCheckService extends IntentService
        implements Response.ErrorListener, Response.Listener<JSONObject> {

    // request actions
    public static final String ACTION_CHECK = "com.aospextended.ota.action.CHECK";
    public static final String ACTION_CANCEL_CHECK = "com.aospextended.ota.action.CANCEL_CHECK";
    // broadcast actions
    public static final String ACTION_CHECK_FINISHED = "com.aospextended.ota.action.UPDATE_CHECK_FINISHED";
    // extra for ACTION_CHECK_FINISHED: total amount of found updates
    public static final String EXTRA_UPDATE_COUNT = "update_count";
    // extra for ACTION_CHECK_FINISHED: amount of updates that are newer than what is installed
    public static final String EXTRA_REAL_UPDATE_COUNT = "real_update_count";
    // extra for ACTION_CHECK_FINISHED: amount of updates that were found for the first time
    public static final String EXTRA_NEW_UPDATE_COUNT = "new_update_count";
    private static final String TAG = "UpdateCheckService";
    // max. number of updates listed in the expanded notification
    private static final int EXPANDED_NOTIF_UPDATE_COUNT = 4;

    // DefaultRetryPolicy values for Volley
    private static final int UPDATE_REQUEST_TIMEOUT = 5000; // 5 seconds
    private static final int UPDATE_REQUEST_MAX_RETRIES = 3;

    public UpdateCheckService() {
        super("UpdateCheckService");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (TextUtils.equals(intent.getAction(), ACTION_CANCEL_CHECK)) {
            ((UpdaterApplication) getApplicationContext()).getQueue().cancelAll(TAG);
            return START_NOT_STICKY;
        }

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (!Utils.isOnline(this)) {
            // Only check for updates if the device is actually connected to a network
            Log.i(TAG, "Could not check for updates. Not connected to the network.");
            return;
        }
        getAvailableUpdates();
    }

    private void recordAvailableUpdates(LinkedList<UpdateInfo> availableUpdates,
                                        Intent finishedIntent) {

        if (availableUpdates == null) {
            sendBroadcast(finishedIntent);
            return;
        }

        // Store the last update check time and ensure boot check completed is true
        Date d = new Date();
        PreferenceManager.getDefaultSharedPreferences(UpdateCheckService.this).edit()
                .putLong(Constants.LAST_UPDATE_CHECK_PREF, d.getTime())
                .putBoolean(Constants.BOOT_CHECK_COMPLETED, true)
                .apply();

        int realUpdateCount = finishedIntent.getIntExtra(EXTRA_REAL_UPDATE_COUNT, 0);
        UpdaterApplication app = (UpdaterApplication) getApplicationContext();

        // Write to log
        Log.i(TAG, "The update check successfully completed at " + d + " and found "
                + availableUpdates.size() + " updates ("
                + realUpdateCount + " newer than installed)");

        if (realUpdateCount != 0 && !app.isMainActivityActive()) {
            // There are updates available
            // The notification should launch the main app
            Intent i = new Intent(this, UpdaterActivity.class);
            i.putExtra(Constants.EXTRA_UPDATE_LIST_UPDATED, true);
            PendingIntent contentIntent = PendingIntent.getActivity(this, 0, i,
                    PendingIntent.FLAG_ONE_SHOT);

            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

            Resources res = getResources();
            String text = getString(R.string.update_found_notification);

            // Get the notification ready
            CharSequence name = getString(R.string.app_name);
            NotificationChannel mChannel = new NotificationChannel(Constants.DOWNLOAD_CHANNEL_ID, name, NotificationManager.IMPORTANCE_HIGH);
            mChannel.enableLights(true);
            mChannel.setLightColor(Color.GREEN);
            mChannel.setShowBadge(true);
            mChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(mChannel);
            Notification.Builder builder = new Notification.Builder(this, Constants.DOWNLOAD_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_system_update)
                    .setWhen(System.currentTimeMillis())
                    .setTicker(text)
                    .setContentTitle(text)
                    .setContentText(getString(R.string.update_found_notification_desc))
                    .setContentIntent(contentIntent)
                    .setLocalOnly(true)
                    .setAutoCancel(true);

            LinkedList<UpdateInfo> realUpdates = new LinkedList<>();
            for (UpdateInfo ui : availableUpdates) {
                if (ui.isNewerThanInstalled()) {
                    realUpdates.add(ui);
                }
            }

            Notification.InboxStyle inbox = new Notification.InboxStyle()
                    .setBigContentTitle(text);
            int added = 0;

            for (UpdateInfo ui : realUpdates) {
                if (added < EXPANDED_NOTIF_UPDATE_COUNT) {
                    inbox.addLine(ui.getFileName());
                    added++;
                }
            }
            builder.setStyle(inbox);
            builder.setNumber(availableUpdates.size());

            // Trigger the notification
            nm.notify(R.string.update_found_notification, builder.build());
        }

        sendBroadcast(finishedIntent);
    }

    private URI getServerURI() {
        return URI.create(String.format(Constants.OTA_URL, Utils.getDeviceName()));
    }

    private void getAvailableUpdates() {

        // Get the actual ROM Update Server URL
        URI updateServerUri = getServerURI();
        UpdatesJsonObjectRequest request;
        request = new UpdatesJsonObjectRequest(updateServerUri.toASCIIString(),
                Utils.getUserAgentString(this), null, this, this);
        // Improve request error tolerance
        request.setRetryPolicy(new DefaultRetryPolicy(UPDATE_REQUEST_TIMEOUT,
                UPDATE_REQUEST_MAX_RETRIES, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));
        // Set the tag for the request, reuse logging tag
        request.setTag(TAG);

        ((UpdaterApplication) getApplicationContext()).getQueue().add(request);
    }

    private LinkedList<UpdateInfo> parseJSON(String jsonString) {
        LinkedList<UpdateInfo> updates = new LinkedList<>();
        try {
            JSONObject obj = new JSONObject(jsonString);

            String addons;

            try {
                addons = obj.getJSONArray("addons").toString();
            } catch (Exception e2) {
                addons = "[]";
            }

            UpdateInfo ui = new UpdateInfo.Builder()
                    .setFileName(obj.getString("filename"))
                    .setFilesize(obj.getLong("filesize"))
                    .setBuildDate(obj.getString("build_date"))
                    .setMD5(obj.getString("md5"))
                    .setDeveloper(obj.isNull("developer") ? "" : obj.getString("developer"))
                    .setDeveloperUrl(obj.isNull("developer_url") ? "" : obj.getString("developer_url"))
                    .setDownloadUrl(obj.getString("url"))
                    .setChangelog(obj.isNull("changelog") ? "" : obj.getString("changelog"))
                    .setDonateUrl(obj.isNull("donate_url") ? "" : obj.getString("donate_url"))
                    .setForumUrl(obj.isNull("forum_url") ? "" : obj.getString("forum_url"))
                    .setWebsiteUrl(obj.isNull("website_url") ? "" : obj.getString("website_url"))
                    .setNewsUrl(obj.isNull("news_url") ? "" : obj.getString("news_url"))
                    .setAddons(addons)
                    .build();
            updates.add(ui);
        } catch (JSONException e) {
            Log.e(TAG, "Error in JSON result", e);
        }
        return updates;
    }

    @Override
    public void onErrorResponse(VolleyError volleyError) {
        VolleyLog.e("Error: ", volleyError.getMessage());
        VolleyLog.e("Error type: " + volleyError.toString());
        Intent intent = new Intent(ACTION_CHECK_FINISHED);
        sendBroadcast(intent);
    }

    @Override
    public void onResponse(JSONObject jsonObject) {
        LinkedList<UpdateInfo> updates = parseJSON(jsonObject.toString());

        int newUpdates = 0, realUpdates = 0;
        for (UpdateInfo ui : updates) {
            if (ui.isNewerThanInstalled()) {
                realUpdates++;
                newUpdates++;
            }
        }

        Intent intent = new Intent(ACTION_CHECK_FINISHED);
        intent.putExtra(EXTRA_UPDATE_COUNT, updates.size());
        intent.putExtra(EXTRA_REAL_UPDATE_COUNT, realUpdates);
        intent.putExtra(EXTRA_NEW_UPDATE_COUNT, newUpdates);

        recordAvailableUpdates(updates, intent);
        State.saveState(this, updates);
    }
}
