package com.exodia.batteryalert.core.analysis

import kotlin.math.*
object GeoMath {
    private const val earthRadiusM = 6_371_000.0
    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * earthRadiusM * atan2(sqrt(a), sqrt(1 - a))
    }
}
