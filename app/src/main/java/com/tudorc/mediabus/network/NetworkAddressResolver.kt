package com.tudorc.mediabus.network

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object NetworkAddressResolver {
    fun listPrivateIpv4Addresses(): List<InetAddress> {
        return NetworkInterface.getNetworkInterfaces()
            ?.toList()
            .orEmpty()
            .filter { network ->
                network.isUp && !network.isLoopback && !network.isVirtual
            }
            .flatMap { network ->
                network.inetAddresses.toList()
            }
            .filterIsInstance<Inet4Address>()
            .filter { address ->
                !address.isLoopbackAddress &&
                    (
                        address.isSiteLocalAddress ||
                            address.hostAddress?.startsWith("169.254.") == true
                        )
            }
            .sortedBy { it.hostAddress.orEmpty() }
    }

    fun findBestPrivateIpv4(): InetAddress? {
        return listPrivateIpv4Addresses().firstOrNull()
    }
}
