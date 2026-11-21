package com.salar.tv

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Resolves app-owned recording folders on primary storage and mounted removable volumes.
 * getExternalFilesDirs keeps writes within the app's scoped-storage area on Android 10+.
 */
object RecordingStorage {
    data class Option(val index: Int, val directory: File, val label: String)

    fun options(context: Context): List<Option> {
        val dirs = context.getExternalFilesDirs(Environment.DIRECTORY_MOVIES)
        return dirs.mapIndexedNotNull { index, directory ->
            if (directory == null) return@mapIndexedNotNull null
            val mounted = Environment.getExternalStorageState(directory) == Environment.MEDIA_MOUNTED
            if (index == 0 || (mounted && Environment.isExternalStorageRemovable(directory))) {
                Option(index, directory, if (index == 0) "حافظه داخلی گوشی" else "حافظه جانبی (کارت حافظه)")
            } else null
        }
    }

    fun selectedIndex(context: Context): Int =
        context.getSharedPreferences("salar_tv", Context.MODE_PRIVATE).getInt("recording_storage_index", 0)

    fun selectedDirectory(context: Context): File {
        val dirs = context.getExternalFilesDirs(Environment.DIRECTORY_MOVIES)
        val index = selectedIndex(context)
        val selected = dirs.getOrNull(index)
        if (selected != null) {
            val mounted = Environment.getExternalStorageState(selected) == Environment.MEDIA_MOUNTED
            if (index == 0 || (mounted && Environment.isExternalStorageRemovable(selected))) return selected
        }
        return dirs.firstOrNull() ?: File(context.filesDir, Environment.DIRECTORY_MOVIES)
    }
}
