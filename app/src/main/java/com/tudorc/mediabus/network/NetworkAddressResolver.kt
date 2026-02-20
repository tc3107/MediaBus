package com.tudorc.mediabus.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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

    @Suppress("DEPRECATION")
    fun listBindablePrivateIpv4Addresses(context: Context): List<InetAddress> {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val networks = connectivityManager?.getAllNetworks()?.asSequence().orEmpty()
        val fromConnectivity = connectivityManager
            ?.let { manager ->
                networks
                    .mapNotNull { network ->
                        val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
                        if (
                            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                        ) {
                            return@mapNotNull null
                        }
                        manager.getLinkProperties(network)
                    }
                    .flatMap { properties ->
                        properties.linkAddresses.asSequence().mapNotNull { linkAddress ->
                            linkAddress.address as? Inet4Address
                        }
                    }
                    .filter { address ->
                        !address.isLoopbackAddress &&
                            (
                                address.isSiteLocalAddress ||
                                    address.hostAddress?.startsWith("169.254.") == true
                                )
                    }
                    .distinctBy { it.hostAddress.orEmpty() }
                    .sortedBy { it.hostAddress.orEmpty() }
                    .toList()
            }
            .orEmpty()

        if (fromConnectivity.isNotEmpty()) {
            return fromConnectivity
        }

        return listPrivateIpv4Addresses()
            .filter { address ->
                val networkName = runCatching {
                    NetworkInterface.getByInetAddress(address)?.name
                }.getOrNull().orEmpty()
                isLikelyLanInterface(networkName)
            }
    }

    fun findBestPrivateIpv4(): InetAddress? {
        return listPrivateIpv4Addresses().firstOrNull()
    }

    private fun isLikelyLanInterface(name: String): Boolean {
        val normalized = name.lowercase()
        if (normalized.isBlank()) return false
        if (
            normalized.startsWith("rmnet") ||
            normalized.startsWith("ccmni") ||
            normalized.startsWith("pdp") ||
            normalized.startsWith("wwan")
        ) {
            return false
        }
        return normalized.startsWith("wlan") ||
            normalized.startsWith("eth") ||
            normalized.startsWith("en") ||
            normalized.startsWith("ap") ||
            normalized.startsWith("bridge") ||
            normalized.startsWith("br") ||
            normalized.startsWith("rndis")
    }
}
