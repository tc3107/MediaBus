package com.tudorc.mediabus.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object MediaBusHaptics {
    fun performTap(context: Context) {
        val vibrator = vibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(18L, 120))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(16L)
        }
    }

    fun performRelease(context: Context) {
        val vibrator = vibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(28L, 255))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(28L)
        }
    }

    fun startTransitionWave(context: Context) {
        val vibrator = vibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Smooth-ish high/low oscillation loop while server is transitioning.
            val timings = longArrayOf(70L, 70L, 70L, 70L, 70L, 70L, 70L, 70L)
            val amplitudes = intArrayOf(70, 145, 220, 145, 70, 145, 220, 145)
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0L, 80L, 80L, 80L, 80L, 80L), 0)
        }
    }

    fun stopTransitionWave(
        context: Context,
        withRelease: Boolean,
    ) {
        val vibrator = vibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.cancel()
        if (withRelease) {
            performRelease(context)
        }
    }

    fun vibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
