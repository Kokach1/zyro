# Hardware Setup & Interfacing Guide — Zyro

This document specifies the electrical, physical, and platform interfacing requirements for deploying the **Zyro Smart Battery Alert System** on the **Skydroid G20 Smart Controller** with a **Skydroid GR01** telemetry receiver.

---

## 1. System Topology

```
+---------------------------+                +-------------------------------+
|      Agricultural Drone   |                |     Skydroid G20 Controller   |
|                           |                |                               |
| [Autopilot: ArduPilot/PX4]|                | +---------------------------+ |
|   |                       |  Air-to-Ground | | Android 13 OS (1920x1080) | |
|   +-- [MAVLink Telemetry] =================> | |                         | |
|   |                       |    Radio Link  | | | [Zyro Monitoring App] | | |
| [Smart Battery (14S/12S)] |                | | +---------------------------+ |
| [Spraying Pump Actuator]  |                | +-------------------------------+
+---------------------------+                +-------------------------------+
```

---

## 2. Platform Specifications

| Component | Specification |
|---|---|
| Target Hardware | Skydroid G20 Smart Controller |
| Operating System | Android 13 (API Level 33) |
| Display | 7.0-inch IPS LCD, 1920 x 1080 resolution, landscape orientation |
| Primary Input | Capacitive multi-touch (outdoor glove compatible) |
| Internal Communication | High-speed internal UART, USB Type-C OTG Host, Local IP stack |

---

## 3. Telemetry Ingestion Interfaces

Zyro supports three physical/network telemetry interfaces. Connection parameters must be confirmed during setup prior to initiating monitoring.

### Interface A: UDP MAVLink Ingestion (Recommended)

When using the Skydroid G20 internal radio bridge, telemetry is broadcast across the controller's internal virtual network interface.

1. **Network Configuration:**
   - Default Bind Address: `0.0.0.0` (all interfaces)
   - Default Port: `14550` (or alternate Skydroid telemetry port `14551`)
   - IP Assignment: The controller radio daemon typically routes packets across `192.168.144.101`.
2. **Setup Procedure:**
   - Launch Zyro and navigate to **Connection Setup**.
   - Select **Live UDP** as the connection source.
   - Enter the target listen port (e.g., `14550`).
   - Select **Start Monitoring**. Zyro binds a datagram socket and enters the `Connecting` state until valid MAVLink telemetry is received.

### Interface B: USB-UART Telemetry (USB-OTG Host)

For bench testing, external telemetry radios, or direct flight controller umbilical connections via USB OTG.

1. **Hardware Requirements:**
   - USB Type-C to USB Type-A OTG adapter or native Type-C data cable.
   - Supported USB-Serial Transceiver: FTDI (FT232R/FT2232), Silicon Labs CP210x, Prolific PL2303, Qinheng CH340/CH341, or USB CDC-ACM.
2. **Serial Port Parameters:**
   - Baud Rate: `57600`, `115200`, or `921600` (must match flight controller `SERIALx_BAUD`)
   - Data Bits: `8`
   - Stop Bits: `1`
   - Parity: `None`
   - Flow Control: `None`
3. **Android Permission Handling:**
   - When a USB-Serial device is attached, Android prompts for USB device access permission.
   - `AndroidUsbSerialStream` registers an explicit application-scoped `PendingIntent` and broadcast receiver to process the grant.
   - Disconnecting the USB cable triggers an automatic detach cleanup and transitions the monitor to `Disconnected`.

### Interface C: Internal Serial Port (`/dev/ttySx`)

For direct integration with the Skydroid internal radio processor via built-in system UART nodes.

1. **Configuration:**
   - Target Device Path: Enter the validated system node path (e.g., `/dev/ttyS1`, `/dev/ttyS3`).
   - Baud Rate: Configurable (`115200` to `921600`, standard `921600`).
2. **Access Prerequisites:**
   - The operating system must grant read and write permissions to the `/dev/ttySx` device node for the application UID.
   - The bridge uses POSIX termios parameters (`CS8`, raw non-canonical mode, zero parity).
   - If SELinux policy or file system permissions restrict access, the setup screen displays `serial access denied`.

---

## 4. Telemetry Stream Protocol

The flight controller must be configured to stream standard MAVLink common dialect messages at the specified minimum rates:

| MAVLink Message | Message ID | Minimum Rate | Description |
|---|---|---|---|
| `HEARTBEAT` | #0 | 1 Hz | Vehicle system identity, autopilot type, arming status. |
| `BATTERY_STATUS` | #147 | 2 Hz to 5 Hz | Primary source for individual cell voltages (slots 1..10 and extensions 11..14), pack voltage, current, consumed mAh, and temperature. |
| `SYS_STATUS` | #1 | 2 Hz | Bounded fallback for aggregate battery voltage and system current. |
| `GLOBAL_POSITION_INT` | #33 | 2 Hz to 5 Hz | Vehicle GPS latitude, longitude, and relative altitude. |
| `HOME_POSITION` | #242 | 1 Hz or on set | Geodesic home reference point used for dynamic RTL distance calculations. |
| `COMMAND_ACK` | #77 | On event | Confirmation feedback for actuator/interlock commands. |

---

## 5. Spraying Pump Interlock Interfacing (FR-5.1)

Zyro includes an optional automatic spraying pump cutoff interlock at $\le 20\%$ battery capacity:
1. **Safety Interlock Gate:** The feature is disabled by default and requires explicit operator opt-in in settings.
2. **Actuator Mapping Requirement:** The specific MAVLink command (e.g., `MAV_CMD_DO_SET_SERVO` or `MAV_CMD_DO_SET_RELAY`), target channel/instance, and cutoff PWM/state value must be confirmed for the drone's flight controller wiring before enabling live command transmission.
3. **Physical Operation Bench Requirement:** Do not activate pump interlock during simulation or bench testing without confirmed actuator channel assignments and ground clearance.
