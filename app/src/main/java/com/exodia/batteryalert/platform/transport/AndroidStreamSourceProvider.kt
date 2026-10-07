package com.exodia.batteryalert.platform.transport

import android.content.Context
import android.hardware.usb.UsbManager
import com.exodia.batteryalert.core.config.RealConnectionConfig
import com.exodia.batteryalert.core.transport.ByteStreamSource
import com.exodia.batteryalert.core.transport.StreamSourceProvider

/**
 * Platform provider supplying concrete Android stream sources (USB serial, internal serial)
 * to the pure core TransportFactory.
 */
class AndroidStreamSourceProvider(private val context: Context) : StreamSourceProvider {

    private val usbManager: UsbManager? by lazy {
        context.getSystemService(Context.USB_SERVICE) as? UsbManager
    }

    override fun provideUsbSerialStream(config: RealConnectionConfig.UsbSerial): ByteStreamSource? {
        val manager = usbManager ?: return null
        return AndroidUsbSerialStream(
            usbManager = manager,
            baudRate = config.baudRate,
            dataBits = config.dataBits,
            stopBits = config.stopBits,
            parity = config.parity,
            portIndex = config.portIndex
        )
    }

    override fun provideInternalSerialStream(config: RealConnectionConfig.InternalSerial): ByteStreamSource? {
        return AndroidInternalSerialStream(
            devicePath = config.devicePath,
            baudRate = config.baudRate
        )
    }
}
