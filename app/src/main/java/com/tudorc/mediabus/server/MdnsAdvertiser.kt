package com.tudorc.mediabus.server

import android.content.Context
import android.net.wifi.WifiManager
import com.tudorc.mediabus.util.ServerLogger
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import java.net.InetAddress

class MdnsAdvertiser(private val context: Context) {
    private var jmdns: JmDNS? = null
    private var serviceInfo: ServiceInfo? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start(
        address: InetAddress,
        port: Int,
        hostLabel: String = "mediabus",
    ) {
        ServerLogger.i(LOG_COMPONENT, "Starting mDNS advertisement host=$hostLabel.local ip=${address.hostAddress} port=$port")
        stop()
        acquireMulticastLock()

        val instance = JmDNS.create(address, hostLabel)
        val service = ServiceInfo.create(
            "_https._tcp.local.",
            "MediaBus",
            port,
            0,
            0,
            mapOf("path" to "/", "host" to "$hostLabel.local"),
        )

        instance.registerService(service)
        jmdns = instance
        serviceInfo = service
        ServerLogger.i(LOG_COMPONENT, "mDNS advertisement registered host=${advertisedHostname("$hostLabel.local")}")
    }

    fun stop() {
        ServerLogger.i(LOG_COMPONENT, "Stopping mDNS advertisement")
        val instance = jmdns
        val registeredService = serviceInfo
        if (instance != null) {
            runCatching {
                if (registeredService != null) {
                    instance.unregisterService(registeredService)
                }
            }
            runCatching { instance.unregisterAllServices() }
            runCatching { instance.close() }
        }
        serviceInfo = null
        jmdns = null

        multicastLock?.let { lock ->
            if (lock.isHeld) {
                runCatching { lock.release() }
            }
        }
        multicastLock = null
    }

    fun advertisedHostname(defaultValue: String = "mediabus.local"): String {
        val resolved = jmdns?.hostName?.trimEnd('.')
        return resolved ?: defaultValue
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) {
            return
        }

        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifiManager.createMulticastLock("mediabus-mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
        ServerLogger.d(LOG_COMPONENT, "Acquired multicast lock for mDNS")
    }

    private companion object {
        private const val LOG_COMPONENT = "Mdns"
    }
}
