package com.rezinch.irawidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.view.View
import android.widget.RemoteViews
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.vehicle_widget)

            if (SecurityUtil.hasCredentials(context)) {
                val state = SecurityUtil.getVehicleState(context)

                // Battery
                val battPct = state.fuelRemaining.coerceIn(0, 100)
                views.setTextViewText(R.id.tvWidgetBattery, "${battPct}%")
                views.setProgressBar(R.id.pbBattery, 100, battPct, false)

                // Charging badge
                if (state.isCharging) {
                    views.setViewVisibility(R.id.ivChargingBadge, View.VISIBLE)
                    if (state.chargeTimeStr.isNotEmpty()) {
                        views.setViewVisibility(R.id.tvWidgetCharging, View.VISIBLE)
                        views.setTextViewText(R.id.tvWidgetCharging, "${state.chargeTimeStr} left")
                    } else {
                        views.setViewVisibility(R.id.tvWidgetCharging, View.GONE)
                    }
                } else {
                    views.setViewVisibility(R.id.ivChargingBadge, View.GONE)
                    views.setViewVisibility(R.id.tvWidgetCharging, View.GONE)
                }

                views.setTextViewText(R.id.tvWidgetSpeed, state.speed)
                views.setTextViewText(R.id.tvWidgetOdo, "${state.odometer} km")
                views.setTextViewText(R.id.tvWidgetTemp, "${state.interiorTemp}°C")
                views.setTextViewText(R.id.tvWidgetIgnition, if (state.ignitionOn) "ON" else "OFF")
                views.setTextViewText(R.id.tvWidgetLocation, state.location)

                val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
                val lastUpdateText = if (state.lastUpdate > 0) {
                    "Last sync: ${sdf.format(Date(state.lastUpdate))}"
                } else {
                    "Connecting..."
                }
                views.setTextViewText(R.id.tvWidgetUpdated, lastUpdateText)

                // Vehicle image (if opted in and cached)
                if (SecurityUtil.isShowImage(context)) {
                    val thumbFile = File(context.cacheDir, "vehicle_thumb.png")
                    if (thumbFile.exists()) {
                        val bitmap = BitmapFactory.decodeFile(thumbFile.absolutePath)
                        if (bitmap != null) {
                            views.setImageViewBitmap(R.id.ivVehicleImage, bitmap)
                            views.setViewVisibility(R.id.ivVehicleImage, View.VISIBLE)
                        } else {
                            views.setViewVisibility(R.id.ivVehicleImage, View.GONE)
                        }
                    } else {
                        views.setViewVisibility(R.id.ivVehicleImage, View.GONE)
                    }
                } else {
                    views.setViewVisibility(R.id.ivVehicleImage, View.GONE)
                }

            } else {
                views.setTextViewText(R.id.tvWidgetUpdated, "Setup required. Tap here.")
                views.setTextViewText(R.id.tvWidgetBattery, "--%")
                views.setTextViewText(R.id.tvWidgetSpeed, "--")
                views.setTextViewText(R.id.tvWidgetOdo, "-- km")
                views.setTextViewText(R.id.tvWidgetTemp, "--°C")
                views.setTextViewText(R.id.tvWidgetIgnition, "OFF")
                views.setTextViewText(R.id.tvWidgetLocation, "📍 Unknown")
                views.setViewVisibility(R.id.ivVehicleImage, View.GONE)
                views.setViewVisibility(R.id.ivChargingBadge, View.GONE)
                views.setViewVisibility(R.id.tvWidgetCharging, View.GONE)
            }

            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            // Setup manual refresh button
            val syncIntent = Intent(context, WidgetProvider::class.java).apply {
                action = ACTION_MANUAL_SYNC
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
            }
            val syncPendingIntent = PendingIntent.getBroadcast(
                context, appWidgetId, syncIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btnWidgetRefresh, syncPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        const val ACTION_MANUAL_SYNC = "com.rezinch.irawidget.MANUAL_SYNC"
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_MANUAL_SYNC) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
            if (ids != null) {
                val views = RemoteViews(context.packageName, R.layout.vehicle_widget)
                views.setTextViewText(R.id.tvWidgetUpdated, "Syncing...")
                for (id in ids) {
                    appWidgetManager.updateAppWidget(id, views)
                }

                val workRequest = androidx.work.OneTimeWorkRequestBuilder<VehicleUpdateWorker>()
                    .build()
                androidx.work.WorkManager.getInstance(context).enqueue(workRequest)
            }
        }
    }
}
