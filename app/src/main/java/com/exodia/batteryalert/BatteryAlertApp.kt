package com.exodia.batteryalert

import android.app.Application
import com.exodia.batteryalert.core.transport.SimulatorTransport
import com.exodia.batteryalert.core.transport.TransportFactory

class BatteryAlertApp : Application() { val container by lazy { AppContainer() } }
class AppContainer { val transport = TransportFactory.createDefaultTransport(); val simulator get() = transport as? SimulatorTransport }
