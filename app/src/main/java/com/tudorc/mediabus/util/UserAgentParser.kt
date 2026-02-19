package com.tudorc.mediabus.util

object UserAgentParser {
    fun labelFromUserAgent(userAgent: String): String {
        if (userAgent.isBlank()) {
            return "Paired Device"
        }
        val browser = browserName(userAgent) ?: return "Paired Device"
        val os = operatingSystem(userAgent)
        return if (os == null) browser else "$browser on $os"
    }

    private fun browserName(ua: String): String? {
        return when {
            ua.contains("Edg/", ignoreCase = true) -> "Edge"
            ua.contains("Chrome/", ignoreCase = true) -> "Chrome"
            ua.contains("Firefox/", ignoreCase = true) -> "Firefox"
            ua.contains("Safari/", ignoreCase = true) && ua.contains("Version/", ignoreCase = true) -> "Safari"
            ua.contains("OPR/", ignoreCase = true) || ua.contains("Opera", ignoreCase = true) -> "Opera"
            else -> null
        }
    }

    private fun operatingSystem(ua: String): String? {
        return when {
            ua.contains("Android", ignoreCase = true) -> "Android"
            ua.contains("iPhone", ignoreCase = true) || ua.contains("iPad", ignoreCase = true) -> "iOS"
            ua.contains("Windows", ignoreCase = true) -> "Windows"
            ua.contains("Mac OS X", ignoreCase = true) -> "macOS"
            ua.contains("Linux", ignoreCase = true) -> "Linux"
            else -> null
        }
    }
}
