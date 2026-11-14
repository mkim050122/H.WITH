
package com.autoresolve

import android.content.Context

data class VehicleProfile(
    val manufacturer: String,
    val model: String,
    val year: String,
    val powertrain: String
)

class VehicleProfileStore(context: Context) {

    private val preferences = context.getSharedPreferences(
        "vehicle_profile",
        Context.MODE_PRIVATE
    )

    fun save(profile: VehicleProfile) {
        preferences.edit()
            .putString("manufacturer", profile.manufacturer)
            .putString("model", profile.model)
            .putString("year", profile.year)
            .putString("powertrain", profile.powertrain)
            .apply()
    }

    fun load(): VehicleProfile? {
        val model = preferences.getString("model", null)
            ?: return null

        return VehicleProfile(
            manufacturer = preferences.getString(
                "manufacturer", ""
            ).orEmpty(),
            model = model,
            year = preferences.getString(
                "year", ""
            ).orEmpty(),
            powertrain = preferences.getString(
                "powertrain", ""
            ).orEmpty()
        )
    }
}
