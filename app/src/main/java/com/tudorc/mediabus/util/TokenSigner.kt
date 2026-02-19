package com.tudorc.mediabus.util

import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class TokenSigner(private val secret: ByteArray) {
    fun sign(payload: JSONObject): String {
        val payloadRaw = payload.toString()
        val payloadBase64 = base64Encode(payloadRaw.toByteArray(Charsets.UTF_8))
        val signatureBase64 = base64Encode(hmac(payloadBase64.toByteArray(Charsets.UTF_8)))
        return "$payloadBase64.$signatureBase64"
    }

    fun verify(token: String): JSONObject? {
        val parts = token.split('.')
        if (parts.size != 2) {
            return null
        }

        val payloadPart = parts[0]
        val signaturePart = parts[1]
        val expectedSignature = base64Encode(hmac(payloadPart.toByteArray(Charsets.UTF_8)))
        if (!MessageDigest.isEqual(expectedSignature.toByteArray(), signaturePart.toByteArray())) {
            return null
        }

        val payloadBytes = runCatching { base64Decode(payloadPart) }.getOrNull() ?: return null
        return runCatching {
            JSONObject(String(payloadBytes, Charsets.UTF_8))
        }.getOrNull()
    }

    private fun hmac(bytes: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(bytes)
    }

    private fun base64Encode(bytes: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun base64Decode(text: String): ByteArray {
        return Base64.getUrlDecoder().decode(text)
    }
}
