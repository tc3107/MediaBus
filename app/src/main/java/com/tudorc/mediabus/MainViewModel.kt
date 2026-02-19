package com.tudorc.mediabus

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tudorc.mediabus.data.HostStoreRepository
import com.tudorc.mediabus.model.DevicePresence
import com.tudorc.mediabus.model.PairedDeviceStatus
import com.tudorc.mediabus.model.TransferSummary
import com.tudorc.mediabus.network.NetworkAddressResolver
import com.tudorc.mediabus.service.HostActionResult
import com.tudorc.mediabus.service.MediaBusHostService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HostControlUiState(
    val folderUri: String? = null,
    val folderDisplayName: String = "No folder selected",
    val hasValidFolder: Boolean = false,
    val serverRunning: Boolean = false,
    val serverTransitioning: Boolean = false,
    val hostName: String = MediaBusHostService.DEFAULT_HOST_NAME,
    val ipAddress: String? = null,
    val statusText: String = "Server offline",
    val error: String? = null,
    val availableIps: List<String> = emptyList(),
    val showHiddenFiles: Boolean = false,
    val allowUpload: Boolean = true,
    val allowDownload: Boolean = true,
    val allowDelete: Boolean = true,
    val pairedDevices: List<PairedDeviceStatus> = emptyList(),
    val transferSummary: TransferSummary = TransferSummary(),
) {
    val url: String
        get() = "https://$hostName:${MediaBusHostService.SERVER_PORT}"
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val context = getApplication<Application>()
    private val repository = HostStoreRepository(context)

    val uiState: StateFlow<HostControlUiState> = combine(
        repository.settingsFlow,
        repository.pairedDevicesFlow,
        MediaBusHostService.state,
    ) { settings, storedDevices, serviceState ->
        val uri = settings.sharedFolderUri?.let(Uri::parse)
        val document = uri?.let { DocumentFile.fromTreeUri(context, it) }
        val displayName = document?.name ?: "No folder selected"
        val hasValidFolder = document?.canRead() == true

        val statuses = if (serviceState.pairedDevicesReady) {
            serviceState.pairedDevices
        } else {
            storedDevices.map { device -> PairedDeviceStatus(device = device, presence = DevicePresence.Disconnected) }
        }

        val localIps = NetworkAddressResolver.listPrivateIpv4Addresses().mapNotNull { it.hostAddress }
        val availableIps = (serviceState.availableIps + localIps).distinct()

        HostControlUiState(
            folderUri = settings.sharedFolderUri,
            folderDisplayName = displayName,
            hasValidFolder = hasValidFolder,
            serverRunning = serviceState.running,
            serverTransitioning = serviceState.transitioning,
            hostName = serviceState.hostname,
            ipAddress = serviceState.ipAddress,
            statusText = serviceState.statusText,
            error = serviceState.error,
            availableIps = availableIps,
            showHiddenFiles = settings.showHiddenFiles,
            allowUpload = settings.allowUpload,
            allowDownload = settings.allowDownload,
            allowDelete = settings.allowDelete,
            pairedDevices = statuses,
            transferSummary = serviceState.transferSummary,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HostControlUiState(),
    )

    fun startServer() {
        val folder = uiState.value.folderUri ?: return
        if (!uiState.value.hasValidFolder) return
        MediaBusHostService.start(context, folder)
    }

    fun stopServer() {
        MediaBusHostService.stop(context)
    }

    fun onFolderSelected(
        uri: Uri,
        persistFlags: Int,
    ) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, persistFlags)
        }

        viewModelScope.launch {
            repository.setSharedFolderUri(uri.toString())
        }
    }

    fun onShowHiddenFiles(enabled: Boolean) {
        viewModelScope.launch {
            repository.setShowHiddenFiles(enabled)
        }
    }

    fun onAllowUpload(enabled: Boolean) {
        viewModelScope.launch {
            repository.setAllowUpload(enabled)
        }
    }

    fun onAllowDownload(enabled: Boolean) {
        viewModelScope.launch {
            repository.setAllowDownload(enabled)
        }
    }

    fun onAllowDelete(enabled: Boolean) {
        viewModelScope.launch {
            repository.setAllowDelete(enabled)
        }
    }

    fun approvePairCode(code: String): HostActionResult {
        return MediaBusHostService.approvePairByCode(code.trim())
    }

    fun approvePairPayload(payload: String): HostActionResult {
        val token = extractToken(payload)
        if (!token.isNullOrBlank()) {
            return MediaBusHostService.approvePairByToken(token)
        }
        val digits = payload.filter { it.isDigit() }
        return if (digits.length >= 6) {
            MediaBusHostService.approvePairByCode(digits.takeLast(6))
        } else {
            HostActionResult.NotFound
        }
    }

    fun revokeDevice(deviceId: String): Boolean {
        val removedInService = MediaBusHostService.revokeDevice(deviceId)
        return removedInService
    }

    private fun extractToken(payload: String): String? {
        if (payload.isBlank()) return null
        val uri = runCatching { Uri.parse(payload.trim()) }.getOrNull() ?: return null
        return uri.getQueryParameter("token")
    }
}
