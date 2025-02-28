package crazydude.com.telemetry.protocol

import android.util.Log
import crazydude.com.telemetry.protocol.crc.CRCMAVLink
import crazydude.com.telemetry.protocol.decoder.DataDecoder
import crazydude.com.telemetry.protocol.decoder.MAVLinkDataDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MAVLink2Protocol : Protocol {

    constructor(dataListener: DataDecoder.Listener) : super(MAVLinkDataDecoder(dataListener))
    constructor(dataDecoder: DataDecoder) : super(dataDecoder)

    private val crc = CRCMAVLink()

    private var state = State.IDLE
    private var buffer: IntArray = IntArray(0)
    private var payloadIndex = 0
    private var packetLength = 0
    private var packetIncompatibility = 0
    private var packetCompatibility = 0
    private var packetIndex = 0
    private var systemId = 0
    private var componentId = 0
    private var messageId = 0
    private var messageIdBuffer = ByteArray(4)
    private var messageIdIndex = 0
    private var crcLow: Int? = null
    private var crcHigh: Int? = null
    private var unique = HashSet<Int>()

    companion object {
        enum class State {
            IDLE, LENGTH, INCOMPATIBILITY, COMPATIBILITY, INDEX, SYSTEM_ID, COMPONENT_ID, MESSAGE_ID, PAYLOAD, CRC
        }

        private const val PACKET_MARKER = 0xFD

        private const val MAV_PACKET_HEARTBEAT_ID = 0
        private const val MAV_PACKET_STATUS_ID = 1
        private const val MAV_PACKET_ATTITUDE_ID = 30
        private const val MAV_PACKET_RC_CHANNEL_ID = 35
        private const val MAV_PACKET_VFR_HUD_ID = 74
        private const val MAV_PACKET_GPS_RAW_ID = 24
        private const val MAV_PACKET_RADIO_STATUS_ID = 109
        private const val MAV_PACKET_GPS_ORIGIN_ID = 49

        private const val MAV_PACKET_STATUS_LENGTH = 31
        private const val MAV_PACKET_HEARTBEAT_LENGTH = 9
        private const val MAV_PACKET_RC_CHANNEL_LENGTH = 22
        private const val MAV_PACKET_ATTITUDE_LENGTH = 28
        private const val MAV_PACKET_VFR_HUD_LENGTH = 20
        private const val MAV_PACKET_GPS_RAW_LENGTH = 30
        private const val MAV_PACKET_RADIO_STATUS_LENGTH = 9
    }

    override fun process(data: Int) {
        when (state) {
            State.IDLE -> {
                if (data == PACKET_MARKER) {
                    state = State.LENGTH
                }
            }
            State.LENGTH -> {
                packetLength = data
                state = State.INCOMPATIBILITY
            }
            State.INCOMPATIBILITY -> {
                packetIncompatibility = data
                state = State.COMPATIBILITY
            }
            State.COMPATIBILITY -> {
                packetCompatibility = data
                state = State.INDEX
            }
            State.INDEX -> {
                packetIndex = data
                state = State.SYSTEM_ID
            }
            State.SYSTEM_ID -> {
                systemId = data
                state = State.COMPONENT_ID
            }
            State.COMPONENT_ID -> {
                componentId = data
                state = State.MESSAGE_ID
                messageIdIndex = 0
            }
            State.MESSAGE_ID -> {
                messageIdBuffer[messageIdIndex++] = data.toByte()
                if (messageIdIndex >= 3) {
                    messageId = ByteBuffer.wrap(messageIdBuffer).order(ByteOrder.LITTLE_ENDIAN).int
                    state = State.PAYLOAD
                    payloadIndex = 0
                    buffer = IntArray(packetLength)
                }
            }
            State.PAYLOAD -> {
                if (payloadIndex < packetLength) {
                    buffer[payloadIndex] = data
                    payloadIndex++
                } else {
                    state = State.CRC
                    crcLow = data
                    crcHigh = null
                }
            }
            State.CRC -> {
                crcHigh = data
                if (checkCrc()) {
                    processPacket()
                } else {
                    Log.d("MAVLink2Protocol", "Bad CRC for $messageId")
                }
                state = State.IDLE
            }
        }
    }

    private fun processPacket() {
        val byteBuffer = ByteBuffer.wrap(buffer.copyOf(255).map { it.toByte() }.toByteArray())
            .order(ByteOrder.LITTLE_ENDIAN)
        if (messageId == MAV_PACKET_STATUS_ID) {
            val sensors = byteBuffer.int
            val enabledSensors = byteBuffer.int
            val healthSensors = byteBuffer.int
            val load = byteBuffer.short
            val voltage = byteBuffer.short
            val current = byteBuffer.short
            val dropRate = byteBuffer.short
            val errors = byteBuffer.short
            val errorsCount1 = byteBuffer.short
            val errorsCount2 = byteBuffer.short
            val errorsCount3 = byteBuffer.short
            val errorsCount4 = byteBuffer.short
            val fuel = byteBuffer.get()

            dataDecoder.decodeData(
                Protocol.Companion.TelemetryData(
                    VBAT,
                    voltage.toInt()
                )
            )
            dataDecoder.decodeData(
                Protocol.Companion.TelemetryData(
                    Protocol.CURRENT,
                    current.toInt()
                )
            )
            dataDecoder.decodeData(Protocol.Companion.TelemetryData(Protocol.FUEL, fuel.toInt()))
        } else if (messageId == MAV_PACKET_HEARTBEAT_ID) {
            val customMode = byteBuffer.int
            val aircraftType = byteBuffer.get()
            val autopilotClass = byteBuffer.get()
            val mode = byteBuffer.get()
            val state = byteBuffer.get()
            val version = byteBuffer.get()
            dataDecoder.decodeData(Protocol.Companion.TelemetryData(FLYMODE, mode.toInt(), byteBuffer.array()))
        } else if (messageId == MAV_PACKET_RC_CHANNEL_ID) {
            //Channels RC
        } else if (messageId == MAV_PACKET_ATTITUDE_ID) {
            dataDecoder.decodeData(
                Protocol.Companion.TelemetryData(
                    ATTITUDE,
                    0,
                    byteBuffer.array()
                )
            )
        } else if (messageId == MAV_PACKET_VFR_HUD_ID) {
            val airSpeed = byteBuffer.float
            val groundSpeed = byteBuffer.float
            val alt = byteBuffer.float
            val vspeed = byteBuffer.float
            val heading = byteBuffer.short
            val throttle = byteBuffer.short

            dataDecoder.decodeData(
                Protocol.Companion.TelemetryData(
                    GSPEED,
                    (groundSpeed * 100).toInt()
                )
            )
            dataDecoder.decodeData(Protocol.Companion.TelemetryData(ALTITUDE, (alt * 100).toInt()))

        } else if (messageId == MAV_PACKET_RADIO_STATUS_ID) {
            val rxErrors = byteBuffer.short
            val fixed = byteBuffer.short
            val rssi = byteBuffer.get()
            val remRssi = byteBuffer.get()
            val txbuf = byteBuffer.get()
            val noise = byteBuffer.get()
            val remnoise = byteBuffer.get()
        } else if (messageId == MAV_PACKET_GPS_RAW_ID) {
            val time = byteBuffer.long
            val lat = byteBuffer.int 
            val lon = byteBuffer.int
            val altitude = byteBuffer.int
            val eph = byteBuffer.short
            val epv = byteBuffer.short
            val vel = byteBuffer.short
            val cog = byteBuffer.short
            val fixType = byteBuffer.get()
            val satellites = byteBuffer.get()

            dataDecoder.decodeData(
                Protocol.Companion.TelemetryData(
                    Protocol.GPS_STATE,
                    fixType.toInt()
                )
            )
            dataDecoder.decodeData(
                Protocol.Companion.TelemetryData(
                    Protocol.GPS_SATELLITES,
                    satellites.toInt()
                )
            )
            dataDecoder.decodeData(Protocol.Companion.TelemetryData(Protocol.GPS_LATITUDE, lat))
            dataDecoder.decodeData(Protocol.Companion.TelemetryData(Protocol.GPS_LONGITUDE, lon))
            if (cog.toInt() != -1)
                dataDecoder.decodeData(
                    Protocol.Companion.TelemetryData(
                        Protocol.HEADING,
                        cog.toInt()
                    )
                )
        } else if (messageId == MAV_PACKET_GPS_ORIGIN_ID) {
            val lat = byteBuffer.int
            val lon = byteBuffer.int

            dataDecoder.decodeData(Protocol.Companion.TelemetryData(GPS_ORIGIN_LATITUDE, lat))
            dataDecoder.decodeData(Protocol.Companion.TelemetryData(GPS_ORIGIN_LONGITUDE, lon))
        } else {
            unique.add(messageId)
        }
    }

    private fun checkCrc(): Boolean {
        crc.start_checksum()
        crc.update_checksum(packetLength)
        crc.update_checksum(packetIncompatibility)
        crc.update_checksum(packetCompatibility)
        crc.update_checksum(packetIndex)
        crc.update_checksum(systemId)
        crc.update_checksum(componentId)
        messageIdBuffer.copyOfRange(0, 3).forEach { crc.update_checksum(it.toInt()) }
        buffer.forEach { crc.update_checksum(it) }
        crc.finish_checksum(messageId)
        return crcHigh == crc.msb && crcLow == crc.lsb
    }
}