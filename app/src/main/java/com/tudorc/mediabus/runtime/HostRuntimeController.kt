package com.tudorc.mediabus.runtime

import com.tudorc.mediabus.data.HostStoreRepository
import com.tudorc.mediabus.model.DevicePresence
import com.tudorc.mediabus.model.PairedDevice
import com.tudorc.mediabus.model.PairedDeviceStatus
import com.tudorc.mediabus.model.TransferDirection
import com.tudorc.mediabus.model.TransferSummary
import com.tudorc.mediabus.util.ServerLogger
import com.tudorc.mediabus.util.UserAgentParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.max

class HostRuntimeController(
    private val repository: HostStoreRepository,
    private val tokenSigner: com.tudorc.mediabus.util.TokenSigner,
    private val scope: CoroutineScope,
) {
    private val lock = Any()

    private val pairedDevices = linkedMapOf<String, PairedDevice>()
    private val deviceRuntime = hashMapOf<String, DeviceRuntime>()

    private val pendingByToken = hashMapOf<String, PairChallenge>()
    private val latestPendingByAnonId = hashMapOf<String, String>()

    private val sessionsById = hashMapOf<String, SessionInfo>()
    private val transferById = linkedMapOf<String, TransferInfo>()
    private val revokedDeviceNotices = hashMapOf<String, Long>()
    private var overallProcessTotalBytes = 0L
    private var overallProcessTransferredBytes = 0L
    private var currentUploadBatchId: String? = null
    private var currentUploadBatchTotalFiles = 0
    private var currentUploadBatchCompletedFiles = 0
    private var currentUploadBatchActiveFiles = 0
    private var currentUploadBatchTotalBytes = 0L
    private var currentDownloadBatchId: String? = null
    private var currentDownloadBatchTotalFiles = 0
    private var currentDownloadBatchCompletedFiles = 0
    private var currentDownloadBatchActiveFiles = 0
    private var currentDownloadBatchTotalBytes = 0L

    @Volatile
    private var showHiddenFiles = false
    @Volatile
    private var allowUpload = true
    @Volatile
    private var allowDownload = true
    @Volatile
    private var allowDelete = true

    private val random = SecureRandom()
    private var lastLoggedPairedCount: Int = -1
    private var lastLoggedSettingsSignature: String? = null

    private val _pairedStatuses = MutableStateFlow<List<PairedDeviceStatus>>(emptyList())
    val pairedStatuses: StateFlow<List<PairedDeviceStatus>> = _pairedStatuses.asStateFlow()

    private val _transferSummary = MutableStateFlow(TransferSummary())
    val transferSummary: StateFlow<TransferSummary> = _transferSummary.asStateFlow()

    fun start() {
        ServerLogger.i(LOG_COMPONENT, "Runtime controller started")
        scope.launch {
            repository.pairedDevicesFlow.collect { devices ->
                synchronized(lock) {
                    pairedDevices.clear()
                    devices.forEach { device ->
                        pairedDevices[device.deviceId] = device
                        deviceRuntime.putIfAbsent(device.deviceId, DeviceRuntime())
                    }
                    val unknownIds = deviceRuntime.keys - pairedDevices.keys
                    unknownIds.forEach { deviceRuntime.remove(it) }
                    cleanupExpiredLocked()
                    publishLocked()
                    if (lastLoggedPairedCount != pairedDevices.size) {
                        lastLoggedPairedCount = pairedDevices.size
                        ServerLogger.i(LOG_COMPONENT, "Paired devices loaded count=${pairedDevices.size}")
                    }
                }
            }
        }
        scope.launch {
            repository.settingsFlow.collect { settings ->
                showHiddenFiles = settings.showHiddenFiles
                allowUpload = settings.allowUpload
                allowDownload = settings.allowDownload
                allowDelete = settings.allowDelete
                val signature = "${settings.showHiddenFiles}:${settings.allowUpload}:${settings.allowDownload}:${settings.allowDelete}"
                if (lastLoggedSettingsSignature != signature) {
                    lastLoggedSettingsSignature = signature
                    ServerLogger.i(
                        LOG_COMPONENT,
                        "Settings updated showHiddenFiles=${settings.showHiddenFiles} allowUpload=${settings.allowUpload} allowDownload=${settings.allowDownload} allowDelete=${settings.allowDelete}",
                    )
                }
            }
        }
        scope.launch {
            while (true) {
                delay(PRESENCE_TICK_MS)
                synchronized(lock) {
                    cleanupExpiredLocked()
                    publishLocked()
                }
            }
        }
    }

    fun showHiddenFiles(): Boolean = showHiddenFiles
    fun uploadEnabled(): Boolean = allowUpload
    fun downloadEnabled(): Boolean = allowDownload
    fun deleteEnabled(): Boolean = allowDelete

    fun ensurePendingChallenge(
        anonId: String,
        userAgent: String,
        ipAddress: String,
    ): PairChallenge {
        synchronized(lock) {
            cleanupExpiredLocked()
            val existingToken = latestPendingByAnonId[anonId]
            val existing = existingToken?.let { pendingByToken[it] }
            if (existing != null && !existing.isExpired(now())) {
                ServerLogger.d(LOG_COMPONENT, "Reusing pending challenge token=${existing.token.take(8)}...")
                return existing
            }

            val challenge = PairChallenge(
                token = randomUrlSafeToken(24),
                code = randomPairCode(),
                createdAtEpochMs = now(),
                expiresAtEpochMs = now() + PAIR_CODE_TTL_MS,
                userAgent = userAgent,
                ipAddress = ipAddress,
            )
            pendingByToken[challenge.token] = challenge
            latestPendingByAnonId[anonId] = challenge.token
            ServerLogger.i(
                LOG_COMPONENT,
                "Created pending challenge code=${challenge.code} token=${challenge.token.take(8)}... ip=$ipAddress",
            )
            return challenge
        }
    }

    fun pairingStatus(token: String): PairingStatus {
        synchronized(lock) {
            cleanupExpiredLocked()
            val challenge = pendingByToken[token] ?: return PairingStatus.NotFound
            if (challenge.approvedDeviceId == null) {
                return PairingStatus.Pending(challenge.expiresAtEpochMs)
            }
            if (challenge.consumedAtEpochMs > 0L) {
                return PairingStatus.NotFound
            }
            challenge.consumedAtEpochMs = now()
            pendingByToken.remove(token)
            latestPendingByAnonId.entries.removeAll { it.value == token }
            ServerLogger.i(LOG_COMPONENT, "Pairing token consumed token=${token.take(8)}... device=${challenge.approvedDeviceId}")
            return PairingStatus.Approved(challenge.approvedDeviceId)
        }
    }

    fun approveByToken(token: String): PairApproval {
        synchronized(lock) {
            cleanupExpiredLocked()
            val challenge = pendingByToken[token] ?: return PairApproval.NotFound
            if (challenge.isExpired(now())) return PairApproval.Expired
            val device = provisionDeviceLocked(challenge)
            challenge.approvedDeviceId = device.deviceId
            publishLocked()
            ServerLogger.i(LOG_COMPONENT, "Approved challenge by token deviceId=${device.deviceId}")
            return PairApproval.Success(device)
        }
    }

    fun approveByCode(code: String): PairApproval {
        synchronized(lock) {
            cleanupExpiredLocked()
            val challenge = pendingByToken.values.firstOrNull { value ->
                value.code == code && !value.isExpired(now())
            } ?: return PairApproval.NotFound

            val device = provisionDeviceLocked(challenge)
            challenge.approvedDeviceId = device.deviceId
            publishLocked()
            ServerLogger.i(LOG_COMPONENT, "Approved challenge by code deviceId=${device.deviceId}")
            return PairApproval.Success(device)
        }
    }

    fun revokeDevice(deviceId: String): Boolean {
        synchronized(lock) {
            val removed = pairedDevices.remove(deviceId) ?: return false
            revokedDeviceNotices[deviceId] = now()
            val runtimeState = deviceRuntime[deviceId]
            if (runtimeState != null) {
                runtimeState.cancelGeneration += 1
            }
            removeSessionsForDeviceLocked(deviceId)
            latestPendingByAnonId.entries.removeAll { entry ->
                pendingByToken[entry.value]?.approvedDeviceId == removed.deviceId
            }
            publishLocked()
            persistDevicesLocked()
            ServerLogger.i(LOG_COMPONENT, "Revoked deviceId=$deviceId")
            return true
        }
    }

    fun consumeRevocationNotice(sessionCookie: String?): String? {
        val payload = parseSessionCookie(sessionCookie) ?: return null
        val deviceId = payload.optString("deviceId")
        if (deviceId.isBlank()) return null
        synchronized(lock) {
            cleanupExpiredLocked()
            val revokedAt = revokedDeviceNotices[deviceId] ?: return null
            if (now() - revokedAt > REVOKE_NOTICE_TTL_MS) {
                revokedDeviceNotices.remove(deviceId)
                return null
            }
            revokedDeviceNotices.remove(deviceId)
            return "Access revoked by host"
        }
    }

    fun createSessionForPairedDevice(deviceId: String, ipAddress: String): String? {
        synchronized(lock) {
            cleanupExpiredLocked()
            if (!pairedDevices.containsKey(deviceId)) {
                return null
            }
            ServerLogger.i(LOG_COMPONENT, "Creating session for paired device deviceId=$deviceId ip=$ipAddress")
            return createOrReplaceSessionLocked(deviceId, ipAddress)
        }
    }

    fun authenticateSession(
        sessionCookie: String?,
        ipAddress: String,
        touch: Boolean = true,
    ): AuthResult {
        val payload = parseSessionCookie(sessionCookie) ?: return AuthResult.Invalid
        val sessionId = payload.optString("sid")
        val deviceId = payload.optString("deviceId")
        if (sessionId.isBlank() || deviceId.isBlank()) return AuthResult.Invalid

        synchronized(lock) {
            cleanupExpiredLocked()
            val session = sessionsById[sessionId] ?: return AuthResult.Invalid
            if (session.deviceId != deviceId) {
                sessionsById.remove(sessionId)
                ServerLogger.w(LOG_COMPONENT, "Session mismatch; removed sid=$sessionId expected=${session.deviceId} got=$deviceId")
                return AuthResult.Invalid
            }
            val device = pairedDevices[deviceId] ?: return AuthResult.Invalid
            if (touch) {
                session.lastSeenAtEpochMs = now()
                updateDeviceLastSeenLocked(deviceId, ipAddress)
                publishLocked()
            }
            return AuthResult.Valid(device)
        }
    }

    fun disconnectSession(sessionCookie: String?) {
        val payload = parseSessionCookie(sessionCookie) ?: return
        val sessionId = payload.optString("sid")
        synchronized(lock) {
            val removed = sessionsById.remove(sessionId) ?: return
            val runtime = deviceRuntime[removed.deviceId]
            if (runtime != null) {
                runtime.sessionCount = max(0, runtime.sessionCount - 1)
            }
            publishLocked()
            ServerLogger.i(LOG_COMPONENT, "Disconnected session sid=$sessionId deviceId=${removed.deviceId}")
        }
    }

    fun heartbeat(deviceId: String, ipAddress: String) {
        synchronized(lock) {
            updateDeviceLastSeenLocked(deviceId, ipAddress)
            publishLocked()
        }
    }

    fun beginTransfer(
        deviceId: String,
        direction: TransferDirection,
        totalBytes: Long,
        batchId: String? = null,
        batchTotalFiles: Int = 0,
        batchTotalBytes: Long = 0L,
        batchCompletedFiles: Int = 0,
    ): TransferTicket? {
        val runtime: DeviceRuntime
        val transfer: TransferInfo
        val normalizedBatchId = batchId?.takeIf { it.isNotBlank() }
        synchronized(lock) {
            if (
                transferById.isEmpty() &&
                (
                    normalizedBatchId == null ||
                        (normalizedBatchId != currentUploadBatchId && normalizedBatchId != currentDownloadBatchId)
                    )
            ) {
                overallProcessTotalBytes = 0L
                overallProcessTransferredBytes = 0L
            }
            if (!pairedDevices.containsKey(deviceId)) {
                return null
            }
            if (direction == TransferDirection.Uploading && normalizedBatchId != null && batchTotalFiles > 0) {
                if (currentUploadBatchId != normalizedBatchId) {
                    currentUploadBatchId = normalizedBatchId
                    currentUploadBatchTotalFiles = batchTotalFiles
                    currentUploadBatchCompletedFiles = 0
                    currentUploadBatchActiveFiles = 0
                    currentUploadBatchTotalBytes = max(0L, batchTotalBytes)
                    overallProcessTotalBytes = max(0L, batchTotalBytes)
                    overallProcessTransferredBytes = 0L
                } else {
                    currentUploadBatchTotalFiles = max(currentUploadBatchTotalFiles, batchTotalFiles)
                    if (batchTotalBytes > 0L) {
                        currentUploadBatchTotalBytes = max(currentUploadBatchTotalBytes, batchTotalBytes)
                        overallProcessTotalBytes = max(overallProcessTotalBytes, batchTotalBytes)
                    }
                }
            } else if (direction == TransferDirection.Uploading && normalizedBatchId == null && transferById.isEmpty()) {
                currentUploadBatchId = null
                currentUploadBatchTotalFiles = 0
                currentUploadBatchCompletedFiles = 0
                currentUploadBatchActiveFiles = 0
                currentUploadBatchTotalBytes = 0L
            } else if (direction == TransferDirection.Downloading && normalizedBatchId != null && batchTotalFiles > 0) {
                if (currentDownloadBatchId != normalizedBatchId) {
                    currentDownloadBatchId = normalizedBatchId
                    currentDownloadBatchTotalFiles = batchTotalFiles
                    currentDownloadBatchCompletedFiles = batchCompletedFiles.coerceIn(0, max(1, batchTotalFiles))
                    currentDownloadBatchActiveFiles = 0
                    currentDownloadBatchTotalBytes = max(0L, batchTotalBytes)
                    overallProcessTotalBytes = max(0L, batchTotalBytes)
                    overallProcessTransferredBytes = 0L
                } else {
                    currentDownloadBatchTotalFiles = max(currentDownloadBatchTotalFiles, batchTotalFiles)
                    currentDownloadBatchCompletedFiles = max(
                        currentDownloadBatchCompletedFiles,
                        batchCompletedFiles.coerceIn(0, max(1, currentDownloadBatchTotalFiles)),
                    )
                    if (batchTotalBytes > 0L) {
                        currentDownloadBatchTotalBytes = max(currentDownloadBatchTotalBytes, batchTotalBytes)
                        overallProcessTotalBytes = max(overallProcessTotalBytes, batchTotalBytes)
                    }
                }
            } else if (direction == TransferDirection.Downloading && normalizedBatchId == null && transferById.isEmpty()) {
                currentDownloadBatchId = null
                currentDownloadBatchTotalFiles = 0
                currentDownloadBatchCompletedFiles = 0
                currentDownloadBatchActiveFiles = 0
                currentDownloadBatchTotalBytes = 0L
            }
            runtime = deviceRuntime.getOrPut(deviceId) { DeviceRuntime() }
            runtime.queuedTransfers++
            transfer = TransferInfo(
                id = UUID.randomUUID().toString(),
                deviceId = deviceId,
                direction = direction,
                totalBytes = totalBytes,
                transferredBytes = 0L,
                active = false,
                generation = runtime.cancelGeneration,
                batchId = normalizedBatchId,
            )
            val usingBatchTotalBytes = currentUploadBatchId != null &&
                normalizedBatchId == currentUploadBatchId &&
                currentUploadBatchTotalBytes > 0L
            val usingDownloadBatchTotalBytes = currentDownloadBatchId != null &&
                normalizedBatchId == currentDownloadBatchId &&
                currentDownloadBatchTotalBytes > 0L
            if (totalBytes > 0L && !usingBatchTotalBytes && !usingDownloadBatchTotalBytes) {
                overallProcessTotalBytes += totalBytes
            }
            transferById[transfer.id] = transfer
            publishLocked()
            ServerLogger.i(
                LOG_COMPONENT,
                "Queued transfer id=${transfer.id} deviceId=$deviceId direction=$direction totalBytes=$totalBytes",
            )
        }

        runtime.transferLock.lock()
        synchronized(lock) {
            val currentRuntime = deviceRuntime[deviceId]
            val currentTransfer = transferById[transfer.id]
            if (currentRuntime == null || currentTransfer == null) {
                runtime.transferLock.unlock()
                return null
            }

            currentRuntime.queuedTransfers = max(0, currentRuntime.queuedTransfers - 1)
            if (!pairedDevices.containsKey(deviceId) || currentRuntime.cancelGeneration != currentTransfer.generation) {
                transferById.remove(transfer.id)
                publishLocked()
                runtime.transferLock.unlock()
                return null
            }
            currentRuntime.activeTransfers++
            currentTransfer.active = true
            if (
                currentTransfer.direction == TransferDirection.Uploading &&
                currentTransfer.batchId != null &&
                currentTransfer.batchId == currentUploadBatchId
            ) {
                currentUploadBatchActiveFiles++
            } else if (
                currentTransfer.direction == TransferDirection.Downloading &&
                currentTransfer.batchId != null &&
                currentTransfer.batchId == currentDownloadBatchId
            ) {
                currentDownloadBatchActiveFiles++
            }
            publishLocked()
            ServerLogger.i(
                LOG_COMPONENT,
                "Started transfer id=${currentTransfer.id} deviceId=$deviceId direction=${currentTransfer.direction}",
            )
        }

        return TransferTicket(
            onProgress = { delta ->
                synchronized(lock) {
                    val current = transferById[transfer.id] ?: return@TransferTicket
                    if (delta > 0) {
                        current.transferredBytes += delta
                        overallProcessTransferredBytes += delta
                        publishLocked()
                    }
                }
            },
            isCancelled = {
                synchronized(lock) {
                    val currentRuntime = deviceRuntime[deviceId] ?: return@synchronized true
                    !pairedDevices.containsKey(deviceId) || currentRuntime.cancelGeneration != transfer.generation
                }
            },
            onFinish = {
                synchronized(lock) {
                    val currentRuntime = deviceRuntime[deviceId]
                    val currentTransfer = transferById.remove(transfer.id)
                    if (currentRuntime != null && currentTransfer != null && currentTransfer.active) {
                        currentRuntime.activeTransfers = max(0, currentRuntime.activeTransfers - 1)
                    }
                    if (
                        currentTransfer != null &&
                        currentTransfer.direction == TransferDirection.Uploading &&
                        currentTransfer.batchId != null &&
                        currentTransfer.batchId == currentUploadBatchId &&
                        currentTransfer.active
                    ) {
                        currentUploadBatchActiveFiles = max(0, currentUploadBatchActiveFiles - 1)
                        currentUploadBatchCompletedFiles = (currentUploadBatchCompletedFiles + 1)
                            .coerceAtMost(max(1, currentUploadBatchTotalFiles))
                    } else if (
                        currentTransfer != null &&
                        currentTransfer.direction == TransferDirection.Downloading &&
                        currentTransfer.batchId != null &&
                        currentTransfer.batchId == currentDownloadBatchId &&
                        currentTransfer.active
                    ) {
                        currentDownloadBatchActiveFiles = max(0, currentDownloadBatchActiveFiles - 1)
                        currentDownloadBatchCompletedFiles = (currentDownloadBatchCompletedFiles + 1)
                            .coerceAtMost(max(1, currentDownloadBatchTotalFiles))
                    }
                    publishLocked()
                    if (transferById.isEmpty()) {
                        val uploadBatchDone = currentUploadBatchId != null &&
                            currentUploadBatchTotalFiles > 0 &&
                            currentUploadBatchCompletedFiles >= currentUploadBatchTotalFiles
                        val downloadBatchDone = currentDownloadBatchId != null &&
                            currentDownloadBatchTotalFiles > 0 &&
                            currentDownloadBatchCompletedFiles >= currentDownloadBatchTotalFiles
                        if ((uploadBatchDone || currentUploadBatchId == null) && (downloadBatchDone || currentDownloadBatchId == null)) {
                            overallProcessTotalBytes = 0L
                            overallProcessTransferredBytes = 0L
                            currentUploadBatchId = null
                            currentUploadBatchTotalFiles = 0
                            currentUploadBatchCompletedFiles = 0
                            currentUploadBatchActiveFiles = 0
                            currentUploadBatchTotalBytes = 0L
                            currentDownloadBatchId = null
                            currentDownloadBatchTotalFiles = 0
                            currentDownloadBatchCompletedFiles = 0
                            currentDownloadBatchActiveFiles = 0
                            currentDownloadBatchTotalBytes = 0L
                        }
                    }
                    if (currentTransfer != null) {
                        ServerLogger.i(
                            LOG_COMPONENT,
                            "Finished transfer id=${currentTransfer.id} deviceId=$deviceId bytes=${currentTransfer.transferredBytes}/${currentTransfer.totalBytes}",
                        )
                    }
                }
                runtime.transferLock.unlock()
            },
        )
    }

    fun pairedDeviceSnapshot(): List<PairedDeviceStatus> = pairedStatuses.value

    private fun removeSessionsForDeviceLocked(deviceId: String) {
        val sessionIds = sessionsById.values.filter { it.deviceId == deviceId }.map { it.sessionId }
        sessionIds.forEach { sessionsById.remove(it) }
        val runtime = deviceRuntime[deviceId]
        if (runtime != null) {
            runtime.sessionCount = 0
            runtime.queuedTransfers = 0
            runtime.activeTransfers = 0
        }
        ServerLogger.i(LOG_COMPONENT, "Cleared sessions/transfers for deviceId=$deviceId sessions=${sessionIds.size}")
    }

    private fun createOrReplaceSessionLocked(deviceId: String, ipAddress: String): String? {
        val now = now()
        val deviceIdsWithSession = sessionsById.values.map { it.deviceId }.toSet()
        if (!deviceIdsWithSession.contains(deviceId) && deviceIdsWithSession.size >= MAX_CONCURRENT_CLIENTS) {
            ServerLogger.w(LOG_COMPONENT, "Session denied; max clients reached deviceId=$deviceId")
            return null
        }

        val existing = sessionsById.values.firstOrNull { it.deviceId == deviceId }
        if (existing != null) {
            sessionsById.remove(existing.sessionId)
        }

        val sessionId = randomUrlSafeToken(24)
        sessionsById[sessionId] = SessionInfo(
            sessionId = sessionId,
            deviceId = deviceId,
            expiresAtEpochMs = now + SESSION_TTL_MS,
            lastSeenAtEpochMs = now,
        )

        val runtime = deviceRuntime.getOrPut(deviceId) { DeviceRuntime() }
        runtime.sessionCount = 1
        updateDeviceLastSeenLocked(deviceId, ipAddress)
        publishLocked()

        val payload = JSONObject()
            .put("kind", "session")
            .put("sid", sessionId)
            .put("deviceId", deviceId)
            .put("exp", now + SESSION_TTL_MS)
        ServerLogger.i(LOG_COMPONENT, "Session issued sid=$sessionId deviceId=$deviceId ip=$ipAddress")
        return tokenSigner.sign(payload)
    }

    private fun parseSessionCookie(cookie: String?): JSONObject? {
        val payload = cookie?.let { tokenSigner.verify(it) } ?: return null
        val exp = payload.optLong("exp")
        if (exp <= now()) return null
        if (payload.optString("kind") != "session") return null
        return payload
    }

    private fun provisionDeviceLocked(challenge: PairChallenge): PairedDevice {
        val now = now()
        val device = PairedDevice(
            deviceId = UUID.randomUUID().toString(),
            displayName = UserAgentParser.labelFromUserAgent(challenge.userAgent),
            userAgent = challenge.userAgent,
            lastKnownIp = challenge.ipAddress,
            createdAtEpochMs = now,
            lastConnectedEpochMs = now,
        )

        pairedDevices[device.deviceId] = device
        deviceRuntime.putIfAbsent(device.deviceId, DeviceRuntime())
        ServerLogger.i(
            LOG_COMPONENT,
            "Provisioned paired device id=${device.deviceId} name=${device.displayName} ip=${device.lastKnownIp}",
        )

        if (pairedDevices.size > MAX_PAIRED_DEVICES) {
            val oldest = pairedDevices.values.minByOrNull { it.createdAtEpochMs }
            if (oldest != null) {
                pairedDevices.remove(oldest.deviceId)
                deviceRuntime.remove(oldest.deviceId)
                removeSessionsForDeviceLocked(oldest.deviceId)
                ServerLogger.w(LOG_COMPONENT, "Evicted oldest paired device id=${oldest.deviceId}")
            }
        }

        persistDevicesLocked()
        return device
    }

    private fun updateDeviceLastSeenLocked(deviceId: String, ipAddress: String) {
        val current = pairedDevices[deviceId] ?: return
        val updated = current.copy(
            lastKnownIp = ipAddress.ifBlank { current.lastKnownIp },
            lastConnectedEpochMs = now(),
        )
        pairedDevices[deviceId] = updated
        val runtime = deviceRuntime.getOrPut(deviceId) { DeviceRuntime() }
        runtime.lastSeenAtEpochMs = now()
        persistDevicesLocked()
    }

    private fun persistDevicesLocked() {
        val snapshot = pairedDevices.values.sortedByDescending { it.lastConnectedEpochMs }
        scope.launch {
            repository.savePairedDevices(snapshot)
        }
    }

    private fun cleanupExpiredLocked() {
        val now = now()
        val expiredChallenges = pendingByToken.values.filter { it.isExpired(now) }.map { it.token }
        expiredChallenges.forEach { token ->
            pendingByToken.remove(token)
            latestPendingByAnonId.entries.removeAll { it.value == token }
        }
        if (expiredChallenges.isNotEmpty()) {
            ServerLogger.d(LOG_COMPONENT, "Expired challenges cleaned=${expiredChallenges.size}")
        }

        val expiredSessions = sessionsById.values.filter { it.expiresAtEpochMs <= now }.toList()
        expiredSessions.forEach { session ->
            sessionsById.remove(session.sessionId)
            val runtime = deviceRuntime[session.deviceId]
            if (runtime != null) {
                runtime.sessionCount = max(0, runtime.sessionCount - 1)
            }
        }
        if (expiredSessions.isNotEmpty()) {
            ServerLogger.d(LOG_COMPONENT, "Expired sessions cleaned=${expiredSessions.size}")
        }

        val expiredRevocationNotices = revokedDeviceNotices
            .filterValues { now - it > REVOKE_NOTICE_TTL_MS }
            .keys
            .toList()
        expiredRevocationNotices.forEach { revokedDeviceNotices.remove(it) }
    }

    private fun publishLocked() {
        val now = now()
        val statuses = pairedDevices.values
            .sortedByDescending { it.lastConnectedEpochMs }
            .map { device ->
                val runtime = deviceRuntime[device.deviceId]
                val presence = when {
                    runtime == null -> DevicePresence.Disconnected
                    runtime.activeTransfers > 0 || runtime.queuedTransfers > 0 -> DevicePresence.Transferring
                    runtime.sessionCount > 0 && now - runtime.lastSeenAtEpochMs <= CONNECTED_WINDOW_MS -> DevicePresence.Connected
                    else -> DevicePresence.Disconnected
                }
                PairedDeviceStatus(
                    device = device,
                    presence = presence,
                    queuedTransfers = runtime?.queuedTransfers ?: 0,
                    activeTransfers = runtime?.activeTransfers ?: 0,
                )
            }
        _pairedStatuses.value = statuses

        val queued = transferById.values.count { !it.active }
        val active = transferById.values.count { it.active }
        val hasUpload = transferById.values.any { it.direction == TransferDirection.Uploading }
        val hasDownload = transferById.values.any { it.direction == TransferDirection.Downloading }
        val direction = when {
            hasUpload && hasDownload -> TransferDirection.Mixed
            hasUpload -> TransferDirection.Uploading
            hasDownload -> TransferDirection.Downloading
            else -> TransferDirection.None
        }
        val totalBytes = transferById.values.sumOf { value -> max(0L, value.totalBytes) }
        val activeBytes = transferById.values
            .filter { it.active }
            .sumOf { value -> max(0L, value.transferredBytes) }
        val firstActive = transferById.values.firstOrNull { it.active }
        val currentFileTransferredBytes = max(0L, firstActive?.transferredBytes ?: 0L)
        val currentFileTotalBytes = max(0L, firstActive?.totalBytes ?: 0L)
        val overallTotalBytes = if (overallProcessTotalBytes > 0L) overallProcessTotalBytes else totalBytes
        val overallTransferredBytes = if (overallProcessTotalBytes > 0L) {
            overallProcessTransferredBytes.coerceAtMost(overallProcessTotalBytes)
        } else {
            activeBytes
        }
        var summaryActiveFiles = active
        var summaryTotalFiles = active + queued
        if (direction == TransferDirection.Uploading && currentUploadBatchId != null && currentUploadBatchTotalFiles > 0) {
            val hasActiveUploadBatch = transferById.values.any { transfer ->
                transfer.direction == TransferDirection.Uploading && transfer.batchId == currentUploadBatchId
            }
            if (hasActiveUploadBatch) {
                summaryActiveFiles = (currentUploadBatchCompletedFiles + currentUploadBatchActiveFiles)
                    .coerceIn(0, currentUploadBatchTotalFiles)
                summaryTotalFiles = currentUploadBatchTotalFiles
            }
        } else if (direction == TransferDirection.Downloading && currentDownloadBatchId != null && currentDownloadBatchTotalFiles > 0) {
            val hasActiveDownloadBatch = transferById.values.any { transfer ->
                transfer.direction == TransferDirection.Downloading && transfer.batchId == currentDownloadBatchId
            }
            if (hasActiveDownloadBatch) {
                summaryActiveFiles = (currentDownloadBatchCompletedFiles + currentDownloadBatchActiveFiles)
                    .coerceIn(0, currentDownloadBatchTotalFiles)
                summaryTotalFiles = currentDownloadBatchTotalFiles
            }
        }
        _transferSummary.value = TransferSummary(
            inProgress = transferById.isNotEmpty(),
            direction = direction,
            activeFiles = summaryActiveFiles,
            totalFiles = summaryTotalFiles,
            activeBytes = activeBytes,
            totalBytes = totalBytes,
            overallTransferredBytes = overallTransferredBytes,
            overallTotalBytes = overallTotalBytes,
            currentFileTransferredBytes = currentFileTransferredBytes,
            currentFileTotalBytes = currentFileTotalBytes,
        )
    }

    private fun randomPairCode(): String {
        val value = random.nextInt(1_000_000)
        return value.toString().padStart(6, '0')
    }

    private fun randomUrlSafeToken(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun now(): Long = System.currentTimeMillis()

    data class PairChallenge(
        val token: String,
        val code: String,
        val createdAtEpochMs: Long,
        val expiresAtEpochMs: Long,
        val userAgent: String,
        val ipAddress: String,
        var approvedDeviceId: String? = null,
        var consumedAtEpochMs: Long = 0L,
    ) {
        fun isExpired(now: Long): Boolean = now >= expiresAtEpochMs
    }

    sealed interface PairingStatus {
        data class Pending(val expiresAtEpochMs: Long) : PairingStatus

        data class Approved(val deviceId: String?) : PairingStatus

        data object NotFound : PairingStatus
    }

    sealed interface PairApproval {
        data class Success(val device: PairedDevice) : PairApproval

        data object NotFound : PairApproval

        data object Expired : PairApproval
    }

    sealed interface AuthResult {
        data class Valid(val device: PairedDevice) : AuthResult

        data object Invalid : AuthResult
    }

    class TransferTicket(
        private val onProgress: (Long) -> Unit,
        private val isCancelled: () -> Boolean,
        private val onFinish: () -> Unit,
    ) : AutoCloseable {
        private var closed = false

        fun addProgress(delta: Long) {
            if (!closed) {
                onProgress(delta)
            }
        }

        fun cancelled(): Boolean = isCancelled()

        override fun close() {
            if (!closed) {
                closed = true
                onFinish()
            }
        }
    }

    private data class SessionInfo(
        val sessionId: String,
        val deviceId: String,
        val expiresAtEpochMs: Long,
        var lastSeenAtEpochMs: Long,
    )

    private data class TransferInfo(
        val id: String,
        val deviceId: String,
        val direction: TransferDirection,
        val totalBytes: Long,
        var transferredBytes: Long,
        var active: Boolean,
        val generation: Int,
        val batchId: String? = null,
    )

    private class DeviceRuntime {
        val transferLock = ReentrantLock(true)
        var sessionCount: Int = 0
        var queuedTransfers: Int = 0
        var activeTransfers: Int = 0
        var lastSeenAtEpochMs: Long = 0L
        var cancelGeneration: Int = 0
    }

    companion object {
        private const val PAIR_CODE_TTL_MS = 2 * 60 * 1000L
        private const val SESSION_TTL_MS = 12 * 60 * 60 * 1000L
        private const val CONNECTED_WINDOW_MS = 12_000L
        private const val PRESENCE_TICK_MS = 1_500L
        private const val REVOKE_NOTICE_TTL_MS = 60_000L
        private const val MAX_PAIRED_DEVICES = 20
        private const val MAX_CONCURRENT_CLIENTS = 5
        private const val LOG_COMPONENT = "Runtime"
    }
}
