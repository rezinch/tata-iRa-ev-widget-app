package com.rezinch.irawidget

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import com.google.gson.annotations.SerializedName

object ApiService {
    private const val BASE_URL = "https://evcx.api.tatamotors"
    
    private val client = getUnsafeOkHttpClient()

    private fun getUnsafeOkHttpClient(): OkHttpClient {
        return try {
            val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
                object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                }
            )

            val sslContext = javax.net.ssl.SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            val sslSocketFactory = sslContext.socketFactory

            OkHttpClient.Builder()
                .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    data class RefreshTokenRequest(val refreshToken: String)
    
    data class RefreshTokenResult(
        @SerializedName("accessToken") val accessToken: String?,
        @SerializedName("expires_in") val expiresIn: String?
    )
    
    data class RefreshTokenResponse(
        @SerializedName("results") val results: RefreshTokenResult?
    )
    
    data class VehicleStateResult(
        val fuelRemaining: Double?,
        val speed: Double?,
        val ignitionOn: Boolean?,
        val acState: Boolean?,
        val vehicleInteriorTemperature: Double?,
        val odometerInMeters: Double?,
        val gpsLatitude: Double?,
        val gpsLongitude: Double?
    )
    
    data class VehicleHealthResult(
        val hvBatterySocPercentage: Double?,
        val distanceToEmpty: Double?,
        val hvChargingState: Boolean?,
        val timeToChargeHour: Int?,
        val timeToChargeMinute: Int?
    )
    
    data class VehicleStateResponse(
        @SerializedName("results") val results: VehicleStateResult?
    )
    
    data class VehicleHealthResponse(
        @SerializedName("results") val results: VehicleHealthResult?
    )

    data class AssetImage(
        val mobileImage: String?,
        val altText: String?,
        val alignment: String?
    )

    data class AssetList(
        val thumbnailImage: String?,
        val imageList: List<AssetImage>?
    )

    data class UserVehicle(
        val assetList: AssetList?
    )

    data class UserVehicleResults(
        val userVehicleList: List<UserVehicle>?
    )

    data class UserVehiclesResponse(
        val results: UserVehicleResults?
    )

    data class VehicleImageRequest(
        val additionalDriverVC: List<String> = emptyList(),
        val crmId: String,
        val mobile: String,
        val vehicleCategory: String = "TPEM"
    )

    suspend fun getUserVehicleImageUrl(
        accessToken: String, apiKey: String, crmId: String, mobile: String
    ): String? = withContext(Dispatchers.IO) {
        val reqBody = gson.toJson(VehicleImageRequest(crmId = crmId, mobile = mobile))
            .toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$BASE_URL/mobile/service/api/v3/get-user-vehicles-info-mobile")
            .post(reqBody)
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("client_id", "TMLEV-ANDROID-APP")
            .addHeader("x-api-key", apiKey)
            .addHeader("x-ownership-status", "true")
            .addHeader("App-Version", "26.3.1")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string()
                if (response.isSuccessful && bodyString != null) {
                    val parsed = gson.fromJson(bodyString, UserVehiclesResponse::class.java)
                    return@withContext parsed?.results?.userVehicleList?.firstOrNull()
                        ?.assetList?.thumbnailImage
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return@withContext null
    }

    suspend fun refreshToken(refreshToken: String, apiKey: String): Pair<RefreshTokenResponse?, String?> = withContext(Dispatchers.IO) {
        val reqBody = gson.toJson(RefreshTokenRequest(refreshToken)).toRequestBody(jsonMediaType)
        
        val request = Request.Builder()
            .url("$BASE_URL/mobile/customer/api/v1/refresh-token")
            .post(reqBody)
            .addHeader("client_id", "TMLEV-ANDROID-APP")
            .addHeader("client_secret", "ef167580-88df-4f6a-847d-039fa3c7e6fa")
            .addHeader("App-Version", "26.3.1")
            .addHeader("x-api-key", apiKey)
            .build()
            
        try {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string()
                if (response.isSuccessful && bodyString != null) {
                    val parsed = gson.fromJson(bodyString, RefreshTokenResponse::class.java)
                    if (parsed?.results?.accessToken != null) {
                        return@withContext Pair(parsed, null)
                    } else {
                        return@withContext Pair(null, "Unexpected JSON format (missing accessToken): $bodyString")
                    }
                } else {
                    return@withContext Pair(null, "HTTP ${response.code}: $bodyString")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext Pair(null, "Exception: ${e.message}")
        }
    }

    suspend fun getVehicleState(accessToken: String, vehicleId: String, apiKey: String): Pair<VehicleStateResponse?, String?> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/mobile/cvp/api/v1/ev/vehicle-state")
            .get()
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("client_id", "TMLEV-ANDROID-APP")
            .addHeader("vehicleId", vehicleId)
            .addHeader("x-api-key", apiKey)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string()
                if (response.isSuccessful && bodyString != null) {
                    try {
                        val parsed = gson.fromJson(bodyString, VehicleStateResponse::class.java)
                        if (parsed?.results != null) {
                            return@withContext Pair(parsed, null)
                        } else {
                            return@withContext Pair(null, "Parsing issue or missing results block: $bodyString")
                        }
                    } catch (e: Exception) {
                        return@withContext Pair(null, "JSON Parse Error on: $bodyString")
                    }
                } else {
                    return@withContext Pair(null, "HTTP ${response.code}: $bodyString")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext Pair(null, "Exception: ${e.message}")
        }
    }

    suspend fun getVehicleHealth(accessToken: String, vehicleId: String, apiKey: String): Pair<VehicleHealthResponse?, String?> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/mobile/cvp/api/v1/ev/vehicle-health")
            .get()
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("client_id", "TMLEV-ANDROID-APP")
            .addHeader("vehicleId", vehicleId)
            .addHeader("x-api-key", apiKey)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string()
                if (response.isSuccessful && bodyString != null) {
                    try {
                        val parsed = gson.fromJson(bodyString, VehicleHealthResponse::class.java)
                        if (parsed?.results != null) {
                            return@withContext Pair(parsed, null)
                        } else {
                            return@withContext Pair(null, "Parsing issue or missing results block: $bodyString")
                        }
                    } catch (e: Exception) {
                        return@withContext Pair(null, "JSON Parse Error on: $bodyString")
                    }
                } else {
                    return@withContext Pair(null, "HTTP ${response.code}: $bodyString")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext Pair(null, "Exception: ${e.message}")
        }
    }
}
