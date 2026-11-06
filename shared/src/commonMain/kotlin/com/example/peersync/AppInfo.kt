package com.example.peersync

/**
 * App name, folder names, identifiers and file names in one place (0.2.30).
 *
 * Up to 0.2.29, "PeerSync" / "peersync" were hard-coded all over Android, Windows and iOS (Kotlin and Swift).
 * Values are only built from here and **are the same as in 0.2.29** (so storage locations, notification IDs and keys don't change).
 *
 * From Swift use `AppInfo.shared.name` etc. (`const val` is a property in Swift too).
 *
 * Out of scope (can't be built from here):
 * - Build-time name/version (Android label, versionName; Windows packageName, packageVersion) live in gradle.properties.
 *   iOS uses project.yml (XcodeGen). Both are fixed at build time, so they can't reference Kotlin constants.
 * - The wire protocol ALPN ([com.example.peersync.proto.Wire.ALPN_STRING]) must match the Rust side (native/peersync-iroh),
 *   so it is a protocol constant kept in Wire, independent of the name.
 */
object AppInfo {
    /** Display name. Basis for screen titles, tray, window name, notification titles and folder names. */
    const val NAME = "PeerSync"

    /** Lowercase identifier (pairing code URI scheme, file name prefix). */
    const val SLUG = "peersync"

    /** Folder name for received files (Android `Download/PeerSync`, Windows `<Downloads>\PeerSync`). */
    const val DOWNLOAD_FOLDER = NAME

    /** Suffix of partially received files (`<name>.peersync-part`). Used to resume. */
    const val PART_SUFFIX = ".$SLUG-part"

    /** URI scheme of the pairing code (QR, shared text) (`peersync:<ID>?name=<device name>`). */
    const val PAIRING_SCHEME = SLUG

    /** Log folder name (iOS `Documents/logs`, Windows `<PeerSync folder>\data\logs` since 0.2.33). */
    const val LOG_DIR = "logs"

    /** Log file name base (iOS `peersync.log`, Windows `peersync-YYYYMMDD.log`). */
    const val LOG_FILE_BASE = SLUG

    /** Android SharedPreferences name (`peersync`; fixed, since changing it loses settings and the device ID). */
    const val PREFS_NAME = SLUG

    /** Windows settings file name (`<PeerSync folder>\data\settings.json` since 0.2.33). */
    const val SETTINGS_FILE = "settings.json"

    /** Android: the last crash, written by the uncaught-exception handler and logged on the next launch (0.2.33). */
    const val CRASH_FILE = "last-crash.txt"

    /** Windows: settings and logs live in this folder next to the exe (portable app, 0.2.33). */
    const val PORTABLE_DATA_DIR = "data"

    /** Log tag (`PeerSync/<name>`). Builds the same form as `const val TAG = "${AppInfo.NAME}/Main"` via a function. */
    fun tag(name: String): String = "$NAME/$name"

    /**
     * Identifier for iOS notifications, background tasks, etc. (`PeerSync.<suffix>`).
     * From Swift: `AppInfo.shared.identifier(suffix:)` (avoids the Objective-C reserved word `id` as the name).
     */
    fun identifier(suffix: String): String = "$NAME.$suffix"

    /** Temp folder where iOS "Send" exports photos etc. (`tmp/PeerSyncOutbox`). */
    const val OUTBOX_FOLDER = "${NAME}Outbox"
}
