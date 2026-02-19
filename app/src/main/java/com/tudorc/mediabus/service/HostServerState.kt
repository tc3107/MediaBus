package com.tudorc.mediabus.service

import com.tudorc.mediabus.model.PairedDeviceStatus
import com.tudorc.mediabus.model.TransferSummary

data class HostServerState(
    val running: Boolean = false,
    val transitioning: Boolean = false,
    val hostname: String = "mediabus.local",
    val ipAddress: String? = null,
    val port: Int = 8443,
    val statusText: String = "Server offline",
    val error: String? = null,
    val availableIps: List<String> = emptyList(),
    val pairedDevicesReady: Boolean = false,
    val pairedDevices: List<PairedDeviceStatus> = emptyList(),
    val transferSummary: TransferSummary = TransferSummary(),
) {
    val url: String
        get() = "https://$hostname:$port"
}
