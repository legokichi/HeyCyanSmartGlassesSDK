package com.sdk.glassessdksample.ui

import android.content.Context

object PairingTargetStore {
    private const val PREF_NAME = "pairing_target"
    private const val KEY_ADDRESS = "address"
    private const val KEY_NAME = "name"

    fun save(context: Context, address: String?, name: String?) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ADDRESS, address?.normalizeMac())
            .putString(KEY_NAME, name)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    fun matches(context: Context, address: String?, name: String?): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val targetAddress = prefs.getString(KEY_ADDRESS, null)
        val targetName = prefs.getString(KEY_NAME, null)
        val normalizedAddress = address?.normalizeMac()
        return when {
            !targetAddress.isNullOrBlank() && !normalizedAddress.isNullOrBlank() -> {
                targetAddress == normalizedAddress
            }
            !targetName.isNullOrBlank() && !name.isNullOrBlank() -> {
                targetName == name
            }
            else -> false
        }
    }

    private fun String.normalizeMac(): String = trim().uppercase()
}
