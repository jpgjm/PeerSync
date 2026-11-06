package com.example.peersync.desktop

import com.example.peersync.AppInfo
import com.example.peersync.data.KeyValueStore
import com.example.peersync.data.SaveFolderAccess
import com.example.peersync.log.LogEntry
import com.example.peersync.log.LogSink
import com.example.peersync.proto.DownloadTargets
import com.example.peersync.proto.FileDownloadTargets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import com.example.peersync.log.PsLog
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinReg
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Storage locations.
 * - Settings and logs (0.2.33, portable app): `<PeerSync folder>\data\` next to PeerSync.exe, so the whole folder
 *   can be moved or copied. 0.2.32 and earlier used %LOCALAPPDATA%\PeerSync\; that data isn't moved (spec 2),
 *   so the device ID changes once and paired devices have to pair again.
 *   Dev run (gradle run, no exe): %LOCALAPPDATA%\PeerSync\ (~/.local/share/PeerSync on non-Windows).
 * - Received files: <user>\Downloads\PeerSync\ by default (Save to can pick another folder).
 */
object DesktopPaths {
    /**
     * The jpackage launcher (PeerSync.exe), or null in a dev run. The launcher passes `jpackage.app-path`; without it,
     * this process's executable is the exe (java.exe / javaw.exe means a dev run).
     */
    val launcherExe: String? by lazy {
        System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }?.let { return@lazy it }
        val cmd = runCatching { ProcessHandle.current().info().command().orElse(null) }.getOrNull() ?: return@lazy null
        val name = File(cmd).name.lowercase()
        if (name == "java.exe" || name == "javaw.exe" || name == "java") null else cmd
    }

    val baseDir: File by lazy {
        val exe = launcherExe
        if (exe != null) return@lazy File(File(exe).absoluteFile.parentFile, AppInfo.PORTABLE_DATA_DIR)
        val localAppData = System.getenv("LOCALAPPDATA")
        if (!localAppData.isNullOrBlank()) File(localAppData, AppInfo.NAME)
        else File(System.getProperty("user.home"), ".local/share/${AppInfo.NAME}")
    }

    /**
     * Checked once at startup (0.2.33): null when [baseDir] can be written, otherwise the reason.
     * Writes a real file, because a folder can look writable and still refuse writes (Program Files,
     * read-only media, Windows "Controlled folder access"). There is no fallback location: data must
     * not silently move somewhere else (that would change the device ID without notice).
     */
    fun writeProblem(): String? = try {
        baseDir.mkdirs()
        File.createTempFile("write-check", ".tmp", baseDir).delete()
        null
    } catch (e: Exception) {
        e.toString()
    }

    val logDir: File by lazy { File(baseDir, AppInfo.LOG_DIR).also { it.mkdirs() } }

    val settingsFile: File by lazy { File(baseDir, AppInfo.SETTINGS_FILE) }

    /**
     * Received files are saved to <user>\Downloads\PeerSync\ (~/Downloads/PeerSync/ on non-Windows).
     * 0.2.31: dropped the known-folder API (FOLDERID_Downloads) of 0.2.29–0.2.30 and went back to the fixed
     * user.home\Downloads of 0.2.28 and earlier. Saves to <user>\Downloads even if "Downloads" was moved or
     * put on OneDrive. Files saved elsewhere by 0.2.29–0.2.30 aren't moved (spec 2).
     */
    fun downloadDir(): File = File(File(System.getProperty("user.home"), "Downloads"), AppInfo.DOWNLOAD_FOLDER)
}

/** KeyValueStore saved to settings.json in DesktopPaths.baseDir (`<PeerSync folder>\data\` since 0.2.33). */
class DesktopKeyValueStore(private val file: File) : KeyValueStore {
    private val json = Json { prettyPrint = true }
    private val map = LinkedHashMap<String, String>()

    init {
        runCatching {
            if (file.exists()) {
                val obj = Json.parseToJsonElement(file.readText()) as? JsonObject
                obj?.forEach { (k, v) -> map[k] = v.jsonPrimitive.content }
            }
        }
    }

    @Synchronized
    override fun getString(key: String): String? = map[key]

    @Synchronized
    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
        save()
    }

    @Synchronized
    override fun getBoolean(key: String, default: Boolean): Boolean =
        map[key]?.toBooleanStrictOrNull() ?: default

    @Synchronized
    override fun putBoolean(key: String, value: Boolean) {
        map[key] = value.toString()
        save()
    }

    /**
     * 0.2.29: write to a temp file, then replace (ATOMIC_MOVE). Up to 0.2.28 settings.json was overwritten in place,
     * so a crash or power loss mid-write could corrupt it and lose the secret key (the source of the device ID).
     * On Windows, Files.move(REPLACE_EXISTING, ATOMIC_MOVE) maps to MoveFileEx(MOVEFILE_REPLACE_EXISTING).
     * 0.2.31: removed the fallback to a plain move. AtomicMoveNotSupportedException only occurs across volumes
     * (Windows: ERROR_NOT_SAME_DEVICE, Unix: EXDEV), and the temp file is in the same folder (JDK 21 WindowsFileCopy / UnixFileSystem).
     */
    private fun save() {
        val text = json.encodeToString(JsonObject.serializer(), JsonObject(map.mapValues { JsonPrimitive(it.value) }))
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            PsLog.w("Settings", "settings save failed: $e")
        }
    }
}

/**
 * File logging (spec 3). Output: DesktopPaths.logDir (`<PeerSync folder>\data\logs\peersync-YYYYMMDD.log` since 0.2.33).
 * Corresponds to the old Windows Services/Logger.cs. Also writes to stdout.
 */
class FileLogSink : LogSink {
    private val lock = Any()

    private fun currentLogFile(): File {
        val day = SimpleDateFormat("yyyyMMdd", Locale.JAPAN).format(Date())
        return File(DesktopPaths.logDir, "${AppInfo.LOG_FILE_BASE}-$day.log")
    }

    override fun write(entry: LogEntry) {
        println(entry.line)
        runCatching {
            synchronized(lock) {
                currentLogFile().appendText(entry.line + System.lineSeparator())
            }
        }
    }
}

/**
 * "Always listen" = auto-start at Windows sign-in (startup registration).
 * Sets/removes `"<exe>" --tray` as value PeerSync under HKCU\Software\Microsoft\Windows\CurrentVersion\Run (no admin needed).
 * Auto-start passes --tray to start in the tray without a window.
 * Corresponds to the old Windows Services/AutoStart.cs and LocalSend's autostart_helper.dart (`"<exe>" --hidden`).
 *
 * 0.2.29: reads/writes directly via the registry API (RegSetValueEx etc., JNA's Advapi32Util).
 * 0.2.15–0.2.28 wrote a .reg file, ran reg.exe `reg import` and read back `reg query` output
 * (0.2.14's `reg add` failed for paths with spaces because Java re-quoted the arguments).
 * The API takes quotes, spaces and non-ASCII text as is, with no child process or code page issues. Same key and value.
 */
object AutoStart {
    private const val RUN_KEY = """Software\Microsoft\Windows\CurrentVersion\Run"""
    private const val VALUE_NAME = AppInfo.NAME
    private const val TAG = "AutoStart"

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    /** Value registered under Run (exe in quotes, as in LocalSend, for paths with spaces). */
    fun runValue(exe: String): String = "\"$exe\" --tray"

    fun apply(enabled: Boolean) {
        if (!isWindows) return
        try {
            val hkcu = WinReg.HKEY_CURRENT_USER
            if (enabled) {
                val exe = DesktopPaths.launcherExe
                if (exe == null) {
                    PsLog.w(TAG, "Dev run: skipping startup registration (only registered in jpackage builds)")
                    return
                }
                val value = runValue(exe)
                // 0.2.31: no read-back after writing. Failure throws Win32Exception, logged as a warning below
                // (jna-platform 5.15.0 Advapi32Util.registrySetStringValue).
                Advapi32Util.registrySetStringValue(hkcu, RUN_KEY, VALUE_NAME, value)
                PsLog.i(TAG, "Startup registered: $value")
            } else {
                if (Advapi32Util.registryValueExists(hkcu, RUN_KEY, VALUE_NAME)) {
                    Advapi32Util.registryDeleteValue(hkcu, RUN_KEY, VALUE_NAME)
                    PsLog.i(TAG, "Startup unregistered")
                } else {
                    PsLog.d(TAG, "Startup unregister: not registered")
                }
            }
        } catch (t: Throwable) {
            // Win32Exception (access denied etc.) or JNA load failure
            PsLog.w(TAG, "Startup registration failed: $t")
        }
    }
}

/** Save folders on the desktop (0.2.32): <user>\Downloads\PeerSync by default, or any folder (token = its path). */
class DesktopSaveFolderAccess : SaveFolderAccess {
    override val defaultPath: String get() = DesktopPaths.downloadDir().path

    override fun defaultTargets(): DownloadTargets = FileDownloadTargets { DesktopPaths.downloadDir() }

    override fun customPath(token: String): String? = File(token).takeIf { it.isDirectory }?.path

    override fun customName(token: String): String = File(token).name

    override fun customTargets(token: String): DownloadTargets? {
        val dir = File(token)
        return if (dir.isDirectory) FileDownloadTargets { dir } else null
    }
}

/**
 * "Keep screen on" on Windows (0.2.32): SetThreadExecutionState(ES_CONTINUOUS | ES_DISPLAY_REQUIRED) while the
 * window is shown. The state belongs to the calling thread, so it is always set from the AWT event thread,
 * which lives as long as the app. Other desktop OSes: no-op.
 */
object KeepScreenOn {
    private const val TAG = "KeepScreenOn"
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    fun apply(on: Boolean) {
        if (!isWindows) return
        javax.swing.SwingUtilities.invokeLater {
            val flags = if (on) WinBase.ES_CONTINUOUS or WinBase.ES_DISPLAY_REQUIRED else WinBase.ES_CONTINUOUS
            // Returns the previous state, or 0 (NULL) when it failed
            val previous = Kernel32.INSTANCE.SetThreadExecutionState(flags)
            if (previous == 0) PsLog.w(TAG, "SetThreadExecutionState failed (keepScreenOn=$on)")
            else PsLog.d(TAG, "keepScreenOn=$on")
        }
    }
}
