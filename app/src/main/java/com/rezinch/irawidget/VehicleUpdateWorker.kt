package com.rezinch.irawidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class VehicleUpdateWorker(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!SecurityUtil.hasCredentials(context)) {
            Log.e("VehicleUpdateWorker", "Missing credentials")
            return@withContext Result.failure()
        }

        val refreshToken = SecurityUtil.getRefreshToken(context)!!
        val vehicleId = SecurityUtil.getVehicleId(context)!!
        val apiKey = SecurityUtil.getApiKey(context)!!

        var accessToken = SecurityUtil.getValidAccessToken(context)

        if (accessToken == null) {
            val (refreshResponse, _) = ApiService.refreshToken(refreshToken, apiKey)
            if (refreshResponse?.results?.accessToken != null) {
                accessToken = refreshResponse.results.accessToken
                val expiresInSecs = refreshResponse.results.expiresIn?.toIntOrNull() ?: 3600
                SecurityUtil.saveAccessToken(context, accessToken, expiresInSecs)
            } else {
                Log.e("VehicleUpdateWorker", "Failed to refresh token")
                return@withContext Result.retry()
            }
        }

        val (stateResponse, errStr1) = ApiService.getVehicleState(accessToken!!, vehicleId, apiKey)
        val (healthResponse, errStr2) = ApiService.getVehicleHealth(accessToken, vehicleId, apiKey)
        
        if (stateResponse?.results != null && healthResponse?.results != null) {
            val sResults = stateResponse.results
            val hResults = healthResponse.results
            
            val odoKm = try {
                val meters = sResults.odometerInMeters ?: 0.0
                (meters / 1000).toInt().toString()
            } catch (e: Exception) {
                "--"
            }

            val rangeStr = (hResults.distanceToEmpty?.toInt()?.toString() ?: "--") + " km"

            var locationText = "Searching..."
            if (sResults.gpsLatitude != null && sResults.gpsLongitude != null) {
                val lat = sResults.gpsLatitude
                val lng = sResults.gpsLongitude
                try {
                    val geocoder = Geocoder(context, Locale.getDefault())
                    val addresses: MutableList<Address>? = geocoder.getFromLocation(lat, lng, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val address = addresses[0]
                        val city = address.locality ?: address.subAdminArea ?: address.adminArea
                        val street = address.thoroughfare ?: address.subLocality ?: address.featureName
                        locationText = if (city != null && street != null) "$street, $city" 
                                       else address.getAddressLine(0) ?: String.format("%.4f, %.4f", lat, lng)
                    } else {
                        throw Exception("Geocoder empty")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    // Fallback to nominatim
                    try {
                        val req = okhttp3.Request.Builder()
                            .url("https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lng&zoom=14")
                            .header("User-Agent", "iRaWidget/1.0")
                            .build()
                        okhttp3.OkHttpClient().newCall(req).execute().use { res ->
                            val body = res.body?.string()
                            if (res.isSuccessful && body != null) {
                                val json = org.json.JSONObject(body)
                                if (json.has("display_name")) {
                                    val parts = json.getString("display_name").split(",")
                                    locationText = if (parts.size >= 2) "${parts[0].trim()}, ${parts[1].trim()}" else parts[0].trim()
                                } else {
                                    locationText = String.format("%.4f, %.4f", lat, lng)
                                }
                            } else {
                                locationText = String.format("%.4f, %.4f", lat, lng)
                            }
                        }
                    } catch (e2: Exception) {
                        e2.printStackTrace()
                        locationText = String.format("%.4f, %.4f", lat, lng)
                    }
                }
            }

            val isCharging = hResults.hvChargingState ?: false
            val hr = hResults.timeToChargeHour ?: 0
            val min = hResults.timeToChargeMinute ?: 0
            val chargeTimeStr = if (hr > 0 || min > 0) "${hr}h ${min}m" else ""

            SecurityUtil.saveVehicleState(
                context = context,
                fuelRemaining = hResults.hvBatterySocPercentage?.toInt() ?: 0,
                speed = rangeStr, 
                ignitionOn = sResults.ignitionOn ?: false,
                acState = sResults.acState ?: false,
                interiorTemp = sResults.vehicleInteriorTemperature?.toString() ?: "--",
                odometerRaw = odoKm,
                location = locationText,
                isCharging = isCharging,
                chargeTimeStr = chargeTimeStr,
                lastUpdate = System.currentTimeMillis()
            )
        } else {
            val err = errStr1 ?: errStr2 ?: "Unknown Error / Missing Results"
            SecurityUtil.saveVehicleState(
                context = context,
                fuelRemaining = -1,
                speed = "--",
                ignitionOn = false,
                acState = false,
                interiorTemp = "--",
                odometerRaw = "--",
                location = "Err: $err",
                isCharging = false,
                chargeTimeStr = "",
                lastUpdate = -1L
            )
        }

        // Fetch vehicle image if user has opted in
        if (SecurityUtil.isShowImage(context)) {
            val crmId = SecurityUtil.getCrmId(context)
            val mobile = SecurityUtil.getMobile(context)
            val cachedUrl = SecurityUtil.getVehicleImageUrl(context)
            // Only re-fetch URL if not already cached
            if (cachedUrl == null && crmId != null && mobile != null) {
                val imageUrl = ApiService.getUserVehicleImageUrl(accessToken!!, apiKey, crmId, mobile)
                if (imageUrl != null) {
                    SecurityUtil.saveVehicleImageUrl(context, imageUrl)
                }
            }
            // Download & cache the bitmap
            val urlToDownload = SecurityUtil.getVehicleImageUrl(context)
            if (urlToDownload != null) {
                try {
                    val imgRequest = okhttp3.Request.Builder().url(urlToDownload).build()
                    okhttp3.OkHttpClient().newCall(imgRequest).execute().use { imgRes ->
                        val bytes = imgRes.body?.bytes()
                        if (bytes != null) {
                            val file = java.io.File(context.cacheDir, "vehicle_thumb.png")
                            file.writeBytes(bytes)
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }

        // Trigger widget update broadcast
        val intent = Intent(context, WidgetProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            val ids = AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, WidgetProvider::class.java))
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        }
        context.sendBroadcast(intent)

        return@withContext Result.success()
    }
}
