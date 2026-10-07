package com.exodia.batteryalert

import com.exodia.batteryalert.core.model.ConnectionState
import com.exodia.batteryalert.core.model.TelemetryFrame
import com.exodia.batteryalert.core.transport.MavlinkCodec
import com.exodia.batteryalert.core.transport.MavlinkCodecConfig
import com.exodia.batteryalert.core.transport.UdpTransport
import io.dronefleet.mavlink.common.BatteryStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket

class UdpTransportTest {

    private fun findFreePort(): Int {
        val socket = ServerSocket(0)
        val port = socket.localPort
        socket.close()
        return port
    }

    @Test
    fun testUdpEndToEnd() = runBlocking {
        val port = findFreePort()
        val transport = UdpTransport(
            bindAddress = "127.0.0.1",
            bindPort = port,
            vehicleSystemId = 1
        )

        transport.start()
        delay(100) // allow bind

        // State is Connecting (Listening), NOT Connected yet
        assertEquals(ConnectionState.Connecting, transport.connectionState.value)

        // Create sender codec and encode a BatteryStatus packet
        val senderCodec = MavlinkCodec(MavlinkCodecConfig(targetSystemId = 1, targetComponentId = 1))
        val batteryPayload = BatteryStatus.builder()
            .id(0)
            .temperature(2900)
            .currentBattery(3500)
            .batteryRemaining(80)
            .voltages(List(10) { 3820 })
            .voltagesExt(listOf(0, 0, 0, 0))
            .build()

        val rawPacket = senderCodec.encodeMavlink2(1, 1, 1, batteryPayload)

        // Start collecting before sending
        val deferredFrame = async {
            withTimeout(3000) {
                transport.frames.first()
            }
        }
        delay(50)

        // Send over UDP to 127.0.0.1:port
        val clientSocket = DatagramSocket()
        val datagram = DatagramPacket(rawPacket, rawPacket.size, InetAddress.getByName("127.0.0.1"), port)
        clientSocket.send(datagram)
        clientSocket.close()

        val receivedFrame = deferredFrame.await()

        assertTrue(receivedFrame is TelemetryFrame.Battery)
        val battery = (receivedFrame as TelemetryFrame.Battery).value
        assertEquals(80, battery.remainingPercent)
        assertEquals(35.0f, battery.currentA, 0.01f)

        // Connection state should now be Connected!
        assertEquals(ConnectionState.Connected, transport.connectionState.value)

        transport.stop()
        assertEquals(ConnectionState.Disconnected, transport.connectionState.value)
    }
}
