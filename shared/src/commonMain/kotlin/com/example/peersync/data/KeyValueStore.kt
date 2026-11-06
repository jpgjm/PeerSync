package com.example.peersync.data

/**
 * Settings storage. Backed by SharedPreferences (Android),
 * <PeerSync folder>\data\settings.json (desktop, since 0.2.33), NSUserDefaults (iOS).
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}
