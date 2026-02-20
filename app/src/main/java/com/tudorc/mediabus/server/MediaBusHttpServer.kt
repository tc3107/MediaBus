package com.tudorc.mediabus.server

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.tudorc.mediabus.model.TransferDirection
import com.tudorc.mediabus.runtime.HostRuntimeController
import com.tudorc.mediabus.util.Base64Url
import com.tudorc.mediabus.util.ServerLogger
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.net.ssl.SSLServerSocketFactory

class MediaBusHttpServer(
    private val appContext: Context,
    bindAddress: InetAddress,
    private val portNumber: Int,
    private val sharedFolderUri: Uri,
    sslServerSocketFactory: SSLServerSocketFactory,
    private val advertisedHost: String,
    private val runtime: HostRuntimeController,
) : NanoHTTPD(bindAddress.hostAddress, portNumber) {

    private val zipExecutor = Executors.newCachedThreadPool()

    init {
        makeSecure(sslServerSocketFactory, null)
        ServerLogger.i(
            LOG_COMPONENT,
            "HTTP server initialized bind=${bindAddress.hostAddress ?: "unknown"}:$portNumber host=$advertisedHost",
        )
    }

    override fun stop() {
        ServerLogger.i(LOG_COMPONENT, "HTTP server stopping")
        super.stop()
        zipExecutor.shutdownNow()
    }

    override fun serve(session: IHTTPSession): Response {
        if (shouldLogRequest(session)) {
            ServerLogger.i(
                LOG_COMPONENT,
                "Request ${session.method} ${session.uri} ip=${session.remoteIpAddress ?: "unknown"}",
            )
        }
        return runCatching {
            when {
                session.method == Method.GET && session.uri == "/" -> {
                    htmlResponse(clientShellHtml())
                }

                session.method == Method.GET && (
                    session.uri == "/index.html" ||
                        session.uri.startsWith("/assets/") ||
                        session.uri.startsWith("/icons/") ||
                        session.uri.startsWith("/ui-icons/") ||
                        session.uri == "/manifest.webmanifest" ||
                        session.uri == "/sw.js"
                    ) -> {
                    serveWebAsset(session.uri)
                        ?: newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
                }

                session.method == Method.GET && session.uri == "/health" -> {
                    jsonResponse(
                        JSONObject()
                            .put("status", "ok")
                            .put("host", advertisedHost)
                            .put("port", portNumber),
                    )
                }

                session.method == Method.GET && session.uri == "/api/bootstrap" -> {
                    handleBootstrap(session)
                }

                session.method == Method.GET && session.uri == "/api/pair/status" -> {
                    handlePairStatus(session)
                }

                session.method == Method.POST && session.uri == "/api/session/disconnect" -> {
                    handleDisconnect(session)
                }

                session.method == Method.POST && session.uri == "/api/heartbeat" -> {
                    handleHeartbeat(session)
                }

                session.method == Method.GET && session.uri == "/api/files/list" -> {
                    handleListFiles(session)
                }

                session.method == Method.GET && session.uri == "/api/files/download" -> {
                    handleDownloadFile(session)
                }

                session.method == Method.GET && session.uri == "/api/files/download-zip" -> {
                    handleDownloadZip(session)
                }

                session.method == Method.GET && session.uri == "/api/files/download-zip-batch" -> {
                    handleDownloadZipBatch(session)
                }

                session.method == Method.PUT && session.uri == "/api/files/upload" -> {
                    handleUpload(session)
                }

                session.method == Method.DELETE && session.uri == "/api/files/delete" -> {
                    handleDelete(session)
                }

                session.method == Method.POST && session.uri == "/api/files/mkdir" -> {
                    handleCreateFolder(session)
                }

                session.method == Method.POST && session.uri == "/api/files/rename" -> {
                    handleRename(session)
                }

                session.method == Method.GET && session.uri == "/api/qr" -> {
                    handleQrSvg(session)
                }

                else -> {
                    ServerLogger.w(LOG_COMPONENT, "Unhandled route ${session.method} ${session.uri}")
                    newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
                }
            }
        }.getOrElse { throwable ->
            ServerLogger.e(
                LOG_COMPONENT,
                "Route failure method=${session.method} uri=${session.uri} message=${throwable.message}",
                throwable,
            )
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                throwable.message ?: "Server error",
            )
        }
    }

    private fun handleBootstrap(session: IHTTPSession): Response {
        val ip = session.remoteIpAddress.orEmpty()
        val auth = runtime.authenticateSession(cookie(session, sessionCookieName), ip, touch = true)
        if (auth is HostRuntimeController.AuthResult.Valid) {
            ServerLogger.d(LOG_COMPONENT, "Bootstrap paired deviceId=${auth.device.deviceId} ip=$ip")
            return jsonResponse(
                JSONObject()
                    .put("paired", true)
                    .put("device", JSONObject()
                        .put("id", auth.device.deviceId)
                        .put("displayName", auth.device.displayName)
                    )
                    .put("host", advertisedHost)
                    .put("port", portNumber)
                    .put("showHiddenFiles", runtime.showHiddenFiles())
                    .put("allowUpload", runtime.uploadEnabled())
                    .put("allowDownload", runtime.downloadEnabled())
                    .put("allowDelete", runtime.deleteEnabled()),
            )
        }

        val existingAnon = cookie(session, anonCookieName)
        val anonId = existingAnon ?: randomToken(16)
        val challenge = runtime.ensurePendingChallenge(
            anonId = anonId,
            userAgent = session.headers["user-agent"].orEmpty(),
            ipAddress = ip,
        )
        ServerLogger.d(LOG_COMPONENT, "Bootstrap unpaired issued code=${challenge.code} ip=$ip")
        val pairPayload = "mediabus://pair?token=${urlEncode(challenge.token)}&code=${urlEncode(challenge.code)}"

        val response = jsonResponse(
            JSONObject()
                .put("paired", false)
                .put("appName", "MediaBus")
                .put("pairCode", challenge.code)
                .put("pairToken", challenge.token)
                .put("pairExpiresAt", challenge.expiresAtEpochMs)
                .put("pairQrPayload", pairPayload),
        )
        clearCookie(response, sessionCookieName)
        if (existingAnon == null) {
            setCookie(response, anonCookieName, anonId, maxAge = 60L * 60 * 24 * 90)
        }
        return response
    }

    private fun handlePairStatus(session: IHTTPSession): Response {
        val token = session.queryParam("token")
        if (token.isBlank()) {
            ServerLogger.w(LOG_COMPONENT, "pair/status missing token")
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing token")
        }

        val status = runtime.pairingStatus(token)
        return when (status) {
            is HostRuntimeController.PairingStatus.Pending -> {
                jsonResponse(
                    JSONObject()
                        .put("status", "pending")
                        .put("expiresAt", status.expiresAtEpochMs),
                )
            }

            is HostRuntimeController.PairingStatus.Approved -> {
                val deviceId = status.deviceId
                if (deviceId.isNullOrBlank()) {
                    ServerLogger.w(LOG_COMPONENT, "pair/status approved without device for token=${token.take(8)}...")
                    jsonResponse(JSONObject().put("status", "not_found"))
                } else {
                    val ip = session.remoteIpAddress.orEmpty()
                    val sessionToken = runtime.createSessionForPairedDevice(deviceId, ip)
                    if (sessionToken == null) {
                        ServerLogger.w(LOG_COMPONENT, "pair/status blocked max clients deviceId=$deviceId ip=$ip")
                        jsonResponse(
                            JSONObject()
                                .put("status", "blocked")
                                .put("reason", "max_clients"),
                        )
                    } else {
                        val response = jsonResponse(JSONObject().put("status", "approved"))
                        setCookie(response, sessionCookieName, sessionToken, maxAge = 60L * 60 * 12)
                        ServerLogger.i(LOG_COMPONENT, "pair/status approved deviceId=$deviceId ip=$ip")
                        response
                    }
                }
            }

            HostRuntimeController.PairingStatus.NotFound -> {
                ServerLogger.d(LOG_COMPONENT, "pair/status not_found token=${token.take(8)}...")
                jsonResponse(JSONObject().put("status", "not_found"))
            }
        }
    }

    private fun handleDisconnect(session: IHTTPSession): Response {
        ServerLogger.i(LOG_COMPONENT, "Session disconnect requested ip=${session.remoteIpAddress.orEmpty()}")
        runtime.disconnectSession(cookie(session, sessionCookieName))
        val response = jsonResponse(JSONObject().put("status", "ok"))
        clearCookie(response, sessionCookieName)
        return response
    }

    private fun handleHeartbeat(session: IHTTPSession): Response {
        val ip = session.remoteIpAddress.orEmpty()
        val auth = runtime.authenticateSession(cookie(session, sessionCookieName), ip, touch = true)
        if (auth !is HostRuntimeController.AuthResult.Valid) {
            val revokeNotice = runtime.consumeRevocationNotice(cookie(session, sessionCookieName))
            if (revokeNotice != null) {
                ServerLogger.w(LOG_COMPONENT, "heartbeat revoked ip=$ip")
                return newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED,
                    "application/json; charset=utf-8",
                    JSONObject()
                        .put("status", "revoked")
                        .put("error", revokeNotice)
                        .toString(),
                )
            }
            ServerLogger.w(LOG_COMPONENT, "heartbeat unauthorized ip=$ip")
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized")
        }
        runtime.heartbeat(auth.device.deviceId, ip)
        return jsonResponse(JSONObject().put("status", "ok"))
    }

    private fun handleListFiles(session: IHTTPSession): Response {
        val auth = authenticatedDevice(session) ?: return unauthorized()
        val root = rootDocument() ?: return sharedFolderUnavailable()

        val segments = normalizePath(session.queryParam("path"))
        if (!runtime.showHiddenFiles() && segments.any { it.startsWith('.') }) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Hidden paths are disabled")
        }
        val directory = resolveDirectory(root, segments, createIfMissing = false)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Folder not found")

        val includeHidden = runtime.showHiddenFiles()
        val items = directory.listFiles()
            .filter { file ->
                includeHidden || !(file.name ?: "").startsWith('.')
            }
            .sortedWith(compareBy<DocumentFile>({ !it.isDirectory }, { it.name.orEmpty().lowercase(Locale.US) }))
            .map { file ->
                val itemPath = (segments + file.name.orEmpty()).joinToString("/")
                JSONObject()
                    .put("name", file.name.orEmpty())
                    .put("path", itemPath)
                    .put("directory", file.isDirectory)
                    .put("size", if (file.isFile) maxOf(0L, file.length()) else 0L)
                    .put("lastModified", file.lastModified())
            }

        val payload = JSONObject()
            .put("deviceId", auth.device.deviceId)
            .put("path", segments.joinToString("/"))
            .put("items", JSONArray(items))
            .put("showHiddenFiles", includeHidden)
        return jsonResponse(payload)
    }

    private fun handleDownloadFile(session: IHTTPSession): Response {
        if (!runtime.downloadEnabled()) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Downloads are disabled")
        }
        val auth = authenticatedDevice(session) ?: return unauthorized()
        val root = rootDocument() ?: return sharedFolderUnavailable()

        val segments = normalizePath(session.queryParam("path"))
        if (segments.isEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid file path")
        }
        if (!runtime.showHiddenFiles() && segments.any { it.startsWith('.') }) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Hidden paths are disabled")
        }

        val node = resolveNode(root, segments)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
        if (!node.isFile) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Expected file")
        }
        val batchId = session.headers["x-mediabus-batch-id"]?.takeIf { it.isNotBlank() }
        val batchTotalFiles = session.headers["x-mediabus-batch-total"]?.toIntOrNull() ?: 0
        val batchTotalBytes = session.headers["x-mediabus-batch-bytes"]?.toLongOrNull() ?: 0L
        val batchCompletedFiles = session.headers["x-mediabus-batch-completed"]?.toIntOrNull() ?: 0

        val ticket = runtime.beginTransfer(
            deviceId = auth.device.deviceId,
            direction = TransferDirection.Downloading,
            totalBytes = maxOf(0L, node.length()),
            batchId = batchId,
            batchTotalFiles = batchTotalFiles,
            batchTotalBytes = batchTotalBytes,
            batchCompletedFiles = batchCompletedFiles,
        ) ?: return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Transfer unavailable")
        ServerLogger.i(
            LOG_COMPONENT,
            "download file start deviceId=${auth.device.deviceId} path=/${segments.joinToString("/")} size=${node.length()}",
        )

        val stream = appContext.contentResolver.openInputStream(node.uri)
            ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Cannot open stream")
        val tracked = TicketTrackingInputStream(stream, ticket)
        val mimeType = URLConnection.guessContentTypeFromName(node.name.orEmpty()) ?: "application/octet-stream"
        return newChunkedResponse(Response.Status.OK, mimeType, tracked).apply {
            addHeader("Content-Disposition", "attachment; filename=\"${node.name.orEmpty().replace("\"", "")}\"")
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun handleDownloadZip(session: IHTTPSession): Response {
        if (!runtime.downloadEnabled()) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Downloads are disabled")
        }
        val auth = authenticatedDevice(session) ?: return unauthorized()
        val root = rootDocument() ?: return sharedFolderUnavailable()

        val segments = normalizePath(session.queryParam("path"))
        if (!runtime.showHiddenFiles() && segments.any { it.startsWith('.') }) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Hidden paths are disabled")
        }
        val node = resolveNode(root, segments)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Folder not found")
        if (!node.isDirectory) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Expected folder")
        }
        val batchId = session.headers["x-mediabus-batch-id"]?.takeIf { it.isNotBlank() }
        val batchTotalFiles = session.headers["x-mediabus-batch-total"]?.toIntOrNull() ?: 0
        val batchTotalBytes = session.headers["x-mediabus-batch-bytes"]?.toLongOrNull() ?: 0L
        val batchCompletedFiles = session.headers["x-mediabus-batch-completed"]?.toIntOrNull() ?: 0

        val totalBytes = calculateTreeSize(node)
        val ticket = runtime.beginTransfer(
            deviceId = auth.device.deviceId,
            direction = TransferDirection.Downloading,
            totalBytes = totalBytes,
            batchId = batchId,
            batchTotalFiles = batchTotalFiles,
            batchTotalBytes = batchTotalBytes,
            batchCompletedFiles = batchCompletedFiles,
        ) ?: return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Transfer unavailable")
        ServerLogger.i(
            LOG_COMPONENT,
            "download zip start deviceId=${auth.device.deviceId} path=/${segments.joinToString("/")} estimatedBytes=$totalBytes",
        )

        val pipeOut = java.io.PipedOutputStream()
        val pipeIn = java.io.PipedInputStream(pipeOut, 64 * 1024)

        zipExecutor.execute {
            try {
                ZipOutputStream(BufferedOutputStream(pipeOut)).use { zip ->
                    zipDirectory(node, "", zip, ticket)
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { pipeOut.close() }
            }
        }

        val fileName = (node.name ?: "folder") + ".zip"
        return newChunkedResponse(
            Response.Status.OK,
            "application/zip",
            TicketLifecycleInputStream(pipeIn, ticket),
        ).apply {
            addHeader("Content-Disposition", "attachment; filename=\"${fileName.replace("\"", "")}\"")
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun handleDownloadZipBatch(session: IHTTPSession): Response {
        if (!runtime.downloadEnabled()) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Downloads are disabled")
        }
        val auth = authenticatedDevice(session) ?: return unauthorized()
        val root = rootDocument() ?: return sharedFolderUnavailable()

        val rawPaths = session.parameters["path"].orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (rawPaths.isEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing file paths")
        }

        val includeHidden = runtime.showHiddenFiles()
        val uniquePaths = linkedSetOf<String>()
        val nodes = mutableListOf<Pair<String, DocumentFile>>()
        var totalBytes = 0L
        val batchId = session.headers["x-mediabus-batch-id"]?.takeIf { it.isNotBlank() }
        val batchTotalFiles = session.headers["x-mediabus-batch-total"]?.toIntOrNull() ?: 0
        val batchTotalBytes = session.headers["x-mediabus-batch-bytes"]?.toLongOrNull() ?: 0L
        val batchCompletedFiles = session.headers["x-mediabus-batch-completed"]?.toIntOrNull() ?: 0
        for (rawPath in rawPaths) {
            val segments = normalizePath(rawPath)
            if (segments.isEmpty()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid file path")
            }
            val joined = segments.joinToString("/")
            if (!uniquePaths.add(joined)) {
                continue
            }
            if (!includeHidden && segments.any { it.startsWith('.') }) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Hidden paths are disabled")
            }
            val node = resolveNode(root, segments)
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found: $joined")
            nodes += joined to node
            totalBytes += calculateTreeSize(node)
        }

        val ticket = runtime.beginTransfer(
            deviceId = auth.device.deviceId,
            direction = TransferDirection.Downloading,
            totalBytes = totalBytes,
            batchId = batchId,
            batchTotalFiles = batchTotalFiles,
            batchTotalBytes = batchTotalBytes,
            batchCompletedFiles = batchCompletedFiles,
        ) ?: return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Transfer unavailable")
        ServerLogger.i(
            LOG_COMPONENT,
            "download batch zip start deviceId=${auth.device.deviceId} count=${nodes.size} estimatedBytes=$totalBytes",
        )

        val pipeOut = java.io.PipedOutputStream()
        val pipeIn = java.io.PipedInputStream(pipeOut, 64 * 1024)
        zipExecutor.execute {
            try {
                ZipOutputStream(BufferedOutputStream(pipeOut)).use { zip ->
                    val usedNames = linkedSetOf<String>()
                    nodes.forEach { (_, node) ->
                        if (ticket.cancelled()) throw IOException("cancelled")
                        val preferredName = node.name.orEmpty().ifBlank { "item" }
                        val entryName = uniqueEntryName(usedNames, preferredName)
                        if (node.isDirectory) {
                            zip.putNextEntry(ZipEntry("$entryName/"))
                            zip.closeEntry()
                            zipDirectory(node, entryName, zip, ticket)
                        } else if (node.isFile) {
                            zip.putNextEntry(ZipEntry(entryName))
                            appContext.contentResolver.openInputStream(node.uri)?.use { input ->
                                val buffered = BufferedInputStream(input)
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    if (ticket.cancelled()) {
                                        throw IOException("cancelled")
                                    }
                                    val read = buffered.read(buffer)
                                    if (read <= 0) break
                                    zip.write(buffer, 0, read)
                                    ticket.addProgress(read.toLong())
                                }
                            }
                            zip.closeEntry()
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { pipeOut.close() }
            }
        }

        return newChunkedResponse(
            Response.Status.OK,
            "application/zip",
            TicketLifecycleInputStream(pipeIn, ticket),
        ).apply {
            addHeader("Content-Disposition", "attachment; filename=\"mediabus-selection.zip\"")
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun handleUpload(session: IHTTPSession): Response {
        if (!runtime.uploadEnabled()) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Uploads are disabled")
        }
        val auth = authenticatedDevice(session) ?: return unauthorized()
        val root = rootDocument() ?: return sharedFolderUnavailable()

        val targetSegments = normalizePath(session.queryParam("path"))
        if (!runtime.showHiddenFiles() && targetSegments.any { it.startsWith('.') }) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Hidden paths are disabled")
        }
        val rawName = session.queryParamOrNull("name") ?: session.headers["x-file-name"].orEmpty()
        val fileName = sanitizeSegment(rawName)
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid file name")
        val mimeType = session.headers["content-type"]?.takeIf { it.isNotBlank() } ?: "application/octet-stream"

        val directory = resolveDirectory(root, targetSegments, createIfMissing = true)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Destination not found")

        val uniqueName = nextAvailableName(directory, fileName)
        val outputFile = directory.createFile(mimeType, uniqueName)
            ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Unable to create file")

        val totalBytes = session.headers["content-length"]?.toLongOrNull() ?: -1L
        val batchId = session.headers["x-mediabus-batch-id"]?.takeIf { it.isNotBlank() }
        val batchTotalFiles = session.headers["x-mediabus-batch-total"]?.toIntOrNull() ?: 0
        val batchTotalBytes = session.headers["x-mediabus-batch-bytes"]?.toLongOrNull() ?: 0L
        val ticket = runtime.beginTransfer(
            deviceId = auth.device.deviceId,
            direction = TransferDirection.Uploading,
            totalBytes = totalBytes,
            batchId = batchId,
            batchTotalFiles = batchTotalFiles,
            batchTotalBytes = batchTotalBytes,
        ) ?: return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Transfer unavailable")
        ServerLogger.i(
            LOG_COMPONENT,
            "upload start deviceId=${auth.device.deviceId} path=/${targetSegments.joinToString("/")} name=$uniqueName contentLength=$totalBytes",
        )

        return runCatching {
            appContext.contentResolver.openOutputStream(outputFile.uri)?.use { output ->
                copyInput(
                    input = session.inputStream,
                    output = output,
                    ticket = ticket,
                    expectedTotalBytes = totalBytes,
                )
            } ?: throw IOException("Unable to open destination")

            jsonResponse(
                JSONObject()
                    .put("status", "ok")
                    .put("name", uniqueName),
            )
        }.getOrElse { throwable ->
            if (isClientDisconnectError(throwable)) {
                ServerLogger.i(
                    LOG_COMPONENT,
                    "upload aborted by client deviceId=${auth.device.deviceId} name=$uniqueName",
                )
                runCatching { outputFile.delete() }
                return@getOrElse newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")
            }
            ServerLogger.e(
                LOG_COMPONENT,
                "upload failed deviceId=${auth.device.deviceId} name=$uniqueName message=${throwable.message}",
                throwable,
            )
            runCatching { outputFile.delete() }
            if (throwable is IOException && throwable.message == "cancelled") {
                newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Transfer cancelled")
            } else {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, throwable.message ?: "Upload failed")
            }
        }.also {
            ServerLogger.i(LOG_COMPONENT, "upload finish deviceId=${auth.device.deviceId} name=$uniqueName")
            ticket.close()
        }
    }

    private fun handleDelete(session: IHTTPSession): Response {
        if (!runtime.deleteEnabled()) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Deletes are disabled")
        }
        val auth = authenticatedDevice(session) ?: return unauthorized()
        val root = rootDocument() ?: return sharedFolderUnavailable()

        val segments = normalizePath(session.queryParam("path"))
        if (segments.isEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid file path")
        }
        if (!runtime.showHiddenFiles() && segments.any { it.startsWith('.') }) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Hidden paths are disabled")
        }
        val node = resolveNode(root, segments)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
        val deleted = node.delete()
        if (!deleted) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Unable to delete item")
        }
        ServerLogger.i(
            LOG_COMPONENT,
            "delete item success deviceId=${auth.device.deviceId} path=/${segments.joinToString("/")}",
        )
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("path", segments.joinToString("/")),
        )
    }

    private fun handleCreateFolder(session: IHTTPSession): Response {
        if (!runtime.uploadEnabled()) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Uploads are disabled")
        }
        val auth = authenticatedDevice(session) ?: return unauthorized()
        val root = rootDocument() ?: return sharedFolderUnavailable()

        val targetSegments = normalizePath(session.queryParam("path"))
        if (!runtime.showHiddenFiles() && targetSegments.any { it.startsWith('.') }) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Hidden paths are disabled")
        }
        val rawName = session.queryParamOrNull("name").orEmpty()
        val folderName = sanitizeSegment(rawName)
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid folder name")
        if (!runtime.showHiddenFiles() && folderName.startsWith('.')) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Hidden paths are disabled")
        }
        val parent = resolveDirectory(root, targetSegments, createIfMissing = false)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Destination not found")
        if (parent.findFile(folderName) != null) {
            return newFixedLengthResponse(Response.Status.CONFLICT, MIME_PLAINTEXT, "Name already exists")
        }
        val created = parent.createDirectory(folderName)
            ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Unable to create folder")
        val createdPath = (targetSegments + (created.name ?: folderName)).joinToString("/")
        ServerLogger.i(
            LOG_COMPONENT,
            "mkdir success deviceId=${auth.device.deviceId} path=/$createdPath",
        )
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("path", createdPath),
        )
    }

    private fun handleRename(session: IHTTPSession): Response {
        if (!runtime.uploadEnabled()) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Uploads are disabled")
        }
        val auth = authenticatedDevice(session) ?: return unauthorized()
        val root = rootDocument() ?: return sharedFolderUnavailable()

        val segments = normalizePath(session.queryParam("path"))
        if (segments.isEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid item path")
        }
        if (!runtime.showHiddenFiles() && segments.any { it.startsWith('.') }) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Hidden paths are disabled")
        }
        val rawName = session.queryParamOrNull("name").orEmpty()
        val targetName = sanitizeSegment(rawName)
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid target name")
        if (!runtime.showHiddenFiles() && targetName.startsWith('.')) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Hidden paths are disabled")
        }

        val parentSegments = segments.dropLast(1)
        val oldName = segments.last()
        val parent = resolveDirectory(root, parentSegments, createIfMissing = false)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Parent not found")
        val node = parent.findFile(oldName)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Item not found")

        if (oldName == targetName) {
            return jsonResponse(
                JSONObject()
                    .put("status", "ok")
                    .put("path", segments.joinToString("/"))
                    .put("renamed", false),
            )
        }
        if (parent.findFile(targetName) != null) {
            return newFixedLengthResponse(Response.Status.CONFLICT, MIME_PLAINTEXT, "Name already exists")
        }
        val renamed = node.renameTo(targetName)
        if (!renamed) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Unable to rename item")
        }
        val renamedPath = (parentSegments + targetName).joinToString("/")
        ServerLogger.i(
            LOG_COMPONENT,
            "rename success deviceId=${auth.device.deviceId} from=/${segments.joinToString("/")} to=/$renamedPath",
        )
        return jsonResponse(
            JSONObject()
                .put("status", "ok")
                .put("path", renamedPath)
                .put("renamed", true),
        )
    }

    private fun handleQrSvg(session: IHTTPSession): Response {
        val value = session.queryParam("value")
        if (value.isBlank()) {
            ServerLogger.w(LOG_COMPONENT, "qr endpoint missing value")
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing value")
        }
        ServerLogger.d(LOG_COMPONENT, "qr generated size=240")
        val svg = qrSvg(value, 240)
        return newFixedLengthResponse(Response.Status.OK, "image/svg+xml", svg)
    }

    private fun authenticatedDevice(session: IHTTPSession): HostRuntimeController.AuthResult.Valid? {
        val ip = session.remoteIpAddress.orEmpty()
        val auth = runtime.authenticateSession(cookie(session, sessionCookieName), ip, touch = true)
        if (auth !is HostRuntimeController.AuthResult.Valid) {
            ServerLogger.w(LOG_COMPONENT, "auth failed uri=${session.uri} ip=$ip")
        }
        return auth as? HostRuntimeController.AuthResult.Valid
    }

    private fun copyInput(
        input: InputStream,
        output: OutputStream,
        ticket: HostRuntimeController.TransferTicket,
        expectedTotalBytes: Long,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var receivedBytes = 0L
        while (true) {
            if (ticket.cancelled()) {
                throw IOException("cancelled")
            }
            val read = try {
                input.read(buffer)
            } catch (timeout: SocketTimeoutException) {
                if (expectedTotalBytes > 0 && receivedBytes >= expectedTotalBytes) {
                    break
                }
                throw timeout
            }
            if (read <= 0) break
            output.write(buffer, 0, read)
            receivedBytes += read
            ticket.addProgress(read.toLong())
            if (expectedTotalBytes > 0 && receivedBytes >= expectedTotalBytes) {
                // Request body fully consumed; do not wait for socket timeout/EOF.
                break
            }
        }
        output.flush()
    }

    private fun zipDirectory(
        directory: DocumentFile,
        prefix: String,
        zip: ZipOutputStream,
        ticket: HostRuntimeController.TransferTicket,
    ) {
        val children = directory.listFiles().sortedBy { it.name.orEmpty().lowercase(Locale.US) }
        children.forEach { child ->
            if (!runtime.showHiddenFiles() && child.name.orEmpty().startsWith('.')) {
                return@forEach
            }
            if (ticket.cancelled()) {
                throw IOException("cancelled")
            }
            val entryName = if (prefix.isBlank()) child.name.orEmpty() else "$prefix/${child.name.orEmpty()}"
            if (child.isDirectory) {
                zip.putNextEntry(ZipEntry("$entryName/"))
                zip.closeEntry()
                zipDirectory(child, entryName, zip, ticket)
            } else if (child.isFile) {
                zip.putNextEntry(ZipEntry(entryName))
                appContext.contentResolver.openInputStream(child.uri)?.use { input ->
                    val buffered = BufferedInputStream(input)
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        if (ticket.cancelled()) {
                            throw IOException("cancelled")
                        }
                        val read = buffered.read(buffer)
                        if (read <= 0) break
                        zip.write(buffer, 0, read)
                        ticket.addProgress(read.toLong())
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun calculateTreeSize(root: DocumentFile): Long {
        if (root.isFile) return maxOf(0L, root.length())
        var total = 0L
        root.listFiles().forEach { child ->
            if (!runtime.showHiddenFiles() && child.name.orEmpty().startsWith('.')) {
                return@forEach
            }
            total += if (child.isDirectory) calculateTreeSize(child) else maxOf(0L, child.length())
        }
        return total
    }

    private fun rootDocument(): DocumentFile? {
        return DocumentFile.fromTreeUri(appContext, sharedFolderUri)
            ?.takeIf { it.exists() && it.canRead() }
    }

    private fun resolveNode(
        root: DocumentFile,
        pathSegments: List<String>,
    ): DocumentFile? {
        var cursor = root
        for (segment in pathSegments) {
            val child = cursor.findFile(segment) ?: return null
            cursor = child
        }
        return cursor
    }

    private fun resolveDirectory(
        root: DocumentFile,
        pathSegments: List<String>,
        createIfMissing: Boolean,
    ): DocumentFile? {
        var cursor = root
        for (segment in pathSegments) {
            val existing = cursor.findFile(segment)
            cursor = when {
                existing == null && createIfMissing -> cursor.createDirectory(segment) ?: return null
                existing != null && existing.isDirectory -> existing
                else -> return null
            }
        }
        return cursor
    }

    private fun normalizePath(path: String): List<String> {
        if (path.isBlank()) {
            return emptyList()
        }
        return path.replace('\\', '/')
            .split('/')
            .filter { it.isNotBlank() }
            .map { value -> sanitizeSegment(value) ?: throw IllegalArgumentException("Illegal path") }
    }

    private fun sanitizeSegment(segment: String): String? {
        val trimmed = segment.trim().replace('\\', '/')
        if (trimmed.isBlank() || trimmed == "." || trimmed == "..") {
            return null
        }
        if (trimmed.contains('/')) {
            return null
        }
        return trimmed
    }

    private fun nextAvailableName(directory: DocumentFile, originalName: String): String {
        if (directory.findFile(originalName) == null) {
            return originalName
        }
        val dot = originalName.lastIndexOf('.')
        val base = if (dot > 0) originalName.substring(0, dot) else originalName
        val ext = if (dot > 0) originalName.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = "$base ($index)$ext"
            if (directory.findFile(candidate) == null) {
                return candidate
            }
            index++
        }
    }

    private fun uniqueEntryName(usedNames: MutableSet<String>, originalName: String): String {
        if (usedNames.add(originalName)) return originalName
        val dot = originalName.lastIndexOf('.')
        val base = if (dot > 0) originalName.substring(0, dot) else originalName
        val ext = if (dot > 0) originalName.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = "$base ($index)$ext"
            if (usedNames.add(candidate)) {
                return candidate
            }
            index++
        }
    }

    private fun unauthorized(): Response {
        ServerLogger.w(LOG_COMPONENT, "Returning unauthorized response")
        return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized").apply {
            clearCookie(this, sessionCookieName)
        }
    }

    private fun sharedFolderUnavailable(): Response {
        ServerLogger.w(LOG_COMPONENT, "Shared folder unavailable")
        return newFixedLengthResponse(
            Response.Status.INTERNAL_ERROR,
            MIME_PLAINTEXT,
            "Shared folder unavailable",
        )
    }

    private fun jsonResponse(body: JSONObject): Response {
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body.toString())
    }

    private fun htmlResponse(html: String): Response {
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html).apply {
            addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
            addHeader("Pragma", "no-cache")
            addHeader("Expires", "0")
        }
    }

    private fun serveWebAsset(uri: String): Response? {
        val cleanUri = uri.substringBefore('?')
        val relativePath = cleanUri.trimStart('/')
        if (relativePath.isBlank() || relativePath.contains("..")) {
            return null
        }

        val assetPath = "web/$relativePath"
        val bytes = runCatching {
            appContext.assets.open(assetPath).use { stream -> stream.readBytes() }
        }.getOrNull() ?: return null

        val mimeType = URLConnection.guessContentTypeFromName(relativePath) ?: when {
            relativePath.endsWith(".js") -> "application/javascript; charset=utf-8"
            relativePath.endsWith(".css") -> "text/css; charset=utf-8"
            relativePath.endsWith(".svg") -> "image/svg+xml"
            relativePath.endsWith(".json") -> "application/json; charset=utf-8"
            relativePath.endsWith(".map") -> "application/json; charset=utf-8"
            else -> "application/octet-stream"
        }
        return newFixedLengthResponse(
            Response.Status.OK,
            mimeType,
            bytes.inputStream(),
            bytes.size.toLong(),
        ).apply {
            if (
                relativePath == "index.html" ||
                relativePath == "sw.js" ||
                relativePath == "manifest.webmanifest"
            ) {
                addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
                addHeader("Pragma", "no-cache")
                addHeader("Expires", "0")
            }
        }
    }

    private fun cookie(session: IHTTPSession, name: String): String? {
        val cookieHeader = session.headers["cookie"] ?: return null
        return cookieHeader.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter("$name=")
            ?.takeIf { it.isNotBlank() }
    }

    private fun setCookie(
        response: Response,
        name: String,
        value: String,
        maxAge: Long,
    ) {
        val cookie = buildString {
            append(name)
            append('=')
            append(value)
            append("; Path=/; Max-Age=")
            append(maxAge)
            append("; HttpOnly; Secure; SameSite=Lax")
        }
        response.addHeader("Set-Cookie", cookie)
    }

    private fun clearCookie(response: Response, name: String) {
        response.addHeader("Set-Cookie", "$name=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Lax")
    }

    private fun randomToken(size: Int): String {
        return Base64Url.encode(UUID.randomUUID().toString().toByteArray())
            .take(size)
    }

    private fun qrSvg(content: String, size: Int): String {
        val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val cell = 4
        val margin = 4
        val width = matrix.width
        val height = matrix.height
        val canvasSize = (width + margin * 2) * cell

        val builder = StringBuilder()
        builder.append("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 $canvasSize $canvasSize' shape-rendering='crispEdges'>")
        builder.append("<rect width='100%' height='100%' fill='white'/>")
        for (x in 0 until width) {
            for (y in 0 until height) {
                if (matrix[x, y]) {
                    val rx = (x + margin) * cell
                    val ry = (y + margin) * cell
                    builder.append("<rect x='$rx' y='$ry' width='$cell' height='$cell' fill='black'/>")
                }
            }
        }
        builder.append("</svg>")
        return builder.toString()
    }

    private fun urlEncode(text: String): String {
        return URLEncoder.encode(text, StandardCharsets.UTF_8.name())
    }

    private fun shouldLogRequest(session: IHTTPSession): Boolean {
        return when (session.uri) {
            "/api/files/upload",
            "/api/files/download",
            "/api/files/download-zip",
            "/api/files/download-zip-batch",
            "/api/files/delete",
            "/api/files/mkdir",
            "/api/files/rename",
            "/api/session/disconnect",
            -> true
            else -> false
        }
    }

    private fun isClientDisconnectError(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            if (current is SocketException) {
                val message = current.message.orEmpty()
                if (
                    message.contains("socket is closed", ignoreCase = true) ||
                    message.contains("broken pipe", ignoreCase = true) ||
                    message.contains("connection reset", ignoreCase = true)
                ) {
                    return true
                }
            }
            if (current is IOException && current.message.orEmpty().contains("cancelled", ignoreCase = true)) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun clientShellHtml(): String {
        return runCatching {
            appContext.assets.open("web/index.html").bufferedReader().use { it.readText() }
        }.getOrElse {
            appContext.assets.open("client.html").bufferedReader().use { it.readText() }
        }
    }

    private companion object {
        private const val sessionCookieName = "mb_session"
        private const val anonCookieName = "mb_anon"
        private const val LOG_COMPONENT = "HttpServer"
    }
}

private class TicketTrackingInputStream(
    private val delegate: InputStream,
    private val ticket: HostRuntimeController.TransferTicket,
) : InputStream() {
    override fun read(): Int {
        if (ticket.cancelled()) {
            throw IOException("cancelled")
        }
        val value = delegate.read()
        if (value >= 0) {
            ticket.addProgress(1)
        }
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (ticket.cancelled()) {
            throw IOException("cancelled")
        }
        val count = delegate.read(b, off, len)
        if (count > 0) {
            ticket.addProgress(count.toLong())
        }
        return count
    }

    override fun close() {
        runCatching { delegate.close() }
        ticket.close()
    }
}

private class TicketLifecycleInputStream(
    private val delegate: InputStream,
    private val ticket: HostRuntimeController.TransferTicket,
) : InputStream() {
    override fun read(): Int = delegate.read()

    override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)

    override fun close() {
        runCatching { delegate.close() }
        ticket.close()
    }
}

private fun IHTTPSession.queryParam(name: String): String {
    return parameters[name]?.firstOrNull().orEmpty()
}

private fun IHTTPSession.queryParamOrNull(name: String): String? {
    return parameters[name]?.firstOrNull()
}
