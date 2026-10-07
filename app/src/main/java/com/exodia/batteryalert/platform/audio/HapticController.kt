package com.exodia.batteryalert.platform.audio

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.exodia.batteryalert.core.alert.HapticPort

/**
 * Android haptic feedback controller.
 * Supports:
 *  - Short pulse for Notice.
 *  - Pulse train for Warning.
 *  - Continuous severe repeating pattern for Critical/Emergency.
 *  - Graceful fallback when vibrator hardware is unavailable.
 */
class HapticController(private val context: Context) : HapticPort {

    private val vibrator: Vibrator? by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun vibrateNotice() {
        try {
            val vib = vibrator ?: return
            if (!vib.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(100)
            }
        } catch (e: Exception) {
            // Capability failure handled gracefully
        }
    }

    override fun vibrateWarning() {
        try {
            val vib = vibrator ?: return
            if (!vib.hasVibrator()) return

            val timings = longArrayOf(0, 200, 200, 200)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createWaveform(timings, -1))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(timings, -1)
            }
        } catch (e: Exception) {
            // Capability failure handled gracefully
        }
    }

    override fun startSevereVibration() {
        try {
            val vib = vibrator ?: return
            if (!vib.hasVibrator()) return

            val timings = longArrayOf(0, 500, 200, 500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Repeat index 0 for continuous pattern
                vib.vibrate(VibrationEffect.createWaveform(timings, 0))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(timings, 0)
            }
        } catch (e: Exception) {
            // Capability failure handled gracefully
        }
    }

    override fun stopVibration() {
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            // Ignore cancel error
        }
    }
}
