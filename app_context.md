# iRA.ev Widget — Full App Context

## What This App Is

An **Android homescreen widget** (5×2 cells wide) for **Tata Motors EV owners** that shows live vehicle telemetry fetched directly from the Tata iRA.ev API. The app itself is just a setup screen; 99% of the experience is the widget on the homescreen.

**Package ID:** `com.rezinch.irawidget`  
**Target SDK:** Android 12+ (API 31), min SDK varies  
**Language:** Kotlin  
**Build system:** Gradle

---

## File Structure (Key Files Only)

```
app/src/main/
├── AndroidManifest.xml
├── java/com/rezinch/irawidget/
│   ├── MainActivity.kt           ← Setup screen (one-time credential entry)
│   ├── ApiService.kt             ← All HTTP calls to Tata API
│   ├── VehicleUpdateWorker.kt    ← Background sync worker (WorkManager)
│   ├── WidgetProvider.kt         ← AppWidgetProvider, draws the widget UI
│   └── SecurityUtil.kt           ← EncryptedSharedPreferences wrapper
└── res/
    ├── layout/
    │   ├── activity_main.xml     ← Setup screen UI
    │   └── vehicle_widget.xml    ← Widget layout
    ├── drawable/
    │   ├── widget_bg.xml         ← Glassmorphic dark background
    │   ├── battery_progress.xml  ← Progress bar drawable (teal→green gradient)
    │   ├── ic_charging.xml       ← Speed-lines + battery bolt vector icon
    │   ├── ic_road.xml           ← Highway icon for odometer
    │   ├── ic_range.xml          ← Concentric rings icon for range
    │   ├── ic_temp_gauge.xml     ← Gauge + thermometer for cabin temp
    │   ├── ic_pin.xml            ← Location pin for footer
    │   └── ic_refresh.xml        ← Refresh/sync icon
    └── xml/
        └── vehicle_widget_info.xml  ← Widget metadata (5×2, minWidth=320dp)
```

---

## Dependencies (app/build.gradle)

```kotlin
implementation("com.squareup.okhttp3:okhttp:4.12.0")       // HTTP client
implementation("com.google.code.gson:gson:2.10.1")          // JSON parsing
implementation("androidx.work:work-runtime-ktx:2.9.0")      // WorkManager
implementation("androidx.security:security-crypto:1.1.0-alpha06") // EncryptedSharedPreferences
implementation("com.google.android.material:material:...")  // Material TextInputLayout
implementation("androidx.appcompat:appcompat:...")          // SwitchCompat
```

---

## AndroidManifest Permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

The `WidgetProvider` is declared as a `<receiver>` with `android.appwidget.action.APPWIDGET_UPDATE` intent filter and points to `@xml/vehicle_widget_info`.

---

## API — Base URL & Authentication

**Base URL:** `https://evcx.api.tatamotors`

All requests use a **custom OkHttpClient that bypasses SSL certificate validation** (self-signed cert on Tata's server). This is done via a trust-all `X509TrustManager`.

### Authentication Flow

1. **User provides** at setup: `refreshToken`, `vehicleId`, `x-api-key`
2. **Access token obtained** by calling `POST /mobile/customer/api/v1/refresh-token`
   - Body: `{ "refreshToken": "<token>" }`
   - Headers: `client_id: TMLEV-ANDROID-APP`, `client_secret: ef167580-88df-4f6a-847d-039fa3c7e6fa`, `x-api-key: <key>`, `App-Version: 26.3.1`
   - Response: `{ "results": { "accessToken": "...", "expires_in": "3600" } }`
3. Access token stored in `EncryptedSharedPreferences` with expiry timestamp (API value minus 60s buffer)
4. On every sync, `getValidAccessToken()` checks if stored token is still valid; if not, re-fetches

---

## API Endpoints

### 1. Vehicle State
`GET /mobile/cvp/api/v1/ev/vehicle-state`  
Headers: `Authorization: Bearer <accessToken>`, `vehicleId: <id>`, `x-api-key: <key>`

Response fields used:
```json
{
  "results": {
    "ignitionOn": true,
    "acState": false,
    "vehicleInteriorTemperature": 28.0,
    "odometerInMeters": 479000.0,
    "gpsLatitude": 11.1234,
    "gpsLongitude": 76.5678
  }
}
```

### 2. Vehicle Health
`GET /mobile/cvp/api/v1/ev/vehicle-health`  
Headers: same as above

Response fields used:
```json
{
  "results": {
    "hvBatterySocPercentage": 82.0,
    "distanceToEmpty": 164.0,
    "hvChargingState": true,
    "timeToChargeHour": 1,
    "timeToChargeMinute": 30
  }
}
```

### 3. Vehicle Image (Optional)
`POST /mobile/service/api/v3/get-user-vehicles-info-mobile`  
Headers: `Authorization: Bearer <accessToken>`, `client_id: TMLEV-ANDROID-APP`, `x-ownership-status: true`, `x-api-key: <key>`, `App-Version: 26.3.1`

Body:
```json
{
  "additionalDriverVC": [],
  "crmId": "<user's CRM ID>",
  "mobile": "<user's mobile>",
  "vehicleCategory": "TPEM"
}
```

Response path used: `results.userVehicleList[0].assetList.thumbnailImage` → URL string

---

## SecurityUtil.kt — Encrypted Storage

All data stored in `EncryptedSharedPreferences` (AES256-GCM). Keys stored:

| Key | Type | Description |
|-----|------|-------------|
| `refresh_token` | String | Tata iRA refresh token (permanent) |
| `vehicle_id` | String | Vehicle ID (e.g. MAT568010TKB16823) |
| `api_key` | String | x-api-key header value |
| `access_token` | String | Short-lived Bearer token |
| `token_expiry` | Long | Unix ms timestamp of accessToken expiry |
| `show_image` | Boolean | Whether user enabled vehicle photo |
| `crm_id` | String | CRM ID for vehicle image API |
| `mobile` | String | Mobile number for vehicle image API |
| `vehicle_image_url` | String | Cached Scene7 thumbnail URL |
| `fuel_remaining` | Int | Battery SoC % (last synced) |
| `speed` | String | Reused for Range string (e.g. "164 km") |
| `ignition_on` | Boolean | Ignition state |
| `ac_state` | Boolean | AC on/off |
| `interior_temp` | String | Cabin temperature |
| `odometer` | String | Odometer in km (converted from meters) |
| `location` | String | Human-readable location string |
| `is_charging` | Boolean | Is currently charging |
| `charge_time` | String | e.g. "1h 30m" |
| `last_update` | Long | Unix ms of last successful sync |

---

## MainActivity.kt — Setup Screen

A `ScrollView` with a dark `#0D0D0D` background. Fields:

1. **Refresh Token** — `TextInputEditText` (visible password)
2. **Vehicle ID** — `TextInputEditText`
3. **x-api-key** — `TextInputEditText` (visible password)
4. **"Show Vehicle Photo" Switch** (`SwitchCompat`) — when toggled ON:
   - **CRM ID** field appears (fade-in animation)
   - **Mobile Number** field appears (fade-in animation)
5. **"Test Connection & Save"** Button

On save:
- Calls `ApiService.refreshToken()` to validate credentials
- On success: saves all credentials to `SecurityUtil`, schedules `WorkManager`
- Immediately enqueues a `OneTimeWorkRequest` for instant sync
- Schedules `PeriodicWorkRequest` every 15 minutes
- If `showImage` was turned OFF, clears cached image URL so it re-fetches if re-enabled

---

## VehicleUpdateWorker.kt — Background Sync

`CoroutineWorker` dispatched on `Dispatchers.IO`. Called every 15 minutes by WorkManager, or immediately on manual refresh.

### doWork() Flow:
```
1. Check hasCredentials() → failure if missing
2. Get valid accessToken (or re-fetch via refreshToken)
   → If refresh fails: Result.retry()
3. Parallel fetch (sequential in code):
   - getVehicleState() → ignition, AC, cabin temp, odometer, GPS coords
   - getVehicleHealth() → battery %, range, charging state, charge time
4. Process data:
   - Odometer: meters ÷ 1000 → integer km string
   - Range: distanceToEmpty as int + " km"
   - Charging: hvChargingState boolean + "Xh Ym" string
   - Location: GPS coords → reverse geocode
5. Reverse Geocoding (two-tier):
   - Primary: Android Geocoder (native)
   - Fallback: OpenStreetMap Nominatim REST API
   - Final fallback: raw "lat, lon" string
6. If vehicle image is enabled:
   - If URL not cached: POST vehicle-info API → cache URL
   - Download PNG bytes → save to context.cacheDir/vehicle_thumb.png
7. Save all data via SecurityUtil.saveVehicleState()
8. Broadcast ACTION_APPWIDGET_UPDATE → WidgetProvider.onUpdate()
9. Return Result.success()
```

---

## WidgetProvider.kt — Widget Renderer

`AppWidgetProvider` with two responsibilities:

### 1. updateAppWidget()
Called whenever widget needs to redraw. Uses `RemoteViews(context.packageName, R.layout.vehicle_widget)`.

> ⚠️ **RemoteViews constraint**: Only a limited set of Views are allowed. Plain `<View>` crashes widget inflation. We use `<TextView>` with `background` attribute for dividers.

Binds these view IDs:
| View ID | Content |
|---------|---------|
| `tvWidgetIgnition` | "ON" or "OFF" (top-left) |
| `ivChargingBadge` | Visible/Gone based on `isCharging` |
| `btnWidgetRefresh` | PendingIntent → `ACTION_MANUAL_SYNC` |
| `tvWidgetBattery` | "82%" |
| `pbBattery` | `setProgressBar(id, 100, pct, false)` |
| `tvWidgetCharging` | "1h 30m left" (visible only if charging + time > 0) |
| `tvWidgetOdo` | "479 km" |
| `tvWidgetSpeed` | "164 km" (range, not speed) |
| `tvWidgetTemp` | "28.0°C" |
| `ivVehicleImage` | Bitmap from cacheDir (VISIBLE/GONE) |
| `tvWidgetLocation` | Reverse-geocoded address |
| `tvWidgetUpdated` | "Last sync: HH:mm" |

### 2. onReceive() — Manual Sync
Handles `ACTION_MANUAL_SYNC = "com.rezinch.irawidget.MANUAL_SYNC"`:
- Shows "Syncing..." on widget
- Enqueues `OneTimeWorkRequestBuilder<VehicleUpdateWorker>`

---

## Widget Layout (vehicle_widget.xml)

**Root:** `LinearLayout` (vertical), `background=@drawable/widget_bg`

```
LinearLayout (vertical)
├── HEADER (horizontal, height=24dp)
│   ├── LEFT  weight=1: tvWidgetIgnition ("OFF"/"ON")
│   ├── CENTER: "iRA.ev" title (white bold 16sp)
│   └── RIGHT weight=1: ivChargingBadge + btnWidgetRefresh
│
├── MAIN CONTENT (horizontal, weight=1)
│   ├── ivVehicleImage  (weight=1.1, GONE by default)
│   ├── BATTERY BLOCK (vertical, weight=1.2)
│   │   ├── "BATTERY" label (9sp gray)
│   │   ├── tvWidgetBattery (28sp white bold, singleLine=true)
│   │   ├── pbBattery (ProgressBar, 6dp height, @drawable/battery_progress)
│   │   └── tvWidgetCharging ("Xh Ym left", GONE when not charging)
│   ├── [Divider TextView 1dp]
│   ├── ODO+RANGE BLOCK (vertical, weight=1.1)
│   │   ├── [ic_road icon + ODOMETER label + tvWidgetOdo]
│   │   ├── [1dp separator]
│   │   └── [ic_range icon + RANGE label + tvWidgetSpeed]
│   ├── [Divider TextView 1dp]
│   └── CABIN BLOCK (vertical, weight=0.85, centered)
│       ├── "CABIN" label
│       ├── ic_temp_gauge (52dp×42dp)
│       └── tvWidgetTemp ("28.0°C")
│
├── [Footer divider 1dp]
└── FOOTER (horizontal)
    ├── ic_pin (12dp×12dp)
    ├── tvWidgetLocation (weight=1, ellipsize=end)
    └── tvWidgetUpdated ("Last sync: HH:mm")
```

---

## Widget Background (widget_bg.xml)

A `<layer-list>` with 3 layers:
1. Dark base: `#E6101820` rectangle with `cornerRadius=20dp`
2. Frosted glass: diagonal gradient `#28FFFFFF` → `#04FFFFFF` at 135°
3. Teal glow border: 1dp stroke `#5500A3A1`

---

## Key Design Constraints / Known Issues

1. **RemoteViews view whitelist** — Only `TextView`, `ImageView`, `ProgressBar`, `LinearLayout`, `FrameLayout`, `RelativeLayout`, `Button`, `ImageButton` etc. are supported. Never use plain `<View>`.

2. **No animations in widgets** — All transitions happen between full redraws.

3. **Vehicle image URL is cached forever** — Once fetched, the `vehicle_image_url` is not re-fetched unless cleared. Cleared only when user saves settings with image toggled OFF.

4. **Refresh token not re-validated** — If the refresh token itself expires or is invalidated server-side, the worker silently enters `Result.retry()` loop with no user notification.

5. **SSL bypass** — The `getUnsafeOkHttpClient()` trusts all certificates. This is intentional for the Tata API endpoint.

6. **`speed` field is reused for Range** — The `SecurityUtil.VehicleState.speed` field stores the range string (e.g., "164 km"), not actual speed. The vehicle-state API does return a `speed` field, but we don't use it.

---

## Color Palette

| Name | Hex | Used For |
|------|-----|----------|
| `tata_teal` | `#00A3A1` | Accents, icons, progress bar |
| `widget_background_dark` | `#D9121212` | (legacy, background now uses layer-list) |
| `widget_text_primary` | `#FFFFFF` | Main values |
| `widget_text_secondary` | `#A0A0A0` | Labels, status text |
| `error_red` | `#CF6679` | Error messages in setup screen |
| `ic_launcher_bg` | `#2A5C45` | App icon background (forest green) |

---

## App Icon

Generated PNG placed in all mipmap density folders:
- `mdpi`: 48×48, `hdpi`: 72×72, `xhdpi`: 96×96, `xxhdpi`: 144×144, `xxxhdpi`: 192×192

Adaptive icon: `mipmap-anydpi/ic_launcher.xml` uses `@color/ic_launcher_bg` as background and `@mipmap/ic_launcher_foreground` (PNG padded to 72% size centered on transparent canvas) as foreground.

Design: Dark forest green `#2A5C45` background, "TATA (ev)" logo top, "i.R.A" large center, "WIDGET" below — all in white.
