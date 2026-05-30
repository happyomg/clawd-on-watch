package com.clawd.watch.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores the connected BLE Central (the desktop running Clawd).
 *
 * V2 uses native BLE pairing/bonding handled by the OS, so there is no secret
 * to protect here — only the device address/name to reconnect to. This replaces
 * V1's E2E-key/JWT [TokenStore].
 */
object PairingStore {
    private const val PREF_NAME = "clawd_pairing"
    private const val KEY_ADDRESS = "device_address"
    private const val KEY_NAME = "device_name"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun save(context: Context, address: String, name: String) {
        prefs(context).edit()
            .putString(KEY_ADDRESS, address)
            .putString(KEY_NAME, name)
            .apply()
    }

    fun isPaired(context: Context): Boolean = getDeviceAddress(context) != null

    fun getDeviceAddress(context: Context): String? =
        prefs(context).getString(KEY_ADDRESS, null)

    fun getDeviceName(context: Context): String? =
        prefs(context).getString(KEY_NAME, null)

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
