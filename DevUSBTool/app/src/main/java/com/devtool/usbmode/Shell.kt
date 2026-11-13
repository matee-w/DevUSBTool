package com.devtool.usbmode

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Small helper for running shell commands with a timeout. Call from a background thread. */
object Shell {
    data class Result(val exitCode: Int, val output: String)

    fun run(vararg cmd: String, timeoutMs: Long = 3_000): Result? = try {
        val process = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = thread(isDaemon = true) {
            try {
                out.append(process.inputStream.bufferedReader().readText())
            } catch (_: Exception) {
            }
        }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroy()
            null
        } else {
            reader.join(500)
            Result(process.exitValue(), out.toString().trim())
        }
    } catch (_: Exception) {
        null
    }

    fun getprop(name: String): String = run("getprop", name)?.output.orEmpty()

    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/system/sbin/su", "/vendor/bin/su", "/data/adb/magisk/su",
    )

    /** True if an `su` binary appears to be present. Does not prompt for root. */
    fun hasSu(): Boolean = SU_PATHS.any { File(it).exists() }

    /** Runs [command] as root. Returns null if su is missing, denied or timed out. */
    fun su(command: String): Result? = run("su", "-c", command, timeoutMs = 15_000)
}
