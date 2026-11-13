package com.devtool.usbmode

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

enum class Table { GLOBAL, SECURE, SYSTEM, PROP }

sealed interface Kind {
    /** On/off. [onValue] is written when switched on, "0" when switched off. */
    data class Toggle(val onValue: String = "1") : Kind

    /** One value from a fixed list of (value, label). */
    data class Choice(val options: List<Pair<String, String>>) : Kind

    /** Shown only; this app cannot change it. */
    data object ReadOnly : Kind
}

data class DevSetting(
    val key: String,
    val label: String,
    val table: Table,
    val kind: Kind,
    val note: String? = null,
)

private val ANIMATION_SCALES = listOf(
    "0" to "Off", "0.5" to "0.5x", "1" to "1x", "1.5" to "1.5x",
    "2" to "2x", "5" to "5x", "10" to "10x",
)

/** The developer settings shown in the app, in display order. */
val DEV_SETTINGS = listOf(
    DevSetting("development_settings_enabled", "Developer options", Table.GLOBAL, Kind.Toggle()),
    DevSetting("adb_enabled", "USB debugging (ADB)", Table.GLOBAL, Kind.Toggle()),
    DevSetting(
        "service.adb.tcp.port", "ADB over network port", Table.PROP, Kind.ReadOnly,
        note = "Change with the ROM's 'ADB over network' toggle in Developer options",
    ),
    // 7 = stay on when plugged into AC, USB or wireless charger (same as the system toggle)
    DevSetting("stay_on_while_plugged_in", "Stay awake while charging", Table.GLOBAL, Kind.Toggle("7")),
    DevSetting("window_animation_scale", "Window animation scale", Table.GLOBAL, Kind.Choice(ANIMATION_SCALES)),
    DevSetting("transition_animation_scale", "Transition animation scale", Table.GLOBAL, Kind.Choice(ANIMATION_SCALES)),
    DevSetting("animator_duration_scale", "Animator duration scale", Table.GLOBAL, Kind.Choice(ANIMATION_SCALES)),
    DevSetting("show_touches", "Show taps", Table.SYSTEM, Kind.Toggle()),
    DevSetting("pointer_location", "Pointer location", Table.SYSTEM, Kind.Toggle()),
    DevSetting(
        "always_finish_activities", "Don't keep activities", Table.GLOBAL, Kind.Toggle(),
        note = "May need an app restart or reboot to take effect",
    ),
)

object DevSettingsRepo {
    fun canWrite(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun grantCommand(context: Context): String =
        "adb shell pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}"

    /** Reads the current raw value, or null when it is unset or unreadable. Call off the main thread. */
    fun read(cr: ContentResolver, s: DevSetting): String? = try {
        when (s.table) {
            Table.GLOBAL -> Settings.Global.getString(cr, s.key)
            Table.SECURE -> Settings.Secure.getString(cr, s.key)
            Table.SYSTEM -> Settings.System.getString(cr, s.key)
            Table.PROP -> Shell.getprop(s.key).ifEmpty { null }
        }
    } catch (_: Exception) {
        null
    }

    /** Writes [value]. Returns an error message, or null on success. */
    fun write(cr: ContentResolver, s: DevSetting, value: String): String? = try {
        val ok = when (s.table) {
            Table.GLOBAL -> Settings.Global.putString(cr, s.key, value)
            Table.SECURE -> Settings.Secure.putString(cr, s.key, value)
            Table.SYSTEM -> Settings.System.putString(cr, s.key, value)
            Table.PROP -> false
        }
        if (ok) null else "System refused to change ${s.key}"
    } catch (e: SecurityException) {
        "Permission denied. Grant WRITE_SECURE_SETTINGS via adb first."
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    fun isOn(raw: String?): Boolean = raw?.toFloatOrNull()?.let { it != 0f } ?: false

    /** Matches "1.0" to the "1" option, etc. */
    fun choiceLabel(kind: Kind.Choice, raw: String?): String {
        val v = raw?.toFloatOrNull() ?: return raw ?: "Default"
        return kind.options.firstOrNull { it.first.toFloat() == v }?.second ?: raw
    }
}
