package com.clawd.watch.data

import android.content.Context

object SettingsStore {
    private const val PREF_NAME = "clawd_settings"
    private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
    private const val KEY_VIBRATION = "vibration_strength"
    private const val KEY_SENSITIVITY = "gesture_sensitivity"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun isOnboardingComplete(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDING_COMPLETE, false)

    fun setOnboardingComplete(context: Context) {
        prefs(context).edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()
    }

    fun getVibrationStrength(context: Context): String =
        prefs(context).getString(KEY_VIBRATION, "normal") ?: "normal"

    fun setVibrationStrength(context: Context, value: String) {
        prefs(context).edit().putString(KEY_VIBRATION, value).apply()
    }

    fun getGestureSensitivity(context: Context): String =
        prefs(context).getString(KEY_SENSITIVITY, "standard") ?: "standard"

    fun setGestureSensitivity(context: Context, value: String) {
        prefs(context).edit().putString(KEY_SENSITIVITY, value).apply()
    }

    fun getKeepScreenOn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_SCREEN_ON, true)

    fun setKeepScreenOn(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()
    }
}
