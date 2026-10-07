package com.exodia.batteryalert.platform.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.exodia.batteryalert.core.transport.ByteStreamSource
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException

/**
 * Android implementation of [ByteStreamSource] using usb-serial-for-android.
 *
 * Handles device discovery, permission validation, port configuration, and I/O.
 */
class AndroidUsbSerialStream(
    private val usbManager: UsbManager,
    private val baudRate: Int = 57600,
    private val dataBits: Int = 8,
    private val stopBits: Int = 1,
    private val parity: Int = 0,
    private val targetDevice: UsbDevice? = null,
    private val portIndex: Int = 0,
) : ByteStreamSource {

    override val displayName: String
        get() = port?.device?.deviceName ?: "USB Serial ($baudRate baud)"

    private var connection: UsbDeviceConnection? = null
    private var port: UsbSerialPort? = null
    private var _isOpen = false
    override val isOpen: Boolean get() = _isOpen

    override fun open() {
        if (_isOpen) return

        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            throw IOException("No compatible USB serial drivers found. Check OTG cable connection.")
        }

        val driver = if (targetDevice != null) {
            availableDrivers.firstOrNull { it.device == targetDevice }
                ?: throw IOException("Target USB device not found or unsupported.")
        } else {
            availableDrivers.first()
        }

        if (!usbManager.hasPermission(driver.device)) {
            throw SecurityException("USB permission denied for device: ${driver.device.deviceName}")
        }

        val conn = usbManager.openDevice(driver.device)
            ?: throw IOException("Failed to open USB device connection via UsbManager.")

        val p = driver.ports.getOrNull(portIndex) ?: driver.ports.firstOrNull()
            ?: throw IOException("No ports available on USB serial driver.")

        try {
            p.open(conn)
            val sb = when (stopBits) {
                2 -> UsbSerialPort.STOPBITS_2
                else -> UsbSerialPort.STOPBITS_1
            }
            val par = when (parity) {
                1 -> UsbSerialPort.PARITY_ODD
                2 -> UsbSerialPort.PARITY_EVEN
                else -> UsbSerialPort.PARITY_NONE
            }
            p.setParameters(baudRate, dataBits, sb, par)

            connection = conn
            port = p
            _isOpen = true
        } catch (e: Exception) {
            try { p.close() } catch (ignored: Exception) {}
            try { conn.close() } catch (ignored: Exception) {}
            throw IOException("Failed to configure USB serial port: ${e.message}", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
        val p = port ?: throw IOException("USB serial port is not open")
        if (!_isOpen) return -1
        return try {
            if (offset == 0 && count == buffer.size) {
                p.read(buffer, 200)
            } else {
                val temp = ByteArray(count)
                val n = p.read(temp, 200)
                if (n > 0) {
                    System.arraycopy(temp, 0, buffer, offset, n)
                }
                n
            }
        } catch (e: Exception) {
            _isOpen = false
            throw e
        }
    }

    override fun close() {
        _isOpen = false
        try {
            port?.close()
        } catch (ignored: Exception) {
        } finally {
            port = null
        }
        try {
            connection?.close()
        } catch (ignored: Exception) {
        } finally {
            connection = null
        }
    }
}
