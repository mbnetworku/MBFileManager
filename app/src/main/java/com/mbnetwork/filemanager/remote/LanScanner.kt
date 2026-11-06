package com.mbnetwork.filemanager.remote

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Looks for computers, NAS boxes and routers on the Wi-Fi network that offer file sharing (SMB / FTP / SFTP). */
object LanScanner {
    class Host(val ip: String, val name: String, val services: List<String>)

    private val ports = linkedMapOf(445 to ConnType.SMB, 21 to ConnType.FTP, 22 to ConnType.SFTP)

    fun myIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }?.hostAddress
    } catch (t: Throwable) { null }

    /** Blocking: call from a background thread. [onFound] is called for each host as it is found. */
    fun scan(onFound: (Host) -> Unit): Int {
        val me = myIp() ?: return 0
        val prefix = me.substringBeforeLast('.') + "."
        val pool = Executors.newFixedThreadPool(48)
        var found = 0
        for (i in 1..254) {
            val ip = prefix + i
            if (ip == me) continue
            pool.execute {
                val open = ArrayList<String>()
                for ((port, type) in ports) {
                    try { Socket().use { it.connect(InetSocketAddress(ip, port), 400); open += type } } catch (t: Throwable) { }
                }
                if (open.isNotEmpty()) {
                    val name = try { InetAddress.getByName(ip).canonicalHostName.takeIf { it != ip } ?: "" } catch (t: Throwable) { "" }
                    synchronized(this) { found++ }
                    onFound(Host(ip, name, open))
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(60, TimeUnit.SECONDS)
        return found
    }
}
