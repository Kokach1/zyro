package com.exodia.batteryalert

import com.exodia.batteryalert.core.model.ConnectionState
import com.exodia.batteryalert.core.model.TelemetryFrame
import com.exodia.batteryalert.core.transport.ReplayTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReplayTransportTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testReplayFileNotFoundReportsError() = runBlocking {
        val transport = ReplayTransport(filePath = "non_existent_file.jsonl")
        transport.start()

        val state = transport.connectionState.value
        assertTrue(state is ConnectionState.Error)
        assertTrue((state as ConnectionState.Error).message.contains("not found"))
    }

    @Test
    fun testReplayJsonlEmitsFrames() = runBlocking {
        val testFile = tempFolder.newFile("test_flight.jsonl")
        testFile.writeText(
            """
            {"type":"battery", "cells":[3.85, 3.84, 3.85, 3.84, 3.85, 3.84], "current":25.0, "percent":85, "temperature":26.5}
            {"type":"position", "lat":37.1234, "lon":-122.5678, "alt":45.0, "speed":8.5}
            """.trimIndent()
        )

        val transport = ReplayTransport(
            filePath = testFile.absolutePath,
            speedMultiplier = 10
        )

        val deferredBattery = async {
            withTimeout(3000) {
                transport.frames.first()
            }
        }

        transport.start()

        val frame = deferredBattery.await()
        assertTrue(frame is TelemetryFrame.Battery)
        val battery = (frame as TelemetryFrame.Battery).value
        assertEquals(6, battery.cellVoltagesV.size)
        assertEquals(85, battery.remainingPercent)
        assertEquals(25.0f, battery.currentA!!, 0.01f)
        assertEquals(ConnectionState.Connected, transport.connectionState.value)

        transport.stop()
        assertEquals(ConnectionState.Disconnected, transport.connectionState.value)
    }
}
