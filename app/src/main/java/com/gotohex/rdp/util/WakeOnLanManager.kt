package com.gotohex.rdp.util

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Wake-on-LAN utility.
 *
 * Builds a "Magic Packet" — 6 bytes of 0xFF followed by the target
 * MAC address repeated 16 times — and broadcasts it as a UDP datagram
 * on port 9 (the WoL discard port). The target machine's NIC intercepts
 * the broadcast and powers on the host.
 */
object WakeOnLanManager {

    private const val WOL_PORT = 9

    /**
     * Validates and normalises a MAC address string.
     * Accepts separators ':', '-', or none.
     * Returns null if the input is not a valid 6-byte MAC address.
     */
    fun normaliseMac(raw: String): String? {
        val hex = raw.replace(":", "").replace("-", "").uppercase()
        if (hex.length != 12 || hex.any { it !in "0123456789ABCDEF" }) return null
        return hex.chunked(2).joinToString(":")
    }

    /**
     * Sends a Wake-on-LAN Magic Packet.
     *
     * @param context         Application context — used to acquire a
     *                        [WifiManager.MulticastLock] so that UDP broadcast
     *                        packets are not silently filtered by the WiFi driver.
     * @param macAddress      Target MAC, e.g. "AA:BB:CC:DD:EE:FF" or "AABBCCDDEEFF".
     * @param broadcastAddress Subnet broadcast, e.g. "255.255.255.255" or "192.168.1.255".
     * @throws IllegalArgumentException if [macAddress] is not a valid MAC.
     * @throws java.io.IOException on network failure.
     */
    suspend fun sendMagicPacket(
        context: Context,
        macAddress: String,
        broadcastAddress: String = "255.255.255.255"
    ) = withContext(Dispatchers.IO) {
        val normalised = normaliseMac(macAddress)
            ?: throw IllegalArgumentException("Invalid MAC address: $macAddress")

        val macBytes = normalised.split(":").map { it.toInt(16).toByte() }.toByteArray()

        // Magic Packet: FF FF FF FF FF FF + MAC × 16
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (rep in 0 until 16) {
            macBytes.copyInto(packet, 6 + rep * 6)
        }

        // FIX-WOL-DNS: Validate broadcastAddress is a numeric IPv4 address BEFORE
        // passing it to InetAddress.getByName(). Without this check, a hostname stored
        // in the profile (e.g. "evil.attacker.com") would trigger a DNS lookup to an
        // attacker-controlled server whenever WoL is invoked. WoL broadcast addresses
        // are always IPv4 dotted-decimal (e.g. "255.255.255.255", "192.168.1.255");
        // no hostname lookup should ever be needed.
        val ipv4Regex = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
        val match = ipv4Regex.matchEntire(broadcastAddress.trim())
            ?: throw IllegalArgumentException("Broadcast address must be a dotted-decimal IPv4 address: $broadcastAddress")
        val isValidOctets = match.groupValues.drop(1).all { it.toInt() in 0..255 }
        if (!isValidOctets) throw IllegalArgumentException("Broadcast address octets out of range: $broadcastAddress")

        val address = InetAddress.getByName(broadcastAddress.trim())
        val datagram = DatagramPacket(packet, packet.size, address, WOL_PORT)

        // Bug #8 FIX: Acquire a MulticastLock before sending.
        // Android's WiFi driver filters UDP broadcast/multicast packets unless a
        // MulticastLock is held. Without it, Magic Packets are silently dropped
        // on most devices over WiFi, making WoL appear to do nothing.
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifiManager.createMulticastLock("hexrdp_wol").apply {
            setReferenceCounted(false)
            acquire()
        }

        try {
            // Send 3 packets for reliability on congested networks.
            // delay() instead of Thread.sleep() for coroutine cancellation support.
            DatagramSocket().use { socket ->
                socket.broadcast = true
                repeat(3) { attempt ->
                    socket.send(datagram)
                    if (attempt < 2) delay(20)
                }
            }
        } finally {
            lock.release()
        }
    }

    /** Returns true iff the string looks like a valid MAC address. */
    fun isValidMac(raw: String): Boolean = normaliseMac(raw) != null
}
