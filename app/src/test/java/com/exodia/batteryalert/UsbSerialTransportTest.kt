package com.exodia.batteryalert

import com.exodia.batteryalert.core.model.ConnectionState
import com.exodia.batteryalert.core.model.TelemetryFrame
import com.exodia.batteryalert.core.transport.ByteStreamSource
import com.exodia.batteryalert.core.transport.MavlinkCodec
import com.exodia.batteryalert.core.transport.MavlinkCodecConfig
import com.exodia.batteryalert.core.transport.UsbSerialTransport
import io.dronefleet.mavlink.common.BatteryStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream

class UsbSerialTransportTest {

    private class MockByteStreamSource : ByteStreamSource {
        override val displayName = "Mock USB Serial"
        private val queue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        private var _open = false
        override val isOpen: Boolean get() = _open

        override fun open() {
            _open = true
        }

        override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
            if (!_open) return -1
            val chunk = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) ?: return 0
            val toCopy = minOf(count, chunk.size)
            System.arraycopy(chunk, 0, buffer, offset, toCopy)
            return toCopy
        }

        fun writeData(bytes: ByteArray) {
            queue.offer(bytes)
        }

        override fun close() {
            _open = false
        }
    }

    @Test
    fun testUsbSerialNoDeviceReportsActionableError() = runBlocking {
        val transport = UsbSerialTransport(streamSource = null)
        transport.start()

        val state = transport.connectionState.value
        assertTrue(state is ConnectionState.Error)
        val msg = (state as ConnectionState.Error).message
        assertTrue(msg.contains("No device connected") || msg.contains("NOT_TESTED"))
    }

    @Test
    fun testUsbSerialEndToEndStream() = runBlocking {
        val mockSource = MockByteStreamSource()
        val transport = UsbSerialTransport(
            baudRate = 57600,
            vehicleSystemId = 1,
            streamSource = mockSource
        )

        transport.start()
        delay(50)

        assertEquals(ConnectionState.Connecting, transport.connectionState.value)

        val senderCodec = MavlinkCodec(MavlinkCodecConfig(targetSystemId = 1, targetComponentId = 1))
        val batteryPayload = BatteryStatus.builder()
            .id(0)
            .temperature(3100)
            .currentBattery(4200)
            .batteryRemaining(92)
            .voltages(List(10) { 3900 })
            .voltagesExt(listOf(0, 0, 0, 0))
            .build()

        val packetBytes = senderCodec.encodeMavlink2(1, 1, 1, batteryPayload)

        val deferredFrame = async {
            withTimeout(3000) {
                transport.frames.first()
            }
        }

        mockSource.writeData(packetBytes)

        val received = deferredFrame.await()
        assertTrue(received is TelemetryFrame.Battery)
        val battery = (received as TelemetryFrame.Battery).value
        assertEquals(92, battery.remainingPercent)
        assertEquals(42.0f, battery.currentA, 0.01f)

        assertEquals(ConnectionState.Connected, transport.connectionState.value)

        transport.stop()
        assertEquals(ConnectionState.Disconnected, transport.connectionState.value)
    }
}
