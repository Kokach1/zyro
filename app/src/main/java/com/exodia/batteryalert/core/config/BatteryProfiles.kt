package com.exodia.batteryalert.core.config

import com.exodia.batteryalert.core.model.BatteryChemistry

data class BatteryProfile(val id: String, val label: String, val cellCount: Int, val chemistry: BatteryChemistry, val capacityMah: Int, val nominalPackVoltageV: Float, val packCount: Int = 1, val source: String, val confirmed: Boolean = false)

object BatteryProfiles {
    // AGRAS-CLASS APPROXIMATION - exact model unconfirmed. Cell count is inferred from nominal voltage.
    val all = listOf(
        BatteryProfile("AGRAS_T55_DB1580", "Agras-class 30 Ah (default)", 14, BatteryChemistry.LI_ION, 30000, 52f, source = "Agras-class approximation"),
        BatteryProfile("AGRAS_T55_DB1050", "Agras-class 20 Ah", 14, BatteryChemistry.LI_ION, 20000, 52.5f, source = "Agras-class approximation"),
        BatteryProfile("AGRAS_T100_DB2160", "Agras-class 41 Ah", 14, BatteryChemistry.LI_ION, 41000, 52f, source = "Agras-class approximation"),
        BatteryProfile("GENERIC_14S_LIPO_22000", "Generic 14S LiPo (fallback)", 14, BatteryChemistry.LIPO, 22000, 51.8f, source = "Generic assumption")
    )
    val default: BatteryProfile = all.first()
    fun byId(id: String): BatteryProfile = all.firstOrNull { it.id == id } ?: default
}
