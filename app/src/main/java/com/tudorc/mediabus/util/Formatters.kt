package com.tudorc.mediabus.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Formatters {
    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "?"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    fun formatTimestamp(epochMs: Long): String {
        if (epochMs <= 0) {
            return "Never"
        }
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return formatter.format(Date(epochMs))
    }
}
