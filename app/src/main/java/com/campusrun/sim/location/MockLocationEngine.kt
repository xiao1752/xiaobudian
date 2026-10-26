package com.campusrun.sim.location

import android.location.Location
import android.location.LocationManager
import android.location.LocationProvider
import android.os.Build
import android.os.SystemClock
import com.campusrun.sim.model.Telemetry

/**
 * Injects fixes through Android's test providers.
 *
 * PRIMARY METHOD ONLY: this app is meant to be installed as a system
 * app via a KernelSU module (systemless) on a rooted Android 14 phone,
 * and run from a foreground service. See README.md, "Primary method".
 * Do NOT use this engine as a plain mock-location-app selection:
 * that selection leaves a detectable mock flag that many apps check.
 * This file contains no code that targets, patches, or bypasses any
 * specific app, and no code that hides itself from a specific app.
 */
class MockLocationEngine(
    private val locationManager: LocationManager,
) {
    private var started = false

    fun start() {
        if (started) return
        val providers = mutableListOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            providers.add(LocationManager.FUSED_PROVIDER)
        }
        for (provider in providers) {
            try {
                if (locationManager.getProvider(provider) == null) {
                    locationManager.addTestProvider(
                        provider,
                        false, // requiresNetwork
                        false, // requiresSatellite
                        false, // requiresCell
                        false, // hasMonetaryCost
                        true,  // supportsAltitude
                        true,  // supportsSpeed
                        true,  // supportsBearing
                        LocationProvider.POWER_USAGE_LOW,
                        LocationProvider.ACCURACY_FINE,
                    )
                }
                locationManager.setTestProviderEnabled(provider, true)
            } catch (_: SecurityException) {
                // System-app installation / permissions are covered in README.
            } catch (_: IllegalArgumentException) {
                // Provider already exists on this device; enabling is enough.
                try {
                    locationManager.setTestProviderEnabled(provider, true)
                } catch (_: Exception) {
                    // Keep going with the providers that did start.
                }
            }
        }
        started = true
    }

    fun inject(telemetry: Telemetry) {
        if (!started) start()
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        ) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(LocationManager.FUSED_PROVIDER)
        } else emptyList()
        for (provider in providers) {
            try {
                val location = Location(provider).apply {
                    latitude = telemetry.latitude
                    longitude = telemetry.longitude
                    altitude = telemetry.altitudeM
                    speed = telemetry.speedMps.toFloat()
                    bearing = telemetry.bearingDeg.toFloat()
                    accuracy = 3.0f
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
                locationManager.setTestProviderLocation(provider, location)
            } catch (_: Exception) {
                // One provider failing must not stop the others.
            }
        }
    }

    fun stop() {
        if (!started) return
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        ) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(LocationManager.FUSED_PROVIDER)
        } else emptyList()
        for (provider in providers) {
            try {
                locationManager.setTestProviderEnabled(provider, false)
                locationManager.removeTestProvider(provider)
            } catch (_: Exception) {
                // Best effort cleanup; the service stops either way.
            }
        }
        started = false
    }
}
