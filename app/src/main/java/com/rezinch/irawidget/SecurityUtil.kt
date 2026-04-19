package com.rezinch.irawidget

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SecurityUtil {
    private const val PREF_NAME = "secure_prefs"

    // Credentials
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_VEHICLE_ID = "vehicle_id"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_TOKEN_EXPIRY = "token_expiry"

    // Vehicle Image (optional)
    private const val KEY_SHOW_IMAGE = "show_image"
    private const val KEY_CRM_ID = "crm_id"
    private const val KEY_MOBILE = "mobile"
    private const val KEY_VEHICLE_IMAGE_URL = "vehicle_image_url"

    // Widget Data
    private const val KEY_FUEL_REMAINING = "fuel_remaining"
    private const val KEY_SPEED = "speed"
    private const val KEY_IGNITION_ON = "ignition_on"
    private const val KEY_AC_STATE = "ac_state"
    private const val KEY_INTERIOR_TEMP = "interior_temp"
    private const val KEY_ODOMETER = "odometer"
    private const val KEY_LOCATION = "location"
    private const val KEY_LAST_UPDATE = "last_update"

    private fun getPrefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREF_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveCredentials(context: Context, refreshToken: String, vehicleId: String, apiKey: String) {
        getPrefs(context).edit()
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putString(KEY_VEHICLE_ID, vehicleId)
            .putString(KEY_API_KEY, apiKey)
            .apply()
    }

    fun saveImageSettings(context: Context, showImage: Boolean, crmId: String, mobile: String) {
        getPrefs(context).edit()
            .putBoolean(KEY_SHOW_IMAGE, showImage)
            .putString(KEY_CRM_ID, crmId)
            .putString(KEY_MOBILE, mobile)
            .apply()
    }

    fun isShowImage(context: Context): Boolean = getPrefs(context).getBoolean(KEY_SHOW_IMAGE, false)
    fun getCrmId(context: Context): String? = getPrefs(context).getString(KEY_CRM_ID, null)
    fun getMobile(context: Context): String? = getPrefs(context).getString(KEY_MOBILE, null)

    fun saveVehicleImageUrl(context: Context, url: String) {
        getPrefs(context).edit().putString(KEY_VEHICLE_IMAGE_URL, url).apply()
    }
    fun getVehicleImageUrl(context: Context): String? = getPrefs(context).getString(KEY_VEHICLE_IMAGE_URL, null)

    fun hasCredentials(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.getString(KEY_REFRESH_TOKEN, null) != null &&
               prefs.getString(KEY_VEHICLE_ID, null) != null &&
               prefs.getString(KEY_API_KEY, null) != null
    }

    fun getRefreshToken(context: Context): String? = getPrefs(context).getString(KEY_REFRESH_TOKEN, null)
    fun getVehicleId(context: Context): String? = getPrefs(context).getString(KEY_VEHICLE_ID, null)
    fun getApiKey(context: Context): String? = getPrefs(context).getString(KEY_API_KEY, null)

    fun saveAccessToken(context: Context, token: String, expiresInSecs: Int) {
        val expiryTime = System.currentTimeMillis() + (expiresInSecs * 1000L) - 60000L // 1 minute buffer
        getPrefs(context).edit()
            .putString(KEY_ACCESS_TOKEN, token)
            .putLong(KEY_TOKEN_EXPIRY, expiryTime)
            .apply()
    }

    fun getValidAccessToken(context: Context): String? {
        val prefs = getPrefs(context)
        val token = prefs.getString(KEY_ACCESS_TOKEN, null)
        val expiry = prefs.getLong(KEY_TOKEN_EXPIRY, 0)
        if (token != null && System.currentTimeMillis() < expiry) {
            return token
        }
        return null
    }

    private const val KEY_IS_CHARGING = "is_charging"
    private const val KEY_CHARGE_TIME = "charge_time"

    fun saveVehicleState(
        context: Context,
        fuelRemaining: Int,
        speed: String,
        ignitionOn: Boolean,
        acState: Boolean,
        interiorTemp: String,
        odometerRaw: String,
        location: String,
        isCharging: Boolean,
        chargeTimeStr: String,
        lastUpdate: Long
    ) {
        getPrefs(context).edit()
            .putInt(KEY_FUEL_REMAINING, fuelRemaining)
            .putString(KEY_SPEED, speed)
            .putBoolean(KEY_IGNITION_ON, ignitionOn)
            .putBoolean(KEY_AC_STATE, acState)
            .putString(KEY_INTERIOR_TEMP, interiorTemp)
            .putString(KEY_ODOMETER, odometerRaw)
            .putString(KEY_LOCATION, location)
            .putBoolean(KEY_IS_CHARGING, isCharging)
            .putString(KEY_CHARGE_TIME, chargeTimeStr)
            .putLong(KEY_LAST_UPDATE, lastUpdate)
            .apply()
    }

    data class VehicleState(
        val fuelRemaining: Int,
        val speed: String,
        val ignitionOn: Boolean,
        val acState: Boolean,
        val interiorTemp: String,
        val odometer: String,
        val location: String,
        val isCharging: Boolean,
        val chargeTimeStr: String,
        val lastUpdate: Long
    )

    fun getVehicleState(context: Context): VehicleState {
        val prefs = getPrefs(context)
        return VehicleState(
            fuelRemaining = prefs.getInt(KEY_FUEL_REMAINING, -1),
            speed = prefs.getString(KEY_SPEED, "--") ?: "--",
            ignitionOn = prefs.getBoolean(KEY_IGNITION_ON, false),
            acState = prefs.getBoolean(KEY_AC_STATE, false),
            interiorTemp = prefs.getString(KEY_INTERIOR_TEMP, "--") ?: "--",
            odometer = prefs.getString(KEY_ODOMETER, "--") ?: "--",
            location = prefs.getString(KEY_LOCATION, "Unknown") ?: "Unknown",
            isCharging = prefs.getBoolean(KEY_IS_CHARGING, false),
            chargeTimeStr = prefs.getString(KEY_CHARGE_TIME, "") ?: "",
            lastUpdate = prefs.getLong(KEY_LAST_UPDATE, 0L)
        )
    }
}
