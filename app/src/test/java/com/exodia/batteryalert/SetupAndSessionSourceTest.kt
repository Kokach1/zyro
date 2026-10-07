package com.exodia.batteryalert

import com.exodia.batteryalert.core.config.RealConnectionConfig
import com.exodia.batteryalert.core.config.TransportConfig
import com.exodia.batteryalert.core.config.TransportKind
import com.exodia.batteryalert.core.model.ConnectionState
import com.exodia.batteryalert.core.model.SessionSource
import com.exodia.batteryalert.core.transport.TelemetryTransport
import com.exodia.batteryalert.core.transport.TransportFactory
import com.exodia.batteryalert.ui.monitor.BatteryUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SetupAndSessionSourceTest {

    @Test
    fun testColdStartHasNoTransportOrSyntheticData() {
        val container = AppContainer()
        assertNull("Cold start must have no active transport", container.currentTransport)
        assertNull("Cold start must have no active simulator", container.activeSimulator)

        val defaultUiState = BatteryUiState()
        assertEquals(ConnectionState.Disconnected, defaultUiState.connection)
        assertNull(defaultUiState.sessionSource)
        assertFalse("Cold launch must not be in simulator mode", defaultUiState.simulatorMode)
        assertNull("Cold launch must have no synthetic analysis", defaultUiState.analysis)
    }

    @Test
    fun testRealApiRejectsSimulatorDefensively() = runBlocking {
        val container = AppContainer()
        val simConfig = TransportConfig(kind = TransportKind.SIMULATOR)
        val result = container.startTransport(simConfig)

        assertTrue("startTransport must reject SIMULATOR", result.isFailure)
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue("Error message must explain simulator cannot be started via real API",
            message.contains("Cannot start simulation via real transport API"))
    }

    @Test
    fun testInvalidPortOrPathDoesNotStart() = runBlocking {
        // Invalid UDP port 0
        val invalidUdp = RealConnectionConfig.Udp(bindPort = 0)
        assertTrue(invalidUdp.validate().isFailure)

        // Invalid UDP port 70000
        val invalidUdpHigh = RealConnectionConfig.Udp(bindPort = 70000)
        assertTrue(invalidUdpHigh.validate().isFailure)

        // Blank internal serial path
        val blankInternal = RealConnectionConfig.InternalSerial(devicePath = "")
        assertTrue(blankInternal.validate().isFailure)

        // Blank replay path
        val blankReplay = RealConnectionConfig.Replay(filePath = "")
        assertTrue(blankReplay.validate().isFailure)

        // Container rejects invalid config
        val container = AppContainer()
        val failResult = container.startRealTransport(blankInternal)
        assertTrue(failResult.isFailure)
        assertNull(container.currentTransport)
    }

    @Test
    fun testEditedConfigReachesSelectedAdapter() {
        val udpConfig = RealConnectionConfig.Udp(bindAddress = "192.168.1.50", bindPort = 14555, vehicleSystemId = 3)
        val result = TransportFactory.createReal(udpConfig)
        assertTrue(result.isSuccess)
        val transport = result.getOrThrow()
        assertEquals("udp", transport.id)
        assertEquals(SessionSource.LIVE_UDP, transport.sessionSource)
        assertTrue(transport.displayName.contains("14555"))
    }

    @Test
    fun testSimulatorAndReplayNeverLabeledLive() {
        assertFalse("SIMULATOR source must not be marked isLive", SessionSource.SIMULATOR.isLive)
        assertFalse("REPLAY source must not be marked isLive", SessionSource.REPLAY.isLive)
        assertTrue("LIVE_UDP must be marked isLive", SessionSource.LIVE_UDP.isLive)
        assertTrue("LIVE_USB must be marked isLive", SessionSource.LIVE_USB.isLive)
        assertTrue("LIVE_INTERNAL must be marked isLive", SessionSource.LIVE_INTERNAL.isLive)

        val simTransport = TransportFactory.createSimulator()
        assertEquals(SessionSource.SIMULATOR, simTransport.sessionSource)

        val replayTransport = TransportFactory.createReal(RealConnectionConfig.Replay("/dummy/path.tlog")).getOrThrow()
        assertEquals(SessionSource.REPLAY, replayTransport.sessionSource)
    }

    @Test
    fun testRapidSourceSwitchesRetainCorrectSource() = runBlocking {
        val container = AppContainer()

        // 1. Start Simulator
        val sim = container.startSimulator()
        assertEquals(SessionSource.SIMULATOR, container.currentTransport?.sessionSource)
        assertSame(sim, container.activeSimulator)

        // 2. Switch to UDP
        val udpResult = container.startRealTransport(RealConnectionConfig.Udp(bindPort = 14550))
        assertTrue(udpResult.isSuccess)
        assertEquals(SessionSource.LIVE_UDP, container.currentTransport?.sessionSource)
        assertNull("activeSimulator must be null after switching to UDP", container.activeSimulator)

        // 3. Switch to Replay
        val replayResult = container.startRealTransport(RealConnectionConfig.Replay("/dummy/replay.jsonl"))
        assertTrue(replayResult.isSuccess)
        assertEquals(SessionSource.REPLAY, container.currentTransport?.sessionSource)

        // 4. Stop
        container.stopTransport()
        assertNull(container.currentTransport)
    }
}
