package com.exodia.batteryalert.core.transport

/**
 * Pure Kotlin abstraction for byte streaming from serial devices (USB or UART).
 * Keeps core.* free of Android, UsbManager, and POSIX/JNI dependencies.
 */
interface ByteStreamSource {
    val displayName: String
    val isOpen: Boolean

    fun open()
    fun read(buffer: ByteArray, offset: Int = 0, count: Int = buffer.size): Int
    fun close()
}
