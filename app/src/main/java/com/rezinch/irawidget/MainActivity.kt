package com.rezinch.irawidget

import android.animation.ValueAnimator
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var etRefreshToken: TextInputEditText
    private lateinit var etVehicleId: TextInputEditText
    private lateinit var etApiKey: TextInputEditText
    private lateinit var switchShowImage: SwitchCompat
    private lateinit var layoutImageFields: LinearLayout
    private lateinit var etCrmId: TextInputEditText
    private lateinit var etMobile: TextInputEditText
    private lateinit var btnSave: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        etRefreshToken = findViewById(R.id.etRefreshToken)
        etVehicleId = findViewById(R.id.etVehicleId)
        etApiKey = findViewById(R.id.etApiKey)
        switchShowImage = findViewById(R.id.switchShowImage)
        layoutImageFields = findViewById(R.id.layoutImageFields)
        etCrmId = findViewById(R.id.etCrmId)
        etMobile = findViewById(R.id.etMobile)
        btnSave = findViewById(R.id.btnSave)
        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)

        // Pre-fill existing values
        if (SecurityUtil.hasCredentials(this)) {
            etRefreshToken.setText(SecurityUtil.getRefreshToken(this))
            etVehicleId.setText(SecurityUtil.getVehicleId(this))
            etApiKey.setText(SecurityUtil.getApiKey(this))
        }

        val showImage = SecurityUtil.isShowImage(this)
        switchShowImage.isChecked = showImage
        if (showImage) {
            layoutImageFields.visibility = View.VISIBLE
            etCrmId.setText(SecurityUtil.getCrmId(this) ?: "")
            etMobile.setText(SecurityUtil.getMobile(this) ?: "")
        }

        // Toggle image fields visibility with smooth animation
        switchShowImage.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                layoutImageFields.visibility = View.VISIBLE
                layoutImageFields.alpha = 0f
                layoutImageFields.animate().alpha(1f).setDuration(250).start()
            } else {
                layoutImageFields.animate().alpha(0f).setDuration(200).withEndAction {
                    layoutImageFields.visibility = View.GONE
                }.start()
            }
        }

        btnSave.setOnClickListener {
            val token = etRefreshToken.text.toString().trim()
            val vehicle = etVehicleId.text.toString().trim()
            val api = etApiKey.text.toString().trim()

            if (token.isEmpty() || vehicle.isEmpty() || api.isEmpty()) {
                tvStatus.text = "Please fill in all fields"
                tvStatus.setTextColor(getColor(R.color.error_red))
                return@setOnClickListener
            }

            if (switchShowImage.isChecked) {
                val crmId = etCrmId.text.toString().trim()
                val mobile = etMobile.text.toString().trim()
                if (crmId.isEmpty() || mobile.isEmpty()) {
                    tvStatus.text = "Please fill in CRM ID and Mobile for vehicle image"
                    tvStatus.setTextColor(getColor(R.color.error_red))
                    return@setOnClickListener
                }
            }

            testConnectionAndSave(token, vehicle, api)
        }
    }

    private fun testConnectionAndSave(refreshToken: String, vehicleId: String, apiKey: String) {
        btnSave.isEnabled = false
        progressBar.visibility = View.VISIBLE
        tvStatus.text = "Testing connection..."
        tvStatus.setTextColor(getColor(R.color.widget_text_secondary))

        lifecycleScope.launch {
            val (response, errMessage) = ApiService.refreshToken(refreshToken, apiKey)
            if (response?.results?.accessToken != null) {
                SecurityUtil.saveCredentials(this@MainActivity, refreshToken, vehicleId, apiKey)

                val expiresInSecs = response.results.expiresIn?.toIntOrNull() ?: 3600
                SecurityUtil.saveAccessToken(this@MainActivity, response.results.accessToken, expiresInSecs)

                // Save image settings
                val showImage = switchShowImage.isChecked
                val crmId = etCrmId.text.toString().trim()
                val mobile = etMobile.text.toString().trim()
                SecurityUtil.saveImageSettings(this@MainActivity, showImage, crmId, mobile)
                // Clear cached image URL so it re-fetches with new settings
                if (!showImage) SecurityUtil.saveVehicleImageUrl(this@MainActivity, "")

                tvStatus.text = "✓ Connection successful! Widget is syncing..."
                tvStatus.setTextColor(getColor(R.color.tata_teal))

                scheduleBackgroundWorker()
            } else {
                tvStatus.text = "Failed: $errMessage"
                tvStatus.setTextColor(getColor(R.color.error_red))
            }
            btnSave.isEnabled = true
            progressBar.visibility = View.GONE
        }
    }

    private fun scheduleBackgroundWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicWork = PeriodicWorkRequestBuilder<VehicleUpdateWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "VehicleUpdateWorker",
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicWork
        )

        val immediateWork = OneTimeWorkRequestBuilder<VehicleUpdateWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueue(immediateWork)
    }
}