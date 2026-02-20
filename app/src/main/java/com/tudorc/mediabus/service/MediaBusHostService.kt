package com.tudorc.mediabus.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.tudorc.mediabus.R
import com.tudorc.mediabus.data.HostStoreRepository
import com.tudorc.mediabus.model.TransferSummary
import com.tudorc.mediabus.network.NetworkAddressResolver
import com.tudorc.mediabus.runtime.HostRuntimeController
import com.tudorc.mediabus.server.CertificateManager
import com.tudorc.mediabus.server.MdnsAdvertiser
import com.tudorc.mediabus.server.MediaBusHttpServer
import com.tudorc.mediabus.util.ServerLogger
import com.tudorc.mediabus.util.TokenSigner
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.net.InetAddress
import java.util.logging.Level
import java.util.logging.Logger

class MediaBusHostService : LifecycleService() {
    private val lock = Mutex()
    private val mdnsAdvertiser by lazy { MdnsAdvertiser(applicationContext) }

    private lateinit var repository: HostStoreRepository
    private lateinit var runtime: HostRuntimeController
    private val runtimeReady = CompletableDeferred<Unit>()

    private var server: MediaBusHttpServer? = null
    private var activeFolderUri: Uri? = null
    private var activeIp: InetAddress? = null
    private var restartingForIpChange = false

    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            ServerLogger.d(LOG_COMPONENT, "Network available; scheduling server recheck")
            scheduleNetworkRecheck()
        }

        override fun onLost(network: Network) {
            ServerLogger.w(LOG_COMPONENT, "Network lost; scheduling server recheck")
            scheduleNetworkRecheck()
        }

        override fun onLinkPropertiesChanged(
            network: Network,
            linkProperties: LinkProperties,
        ) {
            ServerLogger.d(LOG_COMPONENT, "Network link properties changed; scheduling server recheck")
            scheduleNetworkRecheck()
        }
    }

    override fun onCreate() {
        super.onCreate()
        ServerLogger.i(LOG_COMPONENT, "Service created")
        configureNanoHttpdLogging()
        createNotificationChannel()

        repository = HostStoreRepository(applicationContext)
        lifecycleScope.launch {
            val secret = withContext(Dispatchers.IO) { repository.getOrCreateSigningSecret() }
            runtime = HostRuntimeController(
                repository = repository,
                tokenSigner = TokenSigner(secret),
                scope = lifecycleScope,
            )
            runtime.start()
            runtimeRef = runtime
            runtimeReady.complete(Unit)
            _state.value = _state.value.copy(
                pairedDevicesReady = true,
                pairedDevices = runtime.pairedDeviceSnapshot(),
                transferSummary = runtime.transferSummary.value,
            )

            launch {
                runtime.pairedStatuses.collectLatest { statuses ->
                    _state.value = _state.value.copy(
                        pairedDevicesReady = true,
                        pairedDevices = statuses,
                    )
                }
            }
            launch {
                runtime.transferSummary.collectLatest { summary ->
                    _state.value = _state.value.copy(transferSummary = summary)
                }
            }
            launch {
                repository.settingsFlow.collectLatest {
                    val latestIps = localIps()
                    _state.value = _state.value.copy(availableIps = latestIps)
                }
            }
        }

        _state.value = _state.value.copy(
            availableIps = localIps(),
            pairedDevicesReady = false,
            pairedDevices = emptyList(),
            transferSummary = TransferSummary(),
        )

        runCatching { connectivityManager.registerDefaultNetworkCallback(networkCallback) }
            .onSuccess {
                ServerLogger.i(LOG_COMPONENT, "Registered default network callback")
            }
            .onFailure { throwable ->
                ServerLogger.e(LOG_COMPONENT, "Failed to register network callback", throwable)
            }
    }

    override fun onDestroy() {
        ServerLogger.i(LOG_COMPONENT, "Service destroying")
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
            .onFailure { throwable ->
                ServerLogger.w(LOG_COMPONENT, "Unregister network callback failed: ${throwable.message}")
            }
        runtimeRef = null
        lifecycleScope.launch {
            lock.withLock {
                stopRuntime(updateState = true)
            }
        }
        super.onDestroy()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        ServerLogger.i(LOG_COMPONENT, "onStartCommand action=${intent?.action ?: "null"} startId=$startId")
        when (intent?.action) {
            ACTION_START -> {
                if (_state.value.transitioning) {
                    ServerLogger.w(LOG_COMPONENT, "Start requested while transition in progress; ignoring")
                    return Service.START_NOT_STICKY
                }
                val folderUriString = intent.getStringExtra(EXTRA_FOLDER_URI)
                if (folderUriString.isNullOrBlank()) {
                    ServerLogger.w(LOG_COMPONENT, "Start requested without folder URI")
                    setOfflineState("Select a folder first")
                    stopSelf()
                    return Service.START_NOT_STICKY
                }

                val folderUri = Uri.parse(folderUriString)
                ServerLogger.i(LOG_COMPONENT, "Start requested for folderUri=$folderUri")
                startInForeground("Starting MediaBus server...")
                _state.value = _state.value.copy(
                    transitioning = true,
                    statusText = "Starting server...",
                    error = null,
                )
                lifecycleScope.launch {
                    lock.withLock {
                        awaitRuntimeReady()
                        startRuntime(folderUri, preserveStateMessage = false)
                    }
                }
            }

            ACTION_STOP -> {
                if (_state.value.transitioning) {
                    ServerLogger.w(LOG_COMPONENT, "Stop requested while transition in progress; ignoring")
                    return Service.START_NOT_STICKY
                }
                ServerLogger.i(LOG_COMPONENT, "Stop requested")
                _state.value = _state.value.copy(
                    transitioning = true,
                    statusText = "Stopping server...",
                    error = null,
                )
                lifecycleScope.launch {
                    lock.withLock {
                        awaitRuntimeReady()
                        stopRuntime(updateState = true)
                    }
                    stopForegroundCompat()
                    stopSelf()
                }
            }

            else -> {
                ServerLogger.w(LOG_COMPONENT, "Ignoring unknown action=${intent?.action}")
            }
        }

        return Service.START_NOT_STICKY
    }

    private fun scheduleNetworkRecheck() {
        lifecycleScope.launch {
            lock.withLock {
                if (!runtimeReady.isCompleted) return@withLock
                _state.value = _state.value.copy(availableIps = localIps())
                val folder = activeFolderUri ?: return@withLock
                if (server == null || restartingForIpChange) {
                    return@withLock
                }

                val newIp = chooseBindIp()
                val currentIpAddress = activeIp?.hostAddress
                if (newIp == null || currentIpAddress == null || newIp.hostAddress == currentIpAddress) {
                    return@withLock
                }

                ServerLogger.i(
                    LOG_COMPONENT,
                    "Detected bind IP change $currentIpAddress -> ${newIp.hostAddress}; restarting runtime",
                )
                restartingForIpChange = true
                try {
                    startRuntime(folder, preserveStateMessage = true)
                } finally {
                    restartingForIpChange = false
                }
            }
        }
    }

    private suspend fun startRuntime(
        folderUri: Uri,
        preserveStateMessage: Boolean,
    ) {
        awaitRuntimeReady()
        ServerLogger.i(LOG_COMPONENT, "Starting runtime preserveStateMessage=$preserveStateMessage folderUri=$folderUri")
        _state.value = _state.value.copy(
            transitioning = true,
            statusText = if (preserveStateMessage) "Restarting server..." else "Starting server...",
            error = null,
        )
        stopRuntime(updateState = false)

        val ipAddress = chooseBindIp()
        if (ipAddress == null) {
            ServerLogger.e(LOG_COMPONENT, "Runtime start failed: no local network address")
            setOfflineState("No local network address available")
            updateForegroundNotification("MediaBus offline")
            return
        }

        val maxAttempts = 2
        var lastError: Throwable? = null
        repeat(maxAttempts) { attempt ->
            var startedServer: MediaBusHttpServer? = null
            var mdnsStarted = false
            try {
                val sslFactory = CertificateManager.createServerSocketFactory(applicationContext, DEFAULT_HOST_NAME)
                val httpServer = MediaBusHttpServer(
                    appContext = applicationContext,
                    bindAddress = ipAddress,
                    portNumber = SERVER_PORT,
                    sharedFolderUri = folderUri,
                    sslServerSocketFactory = sslFactory,
                    advertisedHost = DEFAULT_HOST_NAME,
                    runtime = runtime,
                )
                withContext(Dispatchers.IO) {
                    httpServer.start(SOCKET_READ_TIMEOUT_MS, false)
                }
                startedServer = httpServer
                withContext(Dispatchers.IO) {
                    mdnsAdvertiser.start(ipAddress, SERVER_PORT, hostLabel = "mediabus")
                }
                mdnsStarted = true

                val advertisedHost = mdnsAdvertiser.advertisedHostname(DEFAULT_HOST_NAME)
                server = httpServer
                activeFolderUri = folderUri
                activeIp = ipAddress

                val statusText =
                    if (preserveStateMessage) "Server restarted for network change"
                    else "Server online"

                _state.value = _state.value.copy(
                    running = true,
                    transitioning = false,
                    hostname = advertisedHost,
                    ipAddress = ipAddress.hostAddress,
                    port = SERVER_PORT,
                    statusText = statusText,
                    error = null,
                    availableIps = localIps(),
                pairedDevices = runtime.pairedDeviceSnapshot(),
                pairedDevicesReady = true,
                transferSummary = runtime.transferSummary.value,
            )
                ServerLogger.i(
                    LOG_COMPONENT,
                    "Runtime started host=$advertisedHost ip=${ipAddress.hostAddress} port=$SERVER_PORT",
                )
                updateForegroundNotification("Online at https://$advertisedHost:$SERVER_PORT")
                return
            } catch (throwable: Throwable) {
                lastError = throwable
                if (mdnsStarted) {
                    withContext(Dispatchers.IO) {
                        runCatching { mdnsAdvertiser.stop() }
                            .onFailure { stopError ->
                                ServerLogger.w(LOG_COMPONENT, "mDNS advertiser cleanup failed: ${stopError.message}")
                            }
                    }
                }
                withContext(Dispatchers.IO) {
                    runCatching { startedServer?.stop() }
                        .onFailure { stopError ->
                            ServerLogger.w(LOG_COMPONENT, "HTTP server cleanup failed: ${stopError.message}")
                        }
                }
                server = null
                activeIp = null
                val hasRetry = attempt < maxAttempts - 1
                if (hasRetry && isAddressInUse(throwable)) {
                    ServerLogger.w(
                        LOG_COMPONENT,
                        "Bind address in use on startup; retrying attempt=${attempt + 1}/$maxAttempts",
                    )
                    delay(400)
                } else {
                    ServerLogger.e(LOG_COMPONENT, "Runtime start failure: ${throwable.message}", throwable)
                    val errorMessage = if (isAddressInUse(throwable)) {
                        "Port $SERVER_PORT is already in use"
                    } else {
                        throwable.message ?: "Unable to start server"
                    }
                    setOfflineState(errorMessage)
                    updateForegroundNotification("MediaBus offline")
                    return
                }
            }
        }
        val error = lastError
        if (error != null) {
            ServerLogger.e(LOG_COMPONENT, "Runtime start failure after retries: ${error.message}", error)
            val message = if (isAddressInUse(error)) "Port $SERVER_PORT is already in use" else (error.message ?: "Unable to start server")
            setOfflineState(message)
            updateForegroundNotification("MediaBus offline")
        }
    }

    private suspend fun stopRuntime(updateState: Boolean) {
        awaitRuntimeReady()
        ServerLogger.i(LOG_COMPONENT, "Stopping runtime updateState=$updateState")
        withContext(Dispatchers.IO) {
            runCatching { server?.stop() }
                .onFailure { throwable ->
                    ServerLogger.w(LOG_COMPONENT, "HTTP server stop failed: ${throwable.message}")
                }
            runCatching { mdnsAdvertiser.stop() }
                .onFailure { throwable ->
                    ServerLogger.w(LOG_COMPONENT, "mDNS advertiser stop failed: ${throwable.message}")
                }
        }

        server = null
        activeIp = null

        if (updateState) {
            setOfflineState(null)
        }
    }

    private fun setOfflineState(errorMessage: String?) {
        if (errorMessage == null) {
            ServerLogger.i(LOG_COMPONENT, "Server offline")
        } else {
            ServerLogger.w(LOG_COMPONENT, "Server offline with reason: $errorMessage")
        }
        _state.value = _state.value.copy(
            running = false,
            transitioning = false,
            hostname = DEFAULT_HOST_NAME,
            ipAddress = null,
            port = SERVER_PORT,
            statusText = if (errorMessage == null) "Server offline" else "Server stopped",
            error = errorMessage,
            availableIps = localIps(),
            pairedDevices = if (::runtime.isInitialized) runtime.pairedDeviceSnapshot() else emptyList(),
            pairedDevicesReady = ::runtime.isInitialized,
            transferSummary = TransferSummary(),
        )
    }

    private suspend fun awaitRuntimeReady() {
        if (!runtimeReady.isCompleted) {
            runtimeReady.await()
        }
    }

    private fun chooseBindIp(): InetAddress? {
        val addresses = NetworkAddressResolver.listPrivateIpv4Addresses()
        if (addresses.isEmpty()) {
            ServerLogger.w(LOG_COMPONENT, "No private IPv4 addresses available for binding")
            return null
        }

        val selected = addresses.firstOrNull()
        ServerLogger.i(LOG_COMPONENT, "Using bind IP ${selected?.hostAddress ?: "none"}")
        return selected
    }

    private fun isAddressInUse(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            if (current is java.net.BindException) {
                return true
            }
            if (current.message?.contains("EADDRINUSE", ignoreCase = true) == true) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun localIps(): List<String> {
        return NetworkAddressResolver.listPrivateIpv4Addresses()
            .mapNotNull { it.hostAddress }
    }

    private fun startInForeground(contentText: String) {
        ServerLogger.d(LOG_COMPONENT, "Entering foreground mode: $contentText")
        val notification = buildNotification(contentText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun updateForegroundNotification(contentText: String) {
        ServerLogger.d(LOG_COMPONENT, "Updating foreground notification: $contentText")
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MediaBus Server",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows MediaBus background server status"
        }
        manager.createNotificationChannel(channel)
        ServerLogger.d(LOG_COMPONENT, "Notification channel ensured")
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("MediaBus")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun configureNanoHttpdLogging() {
        runCatching {
            val logger = Logger.getLogger(NanoHTTPD::class.java.name)
            logger.filter = java.util.logging.Filter { record ->
                val message = record.message.orEmpty()
                val isExpectedClientAbort = message.contains(
                    "Could not send response to the client",
                    ignoreCase = true,
                )
                !isExpectedClientAbort
            }
            logger.level = Level.INFO
        }.onFailure { throwable ->
            ServerLogger.w(LOG_COMPONENT, "Failed configuring NanoHTTPD logger: ${throwable.message}")
        }
    }

    companion object {
        private const val ACTION_START = "com.tudorc.mediabus.action.START_HOST_SERVER"
        private const val ACTION_STOP = "com.tudorc.mediabus.action.STOP_HOST_SERVER"
        private const val EXTRA_FOLDER_URI = "extra_folder_uri"

        private const val CHANNEL_ID = "mediabus_server_channel"
        private const val NOTIFICATION_ID = 4041

        const val SERVER_PORT = 8443
        private const val SOCKET_READ_TIMEOUT_MS = 60_000
        const val DEFAULT_HOST_NAME = "mediabus.local"
        private const val LOG_COMPONENT = "HostService"

        private var runtimeRef: HostRuntimeController? = null

        private val _state = MutableStateFlow(HostServerState())
        val state: StateFlow<HostServerState> = _state.asStateFlow()

        fun start(
            context: Context,
            folderUri: String,
        ) {
            ServerLogger.i(LOG_COMPONENT, "Static start request folderUri=$folderUri")
            val intent = Intent(context, MediaBusHostService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_FOLDER_URI, folderUri)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            ServerLogger.i(LOG_COMPONENT, "Static stop request")
            val intent = Intent(context, MediaBusHostService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun approvePairByCode(code: String): HostActionResult {
            val runtime = runtimeRef ?: return HostActionResult.ServiceOffline
            val result = when (runtime.approveByCode(code)) {
                is HostRuntimeController.PairApproval.Success -> HostActionResult.Success
                HostRuntimeController.PairApproval.Expired -> HostActionResult.Expired
                HostRuntimeController.PairApproval.NotFound -> HostActionResult.NotFound
            }
            ServerLogger.i(LOG_COMPONENT, "approvePairByCode result=$result")
            return result
        }

        fun approvePairByToken(token: String): HostActionResult {
            val runtime = runtimeRef ?: return HostActionResult.ServiceOffline
            val result = when (runtime.approveByToken(token)) {
                is HostRuntimeController.PairApproval.Success -> HostActionResult.Success
                HostRuntimeController.PairApproval.Expired -> HostActionResult.Expired
                HostRuntimeController.PairApproval.NotFound -> HostActionResult.NotFound
            }
            ServerLogger.i(LOG_COMPONENT, "approvePairByToken result=$result")
            return result
        }

        fun revokeDevice(deviceId: String): Boolean {
            val runtime = runtimeRef ?: return false
            val revoked = runtime.revokeDevice(deviceId)
            ServerLogger.i(LOG_COMPONENT, "revokeDevice id=$deviceId revoked=$revoked")
            return revoked
        }
    }
}

enum class HostActionResult {
    Success,
    NotFound,
    Expired,
    ServiceOffline,
}
