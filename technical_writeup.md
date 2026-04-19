# iRA.ev API — Technical Research Writeup

**Author:** Rezin C H (rezinch@gmail.com)  | 8086982257
**Date:** April 2026  
**Scope:** Personal device, personal account only  
**Intent:** Responsible disclosure + community app development

---

## 1. Overview

This document describes the process by which I reverse-engineered the HTTP API used by the Tata Motors iRA.ev Android application, identified security observations, and built an open-source Android homescreen widget using the discovered endpoints.

All research was conducted on my own device using my own Tata Motors account. No other users' data was accessed at any point.

---

## 2. Methodology

### 2.1 Traffic Interception

I used **HTTPToolkit** (httptoolkit.com), a freely available HTTP/HTTPS traffic inspection tool, to intercept network requests between the iRA.ev app and Tata Motors' backend servers.

Setup:
1. Installed HTTPToolkit on a Windows laptop
2. Connected my Android phone to the same WiFi network
3. Used HTTPToolkit's Android QR-code pairing feature to route phone traffic through the laptop proxy
4. HTTPToolkit installed its CA certificate on the device to enable HTTPS decryption
5. Opened the iRA.ev app and observed all outgoing API requests in real time

### 2.2 SSL Pinning

The iRA.ev app does implement SSL certificate validation. HTTPToolkit's automatic certificate injection was sufficient to intercept traffic in this case, suggesting the app relies on system-level certificate trust rather than hardcoded certificate pinning.

---

## 3. Discovered API

**Base URL:** `https://evcx.api.tatamotors`

All requests use HTTP/1.1 and return JSON responses.

### 3.1 Common Request Headers

| Header | Value |
|---|---|
| `client_id` | `TMLEV-ANDROID-APP` |
| `client_secret` | (observed in traffic) |
| `App-Version` | `26.3.1` |
| `x-api-key` | (per-user value, observed in traffic) |
| `User-Agent` | `Ktor client` |

---

### 3.2 Authentication — Refresh Token Endpoint

**Request:**
```
POST /mobile/customer/api/v1/refresh-token
Content-Type: application/json

{
  "refreshToken": "<user_refresh_token>"
}
```

**Response:**
```json
{
  "results": {
    "message": "success",
    "accessToken": "<jwt_access_token>",
    "expires_in": "86399"
  },
  "errorData": null
}
```

**Observation:** The access token expires in ~24 hours (86,399 seconds). The refresh token itself has no observed expiry and appears to be long-lived.

---

### 3.3 Vehicle State Endpoint

**Request:**
```
GET /mobile/cvp/api/v1/ev/vehicle-state
Authorization: Bearer <accessToken>
vehicleId: <vehicleId>
x-api-key: <apiKey>
```

**Response (relevant fields):**
```json
{
  "results": {
    "speed": 0,
    "acState": false,
    "crankOn": false,
    "ignitionOn": false,
    "gpsLatitude": <Location_Latitude>,
    "gpsLongitude": <Location_Longitude>,
    "fuelRemaining": <Battery_Percentage>,
    "odometerInMeters": <Odometer_Reading>,
    "vehicleInteriorTemperature": <Cabin_Temperature>,
    "engineRpm": <Engine_RPM>,
    "eventDateTime": "<Event_Date_Time>"
  },
  "errorData": null
}
```

---

### 3.4 Vehicle Health Endpoint

**Request:**
```
GET /mobile/cvp/api/v1/ev/vehicle-health
Authorization: Bearer <accessToken>
vehicleId: <vehicleId>
x-api-key: <apiKey>
```

**Response (relevant fields):**
```json
{
  "results": {
    "hvBatterySocPercentage": 82.0,
    "distanceToEmpty": 164.0,
    "hvChargingState": true,
    "timeToChargeHour": 1,
    "timeToChargeMinute": 30
  },
  "errorData": null
}
```

---

### 3.5 Vehicle Info / Image Endpoint

**Request:**
```
POST /mobile/service/api/v3/get-user-vehicles-info-mobile
Authorization: Bearer <accessToken>
x-ownership-status: true

{
  "additionalDriverVC": [],
  "crmId": "<crmId>",
  "mobile": "<mobileNumber>",
  "vehicleCategory": "TPEM"
}
```

Returns vehicle metadata including a thumbnail image URL hosted on Tata's Scene7 CDN.

---

## 4. Security Observations

### 4.1 Long-lived Refresh Tokens with No Device Binding

The `refreshToken` observed in traffic appears to be permanent (or very long-lived) and is not bound to a specific device, IP address, or hardware identifier. 

**Risk:** If a refresh token is compromised (e.g. via malware, phishing, or a rogue app), an attacker could silently maintain access to the victim's vehicle indefinitely — including real-time GPS location and, potentially, remote commands.

**Recommendation:** Bind refresh tokens to a device fingerprint or push notification token. Invalidate tokens when used from a new device without re-authentication.

---

### 4.2 No Session Anomaly Detection

There is no observed mechanism to detect or alert when a token is used from a new device or geographic location.

**Recommendation:** Implement anomaly detection and notify users via push/email when their account is accessed from a new device.

---

### 4.3 Static Client Credentials in App Binary

The `client_secret` ("taken from traffic") is hardcoded in the iRA.ev app and transmitted in every request. Anyone who decompiles the APK or intercepts traffic can obtain it.

**Recommendation:** While eliminating client secrets from mobile apps entirely is difficult, rotating them periodically and combining with certificate pinning would raise the bar.

---

### 4.4 No Official Third-Party API or Widget Support

The absence of an official API program or widget has created demand that community developers are now filling by reverse-engineering the private API — introducing security risks for end users who may use unvetted third-party apps.

**Recommendation:** Consider a developer portal with scoped, rate-limited API access for community builders, similar to what Tesla and some European OEMs offer.

---

## 5. The iRA.ev Widget App

Using the above endpoints, I built an open-source Android homescreen widget called **iRA.ev Widget**.

**Features:**
- Live battery %, range (DTE), odometer
- Cabin temperature and ignition state
- Charging status with time remaining
- Real-time GPS location (reverse geocoded)
- Optional vehicle photo from Tata account
- Auto-syncs every 15 minutes via Android WorkManager
- Credentials stored with AES-256 EncryptedSharedPreferences

**Tech stack:** Kotlin, OkHttp, Gson, WorkManager, Android AppWidgetProvider

**GitHub:** [[github.com/rezinch/irawidget](https://github.com/rezinch/tata-iRa-ev-widget-app)]

The app requires users to extract their own credentials using a traffic interceptor — it does not collect or transmit any data to third-party servers. Everything stays on-device.

---

## 6. Conclusion

The iRA.ev API is well-structured and returns rich telemetry data. The primary security concerns are around token lifecycle management and the lack of device binding rather than any fundamental architectural flaw.

I hope this research is useful to the Tata Motors security and product teams. I am available for further discussion at rezinch@gmail.com and 8086982257.

---

*This research was conducted solely on the author's own device and account. No other users were affected. This writeup is shared under responsible disclosure principles.*
