package com.tudorc.mediabus.util

import android.util.Base64

object Base64Url {
    private const val ENCODE_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    private const val DECODE_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP

    fun encode(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, ENCODE_FLAGS)
    }

    fun decode(text: String): ByteArray {
        val normalized = when (text.length % 4) {
            0 -> text
            2 -> "${text}=="
            3 -> "$text="
            else -> throw IllegalArgumentException("Invalid Base64 URL string length")
        }
        return Base64.decode(normalized, DECODE_FLAGS)
    }
}
