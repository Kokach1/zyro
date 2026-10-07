package com.exodia.batteryalert

import com.exodia.batteryalert.core.config.RealConnectionConfig
import com.exodia.batteryalert.core.model.ConnectionState
import com.exodia.batteryalert.core.transport.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SerialPlatformWiringTest {

    private class ControlledByteStream(val name: String = "TestStream") : ByteStreamSource {
        override val displayName: String = name
        var openCalled = false
        var closeCalled = false
        var throwOnOpen: Exception? = null
        var throwOnRead: Exception? = null

        private val bufferQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()

        override val isOpen: Boolean get() = openCalled && !closeCalled

        override fun open() {
            throwOnOpen?.let { throw it }
            openCalled = true
        }

        override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
            throwOnRead?.let { throw it }
            if (!isOpen) return -1
            val chunk = bufferQueue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS) ?: return 0
            val n = minOf(count, chunk.size)
            System.arraycopy(chunk, 0, buffer, offset, n)
            return n
        }

        fun push(bytes: ByteArray) {
            bufferQueue.offer(bytes)
        }

        override fun close() {
            closeCalled = true
        }
    }

    @Test
    fun testStreamSourceInjectedViaProvider() {
        val testStream = ControlledByteStream("InjectedUsb")
        val provider = object : StreamSourceProvider {
            override fun provideUsbSerialStream(config: RealConnectionConfig.UsbSerial): ByteStreamSource? {
                return testStream
            }
            override fun provideInternalSerialStream(config: RealConnectionConfig.InternalSerial): ByteStreamSource? {
                return null
            }
        }

        TransportFactory.streamSourceProvider = provider

        val config = RealConnectionConfig.UsbSerial(baudRate = 115200)
        val result = TransportFactory.createReal(config)
        assertTrue(result.isSuccess)
        val transport = result.getOrThrow()
        assertTrue(transport is UsbSerialTransport)

        // Reset provider
        TransportFactory.streamSourceProvider = null
    }

    @Test
    fun testByteStreamOffsetCountContract() {
        val stream = ControlledByteStream("OffsetTest")
        stream.open()
        stream.push(byteArrayOf(1, 2, 3, 4, 5))

        val dest = ByteArray(10)
        val bytesRead = stream.read(dest, 2, 3)

        assertEquals(3, bytesRead)
        assertEquals(0.toByte(), dest[0])
        assertEquals(0.toByte(), dest[1])
        assertEquals(1.toByte(), dest[2])
        assertEquals(2.toByte(), dest[3])
        assertEquals(3.toByte(), dest[4])
        assertEquals(0.toByte(), dest[5])

        stream.close()
        assertTrue(stream.closeCalled)
        assertFalse(stream.isOpen)
    }

    @Test
    fun testInternalSerialAccessFailure() = runBlocking {
        val transport = InternalSerialTransport(
            devicePath = "/dev/nonexistent_serial_node_test",
            baudRate = 921600,
            streamSource = null
        )

        transport.start()
        val state = transport.connectionState.value
        assertTrue("Expected Error state when device node does not exist", state is ConnectionState.Error)
        val message = (state as ConnectionState.Error).message
        assertTrue(message.contains("Device /dev/nonexistent_serial_node_test not found"))
    }

    @Test
    fun testCancellationClosesStreamAndJoinsJob() = runBlocking {
        val stream = ControlledByteStream("CancellationTest")
        val transport = UsbSerialTransport(
            baudRate = 57600,
            streamSource = stream
        )

        transport.start()
        delay(50)
        assertTrue("Stream must be opened", stream.openCalled)
        assertFalse("Stream must not be closed yet", stream.closeCalled)

        transport.stop()
        delay(50)
        assertTrue("Stream must be closed on stop()", stream.closeCalled)
        assertEquals(ConnectionState.Disconnected, transport.connectionState.value)
    }
}
