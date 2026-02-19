package com.tudorc.mediabus.model

data class HostSettings(
    val sharedFolderUri: String? = null,
    val showHiddenFiles: Boolean = false,
    val allowUpload: Boolean = true,
    val allowDownload: Boolean = true,
    val allowDelete: Boolean = true,
)

data class PairedDevice(
    val deviceId: String,
    val displayName: String,
    val userAgent: String,
    val lastKnownIp: String,
    val createdAtEpochMs: Long,
    val lastConnectedEpochMs: Long,
)

enum class DevicePresence {
    Disconnected,
    Connected,
    Transferring,
}

data class PairedDeviceStatus(
    val device: PairedDevice,
    val presence: DevicePresence = DevicePresence.Disconnected,
    val queuedTransfers: Int = 0,
    val activeTransfers: Int = 0,
)

enum class TransferDirection {
    Uploading,
    Downloading,
    Mixed,
    None,
}

data class TransferSummary(
    val inProgress: Boolean = false,
    val direction: TransferDirection = TransferDirection.None,
    val activeFiles: Int = 0,
    val totalFiles: Int = 0,
    val activeBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val overallTransferredBytes: Long = 0L,
    val overallTotalBytes: Long = 0L,
    val currentFileTransferredBytes: Long = 0L,
    val currentFileTotalBytes: Long = 0L,
)
