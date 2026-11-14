package com.autoresolve

import android.content.Context
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle

class VehicleInfoRepository(context: Context) {

    private val vehicle = Vehicle(context.applicationContext)
    private var initialized = false

    fun initialize(): Boolean {
        return try {
            vehicle.initialize()
            initialized = true

            Log.d(TAG, "Vehicle SDK initialization requested")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Vehicle SDK initialization failed", e)
            false
        }
    }

    fun loadVehicleId(onResult: (String) -> Unit) {
        if (!initialized) {
            onResult("Vehicle SDK is not initialized")
            return
        }

        try {
            val carInfo = vehicle.getCarInfo()

            carInfo.getVINInfo(
                onComplete = { vin ->

                    val value = vin?.toString()?.trim().orEmpty()

// Only accept a string that matches the standard VIN format.
                    val validVin = Regex("^[A-HJ-NPR-Z0-9]{17}$")
                        .matches(value.uppercase())

                    val result = if (validVin) {
                        "Vehicle VIN detected (ending ${value.takeLast(4)})"
                    } else {
                        "Vehicle information received; VIN not verified"
                    }

                    Log.d(
                        TAG,
                        "VIN response type: ${vin?.javaClass?.simpleName ?: "null"}"
                    )

                    Log.d(TAG, "VIN length: ${value.length}")

                    Log.d(
                        TAG,
                        "VIN format valid: ${
                            Regex("^[A-HJ-NPR-Z0-9]{17}$")
                                .matches(value.uppercase())
                        }"
                    )

                    Log.d(TAG, "Vehicle ID query completed")
                    onResult(result)
                },
                onFailed = { e ->
                    Log.e(TAG, "Vehicle ID query failed", e)
                    onResult(
                        "Vehicle information unavailable: ${e.message}"
                    )
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Vehicle ID request failed", e)
            onResult("Vehicle information request failed")
        }
    }

    fun release() {
        if (initialized) {
            vehicle.release()
            initialized = false
            Log.d(TAG, "Vehicle SDK released")
        }
    }

    companion object {
        private const val TAG = "AutoResolveVehicle"
    }
}