package com.exodia.batteryalert.ui.monitor

import kotlin.math.min

/** Stable sizing model for the tabular `100%` hero readout. */
object HeroNumberLayout {
    const val ringStrokeDp = 22f
    const val clearPaddingDp = 16f
    private const val widestReadoutWidthInFontSizes = 2.3f // "100" plus the 30% percent sign.

    fun innerDiameterDp(ringDiameterDp: Float): Float =
        (ringDiameterDp - 2 * ringStrokeDp - 2 * clearPaddingDp).coerceAtLeast(0f)

    fun fontSizeSp(ringDiameterDp: Float, density: Float): Float {
        require(density > 0f)
        val innerPixels = innerDiameterDp(ringDiameterDp) * density
        val maximumWidthPixels = innerPixels * .70f
        return min(160f, maximumWidthPixels / widestReadoutWidthInFontSizes / density)
    }

    fun widestReadoutWidthDp(fontSizeSp: Float): Float = fontSizeSp * widestReadoutWidthInFontSizes
}
