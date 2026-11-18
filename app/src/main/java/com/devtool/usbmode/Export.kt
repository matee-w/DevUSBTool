package com.devtool.usbmode

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ways to get the diagnostics text off the head unit. */
object Export {

    fun share(context: Context, text: String): Boolean {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "DevUSB Tool diagnostics")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        if (send.resolveActivity(context.packageManager) == null) return false
        context.startActivity(
            Intent.createChooser(send, "Share diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }

    /** Before Android 10, writing to the public Download folder needs this permission. */
    fun needsStoragePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED

    /**
     * Saves [text] as a .txt file, preferring the public Download folder.
     * Falls back to the app's own folder on external storage (no permission needed).
     * Returns the location to show the user. Call off the main thread.
     */
    fun saveToFile(context: Context, text: String): String {
        val name = "devusb-diagnostics-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                return "Download/$name"
            }
        } else if (!needsStoragePermission(context)) {
            try {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                val file = File(dir, name)
                file.writeText(text)
                return file.absolutePath
            } catch (_: Exception) {
                // fall through to the app folder
            }
        }

        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, name)
        file.writeText(text)
        return file.absolutePath
    }
}
