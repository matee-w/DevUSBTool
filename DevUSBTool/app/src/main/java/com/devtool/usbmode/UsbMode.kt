package com.devtool.usbmode

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.provider.Settings
import java.io.File

enum class UsbRole(val label: String) { HOST("Host"), DEVICE("Device"), OTG("Auto (OTG)"), UNKNOWN("Unknown") }

/** How a sysfs/debugfs node encodes the USB role. */
enum class NodeType {
    RK_OTG_MODE,          // Rockchip usb2-phy otg_mode: host | peripheral | otg
    RK_FORCE_MODE,        // Rockchip dwc3 debugfs rk_usb_force_mode: host | peripheral | otg
    RK_LEGACY_FORCE,      // Older Rockchip kernels force_usb_mode: 0 otg | 1 host | 2 device
    TYPEC_DATA_ROLE,      // Generic Type-C class: "[host] device"
}

data class UsbNode(
    val path: String,
    val type: NodeType,
    val exists: Boolean,
    val raw: String?,
    val error: String?,
) {
    val role: UsbRole get() = parseRole(type, raw)
    val readable: Boolean get() = raw != null
}

data class VendorApp(val packageName: String, val label: String)

data class UsbStatus(
    val nodes: List<UsbNode>,
    val usbState: String,
    val usbConfig: String,
    val hasRoot: Boolean,
    val vendorApps: List<VendorApp>,
) {
    /** The first node we could read, used as the "current role". */
    val primary: UsbNode? get() = nodes.firstOrNull { it.readable && it.role != UsbRole.UNKNOWN }
}

private fun parseRole(type: NodeType, raw: String?): UsbRole {
    val v = raw?.trim()?.lowercase() ?: return UsbRole.UNKNOWN
    return when (type) {
        NodeType.RK_LEGACY_FORCE -> when (v) {
            "0" -> UsbRole.OTG; "1" -> UsbRole.HOST; "2" -> UsbRole.DEVICE; else -> UsbRole.UNKNOWN
        }
        NodeType.TYPEC_DATA_ROLE -> {
            val selected = Regex("\\[(\\w+)]").find(v)?.groupValues?.get(1) ?: v
            when (selected) { "host" -> UsbRole.HOST; "device" -> UsbRole.DEVICE; else -> UsbRole.UNKNOWN }
        }
        else -> when {
            v.contains("peripheral") || v.contains("device") -> UsbRole.DEVICE
            v.contains("host") -> UsbRole.HOST
            v.contains("otg") -> UsbRole.OTG
            else -> UsbRole.UNKNOWN
        }
    }
}

/** The value to write to [type] for [role], or null if that node can't take that role. */
fun writeValue(type: NodeType, role: UsbRole): String? = when (type) {
    NodeType.RK_OTG_MODE, NodeType.RK_FORCE_MODE -> when (role) {
        UsbRole.HOST -> "host"; UsbRole.DEVICE -> "peripheral"; UsbRole.OTG -> "otg"; else -> null
    }
    NodeType.RK_LEGACY_FORCE -> when (role) {
        UsbRole.HOST -> "1"; UsbRole.DEVICE -> "2"; UsbRole.OTG -> "0"; else -> null
    }
    NodeType.TYPEC_DATA_ROLE -> when (role) {
        UsbRole.HOST -> "host"; UsbRole.DEVICE -> "device"; else -> null
    }
}

object UsbModeRepo {
    /** Known RK3399 locations, checked first. */
    private val KNOWN_NODES = listOf(
        "/sys/devices/platform/ff770000.syscon/ff770000.syscon:usb2-phy@e450/otg_mode" to NodeType.RK_OTG_MODE,
        "/sys/devices/platform/ff770000.syscon/ff770000.syscon:usb2-phy@e460/otg_mode" to NodeType.RK_OTG_MODE,
        "/sys/kernel/debug/usb@fe800000/rk_usb_force_mode" to NodeType.RK_FORCE_MODE,
        "/sys/kernel/debug/usb@fe900000/rk_usb_force_mode" to NodeType.RK_FORCE_MODE,
        "/sys/bus/platform/drivers/usb20_otg/force_usb_mode" to NodeType.RK_LEGACY_FORCE,
        "/sys/class/typec/port0/data_role" to NodeType.TYPEC_DATA_ROLE,
        "/sys/class/typec/port1/data_role" to NodeType.TYPEC_DATA_ROLE,
    )

    private val VENDOR_KEYWORDS = listOf(
        "factory", "carsetting", "car_setting", "carsettings", "hidden", "engineer",
        "engmode", "usb", "otg", "fyt", "syu", "hct", "mtc", "microntek", "tsetting",
        "rksettings", "rockchip", "setting",
    )

    /** Collects everything the USB card shows. Call off the main thread. */
    fun load(context: Context): UsbStatus {
        val discovered = discoverOtgModeFiles()
        val paths = (KNOWN_NODES + discovered.map { it to NodeType.RK_OTG_MODE }).distinctBy { it.first }
        return UsbStatus(
            nodes = paths.map { (p, t) -> readNode(p, t) },
            usbState = Shell.getprop("sys.usb.state"),
            usbConfig = Shell.getprop("persist.sys.usb.config").ifEmpty { Shell.getprop("sys.usb.config") },
            hasRoot = Shell.hasSu(),
            vendorApps = findVendorApps(context),
        )
    }

    private fun readNode(path: String, type: NodeType): UsbNode {
        val f = File(path)
        return try {
            UsbNode(path, type, f.exists(), f.readText().trim(), null)
        } catch (e: Exception) {
            UsbNode(path, type, f.exists(), null, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Looks for other otg_mode files under /sys/devices/platform (vendor kernels move them around). */
    private fun discoverOtgModeFiles(): List<String> {
        val found = mutableListOf<String>()
        fun walk(dir: File, depth: Int) {
            if (depth > 3 || found.size > 10) return
            val children = try { dir.listFiles() } catch (_: Exception) { null } ?: return
            for (c in children) {
                if (c.name == "otg_mode") found += c.path
                else if (c.isDirectory && !c.name.startsWith("power") && c.name != "subsystem" && c.name != "driver") walk(c, depth + 1)
            }
        }
        walk(File("/sys/devices/platform"), 0)
        return found
    }

    private fun findVendorApps(context: Context): List<VendorApp> {
        val pm = context.packageManager
        return pm.getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName != context.packageName && it.packageName != "com.android.settings" }
            .mapNotNull { info ->
                val label = pm.getApplicationLabel(info).toString()
                val hay = (info.packageName + " " + label).lowercase()
                val match = VENDOR_KEYWORDS.any { hay.contains(it) }
                if (match && pm.getLaunchIntentForPackage(info.packageName) != null) {
                    VendorApp(info.packageName, label)
                } else null
            }
            .sortedWith(compareBy({ !isSystem(pm, it.packageName) }, { it.label }))
            .toList()
    }

    private fun isSystem(pm: android.content.pm.PackageManager, pkg: String): Boolean = try {
        pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
    } catch (_: Exception) {
        false
    }

    /** Switches [node] to [role] using root. Returns an error message, or null on success. */
    fun switchWithRoot(node: UsbNode, role: UsbRole): String? {
        val value = writeValue(node.type, role) ?: return "${role.label} is not supported by ${node.path}"
        val result = Shell.su("echo $value > '${node.path}'") ?: return "Root denied or timed out"
        return if (result.exitCode == 0) null else result.output.ifEmpty { "su exited with ${result.exitCode}" }
    }

    fun developerOptionsIntent() = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun diagnostics(context: Context, status: UsbStatus?, settings: Map<String, String?>): String = buildString {
        appendLine("=== DevUSB Tool diagnostics ===")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Board/Hardware: ${Build.BOARD} / ${Build.HARDWARE}")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Build: ${Build.DISPLAY}")
        appendLine("ro.board.platform: ${Shell.getprop("ro.board.platform")}")
        appendLine("WRITE_SECURE_SETTINGS: ${DevSettingsRepo.canWrite(context)}")
        appendLine()
        appendLine("--- Developer settings ---")
        DEV_SETTINGS.forEach { appendLine("${it.key} = ${settings[it.key] ?: "(unset)"}") }
        appendLine()
        appendLine("--- USB ---")
        if (status == null) {
            appendLine("(not loaded)")
        } else {
            appendLine("root (su present): ${status.hasRoot}")
            appendLine("sys.usb.state: ${status.usbState}")
            appendLine("usb config: ${status.usbConfig}")
            status.nodes.forEach { n ->
                appendLine("${n.path}")
                appendLine("   exists=${n.exists} value=${n.raw ?: "-"} role=${n.role.label}${n.error?.let { " error=$it" } ?: ""}")
            }
            appendLine()
            appendLine("--- Vendor apps ---")
            if (status.vendorApps.isEmpty()) appendLine("(none matched)")
            status.vendorApps.forEach { appendLine("${it.label}  [${it.packageName}]") }
        }
    }
}
