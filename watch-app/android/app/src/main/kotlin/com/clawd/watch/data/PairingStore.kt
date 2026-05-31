package com.clawd.watch.data

import android.content.Context

object PairingStore {
    private const val PREF_NAME = "clawd_pairing"
    private const val KEY_ADDRESS = "device_address"
    private const val KEY_NAME = "device_name"

    fun save(context: Context, address: String, name: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_ADDRESS, address)
            .putString(KEY_NAME, name)
            .apply()
    }

    fun isPaired(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ADDRESS, null) != null

    fun getDeviceAddress(context: Context): String? =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ADDRESS, null)

    fun clear(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
