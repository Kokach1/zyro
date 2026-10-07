package com.exodia.batteryalert.platform.transport

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.exodia.batteryalert.core.transport.ByteStreamSource
import java.io.File
import java.io.FileDescriptor
import java.io.IOException

/**
 * Android implementation of [ByteStreamSource] for internal serial nodes (/dev/ttySx).
 * Configures raw mode and baud rate via POSIX stty / termios parameters and opens
 * a direct POSIX file descriptor via android.system.Os.
 */
class AndroidInternalSerialStream(
    val devicePath: String,
    val baudRate: Int = 921600
) : ByteStreamSource {

    override val displayName: String = "Internal Serial ($devicePath)"

    private var fd: FileDescriptor? = null
    private var _isOpen = false
    override val isOpen: Boolean get() = _isOpen

    override fun open() {
        if (_isOpen) return

        val file = File(devicePath)
        if (!file.exists()) {
            throw IOException("Device $devicePath does not exist")
        }
        if (!file.canRead()) {
            throw SecurityException("ACCESS_DENIED: Cannot read $devicePath. Vendor permissions required.")
        }

        // Configure baud rate and raw mode before opening
        configureSerialNode(devicePath, baudRate)

        try {
            val descriptor = Os.open(devicePath, OsConstants.O_RDWR or OsConstants.O_NOCTTY, 0)
            fd = descriptor
            _isOpen = true
        } catch (e: ErrnoException) {
            throw IOException("Failed to open serial device $devicePath: ${e.message}", e)
        }
    }

    private fun configureSerialNode(path: String, baud: Int) {
        try {
            val process = ProcessBuilder("stty", "-F", path, "$baud", "raw", "-echo", "-echoe", "-echok").start()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val error = process.errorStream.bufferedReader().use { it.readText() }
                if (error.contains("Permission denied", ignoreCase = true)) {
                    throw SecurityException("ACCESS_DENIED: Cannot configure $path: $error")
                }
            }
        } catch (e: SecurityException) {
            throw e
        } catch (ignored: Exception) {
            // stty utility may not be present on emulators or restricted environments
        }
    }

    override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
        val currentFd = fd ?: throw IOException("Device is not open")
        if (!_isOpen) return -1

        return try {
            val bytesRead = Os.read(currentFd, buffer, offset, count)
            if (bytesRead <= 0) -1 else bytesRead
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) {
                0
            } else {
                _isOpen = false
                throw IOException("Read error on $devicePath: ${e.message}", e)
            }
        }
    }

    override fun close() {
        _isOpen = false
        fd?.let {
            try {
                Os.close(it)
            } catch (ignored: Exception) {
            } finally {
                fd = null
            }
        }
    }
}
