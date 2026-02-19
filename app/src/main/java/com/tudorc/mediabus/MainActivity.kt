package com.tudorc.mediabus

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlin.math.cos
import kotlin.math.sin
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.tudorc.mediabus.model.DevicePresence
import com.tudorc.mediabus.model.TransferDirection
import com.tudorc.mediabus.service.HostActionResult
import com.tudorc.mediabus.service.MediaBusHostService
import com.tudorc.mediabus.ui.QrScannerDialog
import com.tudorc.mediabus.ui.theme.MediaBusTheme
import com.tudorc.mediabus.util.Formatters
import com.tudorc.mediabus.util.MediaBusHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MediaBusTheme {
                val viewModel: MainViewModel = viewModel()
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                val context = LocalContext.current

                var showAddressDialog by remember { mutableStateOf(false) }
                var showCodeDialog by remember { mutableStateOf(false) }
                var showScanner by remember { mutableStateOf(false) }
                var pairCode by remember { mutableStateOf("") }
                var wasTransitioning by remember { mutableStateOf(uiState.serverTransitioning) }

                LaunchedEffect(uiState.serverTransitioning) {
                    if (uiState.serverTransitioning) {
                        MediaBusHaptics.startTransitionWave(context)
                    } else {
                        MediaBusHaptics.stopTransitionWave(context, withRelease = wasTransitioning)
                    }
                    wasTransitioning = uiState.serverTransitioning
                }
                DisposableEffect(Unit) {
                    onDispose {
                        MediaBusHaptics.stopTransitionWave(context, withRelease = false)
                    }
                }

                val folderPicker =
                    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                        if (uri != null) {
                            viewModel.onFolderSelected(
                                uri = uri,
                                persistFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                            )
                        }
                    }

                val cameraPermissionLauncher =
                    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                        if (granted) {
                            showScanner = true
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar(getString(R.string.camera_permission_required))
                            }
                        }
                    }

                fun handlePairResult(result: HostActionResult) {
                    val message = when (result) {
                        HostActionResult.Success -> getString(R.string.pair_success)
                        HostActionResult.NotFound -> getString(R.string.pair_not_found)
                        HostActionResult.Expired -> getString(R.string.pair_expired)
                        HostActionResult.ServiceOffline -> getString(R.string.pair_service_offline)
                    }
                    scope.launch { snackbarHostState.showSnackbar(message) }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                ) { innerPadding ->
                    HostControlPanel(
                        uiState = uiState,
                        onSelectFolder = {
                            MediaBusHaptics.performTap(context)
                            folderPicker.launch(null)
                        },
                        onToggleServer = {
                            if (uiState.serverTransitioning) return@HostControlPanel
                            MediaBusHaptics.performTap(context)
                            if (uiState.serverRunning) viewModel.stopServer() else viewModel.startServer()
                        },
                        onShowAddress = {
                            MediaBusHaptics.performTap(context)
                            showAddressDialog = true
                        },
                        onOpenManualPair = {
                            MediaBusHaptics.performTap(context)
                            showCodeDialog = true
                        },
                        onOpenScanner = {
                            MediaBusHaptics.performTap(context)
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                showScanner = true
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        onToggleHiddenFiles = { enabled ->
                            MediaBusHaptics.performTap(context)
                            viewModel.onShowHiddenFiles(enabled)
                        },
                        onToggleAllowUpload = { enabled ->
                            MediaBusHaptics.performTap(context)
                            viewModel.onAllowUpload(enabled)
                        },
                        onToggleAllowDownload = { enabled ->
                            MediaBusHaptics.performTap(context)
                            viewModel.onAllowDownload(enabled)
                        },
                        onToggleAllowDelete = { enabled ->
                            MediaBusHaptics.performTap(context)
                            viewModel.onAllowDelete(enabled)
                        },
                        onRevokeDevice = { deviceId ->
                            MediaBusHaptics.performTap(context)
                            viewModel.revokeDevice(deviceId)
                            scope.launch { snackbarHostState.showSnackbar(getString(R.string.device_revoked)) }
                        },
                        modifier = Modifier.padding(innerPadding),
                    )

                    if (showAddressDialog) {
                        AddressDialog(
                            url = uiState.url,
                            onDismiss = { showAddressDialog = false },
                        )
                    }

                    if (showCodeDialog) {
                        AlertDialog(
                            onDismissRequest = {
                                MediaBusHaptics.performTap(context)
                                showCodeDialog = false
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    MediaBusHaptics.performTap(context)
                                    handlePairResult(viewModel.approvePairCode(pairCode))
                                    showCodeDialog = false
                                    pairCode = ""
                                }) {
                                    Text(stringResource(R.string.pair_now))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    MediaBusHaptics.performTap(context)
                                    showCodeDialog = false
                                }) {
                                    Text(stringResource(R.string.cancel))
                                }
                            },
                            title = { Text(stringResource(R.string.enter_pair_code)) },
                            text = {
                                OutlinedTextField(
                                    value = pairCode,
                                    onValueChange = { pairCode = it.filter(Char::isLetterOrDigit).take(64) },
                                    label = { Text(stringResource(R.string.pair_code_label)) },
                                    singleLine = true,
                                )
                            },
                        )
                    }

                    if (showScanner) {
                        QrScannerDialog(
                            onDismiss = {
                                MediaBusHaptics.performTap(context)
                                showScanner = false
                            },
                            onPayloadScanned = { payload ->
                                showScanner = false
                                handlePairResult(viewModel.approvePairPayload(payload))
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HostControlPanel(
    uiState: HostControlUiState,
    onSelectFolder: () -> Unit,
    onToggleServer: () -> Unit,
    onShowAddress: () -> Unit,
    onOpenManualPair: () -> Unit,
    onOpenScanner: () -> Unit,
    onToggleHiddenFiles: (Boolean) -> Unit,
    onToggleAllowUpload: (Boolean) -> Unit,
    onToggleAllowDownload: (Boolean) -> Unit,
    onToggleAllowDelete: (Boolean) -> Unit,
    onRevokeDevice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = stringResource(R.string.host_panel_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        val borderColor = when {
            uiState.serverTransitioning -> Color(0xFFFF9A3D)
            uiState.transferSummary.inProgress -> Color(0xFF4FA8FF)
            uiState.serverRunning -> Color(0xFF2FC16A)
            else -> Color(0xFFD95C5C)
        }
        val borderBrush = animatedStatusBorderBrush(borderColor)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(width = 2.dp, brush = borderBrush),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.server_controls_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(text = uiState.statusText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                WebServerAccessibilityStatus(uiState = uiState)
                uiState.error?.let { message ->
                    Text(text = message, color = MaterialTheme.colorScheme.error)
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onToggleServer,
                    enabled = !uiState.serverTransitioning && (uiState.serverRunning || uiState.hasValidFolder),
                ) {
                    Text(
                        if (uiState.serverRunning) stringResource(R.string.stop_server)
                        else stringResource(R.string.start_server),
                    )
                }
            }
        }

        PairingControlsCard(
            serverOnline = uiState.serverRunning,
            serverTransitioning = uiState.serverTransitioning,
            onOpenScanner = onOpenScanner,
            onOpenManualPair = onOpenManualPair,
            onOpenAddress = onShowAddress,
        )

        StatsCard(uiState = uiState)

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.shared_folder_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = uiState.folderDisplayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (uiState.hasValidFolder) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(onClick = onSelectFolder) {
                        Text(stringResource(R.string.select_folder))
                    }
                }
                SettingToggleRow(
                    label = stringResource(R.string.show_hidden_files),
                    checked = uiState.showHiddenFiles,
                    onCheckedChange = onToggleHiddenFiles,
                )
                SettingToggleRow(
                    label = stringResource(R.string.allow_upload),
                    checked = uiState.allowUpload,
                    onCheckedChange = onToggleAllowUpload,
                )
                SettingToggleRow(
                    label = stringResource(R.string.allow_download),
                    checked = uiState.allowDownload,
                    onCheckedChange = onToggleAllowDownload,
                )
                SettingToggleRow(
                    label = stringResource(R.string.allow_delete),
                    checked = uiState.allowDelete,
                    onCheckedChange = onToggleAllowDelete,
                )
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.paired_devices_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (uiState.pairedDevices.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_paired_devices),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    uiState.pairedDevices.forEach { status ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                StatusDot(color = when (status.presence) {
                                    DevicePresence.Disconnected -> Color(0xFFD95C5C)
                                    DevicePresence.Connected -> Color(0xFF2FC16A)
                                    DevicePresence.Transferring -> Color(0xFF4FA8FF)
                                })
                                Column {
                                    Text(text = status.device.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        text = status.device.lastKnownIp,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.last_seen_label,
                                            Formatters.formatTimestamp(status.device.lastConnectedEpochMs),
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            TextButton(onClick = { onRevokeDevice(status.device.deviceId) }) {
                                Text(text = stringResource(R.string.revoke), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun animatedStatusBorderBrush(baseColor: Color): Brush {
    val transition = rememberInfiniteTransition(label = "server-controls-border")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
        ),
        label = "server-controls-border-phase",
    )
    val radius = 460f
    val cx = 300f
    val cy = 300f
    val sx = cx + (cos(phase.toDouble()) * radius).toFloat()
    val sy = cy + (sin(phase.toDouble()) * radius).toFloat()
    val ex = cx + (cos((phase + Math.PI).toDouble()) * radius).toFloat()
    val ey = cy + (sin((phase + Math.PI).toDouble()) * radius).toFloat()
    return Brush.linearGradient(
        colors = listOf(
            baseColor.copy(alpha = 0.2f),
            baseColor.copy(alpha = 0.95f),
            baseColor.copy(alpha = 0.2f),
        ),
        start = Offset(x = sx, y = sy),
        end = Offset(x = ex, y = ey),
    )
}

@Composable
private fun StatsCard(uiState: HostControlUiState) {
    val summary = uiState.transferSummary
    val isActive = summary.inProgress
    val overallProgress = if (summary.overallTotalBytes > 0L) {
        (summary.overallTransferredBytes.toFloat() / summary.overallTotalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val fileProgress = if (summary.currentFileTotalBytes > 0L) {
        (summary.currentFileTransferredBytes.toFloat() / summary.currentFileTotalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.stats_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (!isActive) {
                Text(
                    text = stringResource(R.string.stats_idle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val direction = when (summary.direction) {
                    TransferDirection.Uploading -> stringResource(R.string.direction_uploading)
                    TransferDirection.Downloading -> stringResource(R.string.direction_downloading)
                    TransferDirection.Mixed -> stringResource(R.string.direction_transferring)
                    TransferDirection.None -> stringResource(R.string.direction_transferring)
                }
                Text(
                    text = "$direction ${summary.activeFiles}/${summary.totalFiles}",
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(
                        R.string.transfer_progress_overall,
                        Formatters.formatBytes(summary.overallTransferredBytes),
                        Formatters.formatBytes(summary.overallTotalBytes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (summary.overallTotalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { overallProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                Text(
                    text = stringResource(
                        R.string.transfer_progress_current_file,
                        Formatters.formatBytes(summary.currentFileTransferredBytes),
                        Formatters.formatBytes(summary.currentFileTotalBytes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (summary.currentFileTotalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { fileProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun WebServerAccessibilityStatus(uiState: HostControlUiState) {
    val isStopping = uiState.serverTransitioning && uiState.serverRunning
    val label = when {
        uiState.serverRunning -> stringResource(R.string.webserver_status_online)
        isStopping -> stringResource(R.string.webserver_status_stopping)
        else -> stringResource(R.string.webserver_status_offline)
    }
    val stateColor = when {
        uiState.serverRunning -> Color(0xFF2FC16A)
        else -> Color(0xFFD95C5C)
    }
    val prefix = stringResource(R.string.webserver_status_prefix)
    val message = buildAnnotatedString {
        append(prefix)
        append(" ")
        withStyle(style = SpanStyle(color = stateColor, fontWeight = FontWeight.SemiBold)) {
            append(label)
        }
    }
    Text(
        text = message,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PairingControlsCard(
    serverOnline: Boolean,
    serverTransitioning: Boolean,
    onOpenScanner: () -> Unit,
    onOpenManualPair: () -> Unit,
    onOpenAddress: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var openingAddress by remember { mutableStateOf(false) }
    val pairingEnabled = serverOnline && !serverTransitioning
    val qrClickable = pairingEnabled && !openingAddress
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.pairing_controls_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                FilledTonalButton(
                    onClick = onOpenScanner,
                    enabled = pairingEnabled,
                ) {
                    Text(stringResource(R.string.scan_pair_qr))
                }
                FilledTonalButton(
                    onClick = onOpenManualPair,
                    enabled = pairingEnabled,
                ) {
                    Text(stringResource(R.string.enter_pair_code))
                }
            }
            Box(
                modifier = Modifier
                    .size(104.dp)
                    .clickable(
                        enabled = qrClickable,
                        onClick = {
                            if (!pairingEnabled) return@clickable
                            openingAddress = true
                            onOpenAddress()
                            scope.launch {
                                runCatching { playAddressOpenHaptic(context) }
                                openingAddress = false
                            }
                        },
                ),
                contentAlignment = Alignment.Center,
            ) {
                if (pairingEnabled) {
                    Box(
                        modifier = Modifier
                            .size(104.dp)
                            .border(
                                width = 2.5.dp,
                                brush = animatedQrGlowBrush(),
                                shape = RoundedCornerShape(18.dp),
                            ),
                    )
                }
                QrCode(
                    url = "https://${MediaBusHostService.DEFAULT_HOST_NAME}:${MediaBusHostService.SERVER_PORT}",
                    modifier = Modifier.border(
                        width = 1.5.dp,
                        color = Color(0xFF8A8A8A),
                        shape = RoundedCornerShape(14.dp),
                    ),
                    size = 94.dp,
                    reverseContrast = !pairingEnabled,
                )
            }
        }
    }
}

@Composable
private fun animatedQrGlowBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "qr-button-glow")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
        ),
        label = "qr-button-glow-phase",
    )
    fun wrap(value: Float): Float {
        val normalized = value % 1f
        return if (normalized < 0f) normalized + 1f else normalized
    }
    val stops = arrayOf(
        0f to Color.Transparent,
        wrap(phase - 0.20f) to Color.Transparent,
        wrap(phase - 0.08f) to Color.White.copy(alpha = 0.35f),
        wrap(phase) to Color.White.copy(alpha = 0.95f),
        wrap(phase + 0.08f) to Color.White.copy(alpha = 0.35f),
        wrap(phase + 0.20f) to Color.Transparent,
        1f to Color.Transparent,
    ).sortedBy { it.first }.toTypedArray()
    return Brush.sweepGradient(colorStops = stops)
}

private suspend fun playAddressOpenHaptic(context: Context) {
    val vibrator = context.mediaBusVibrator() ?: return
    if (!vibrator.hasVibrator()) return

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val durations = LongArray(12) { index -> 45L + (index * 15L) }
        val amplitudes = IntArray(12) { index -> (25 + (index * 18)).coerceAtMost(255) }
        val ramp = VibrationEffect.createWaveform(durations, amplitudes, -1)
        vibrator.vibrate(ramp)
        delay(220)
        vibrator.cancel()
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(120)
        delay(140)
        @Suppress("DEPRECATION")
        vibrator.vibrate(30)
    }
}

private fun Context.mediaBusVibrator(): Vibrator? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
}

@Composable
private fun AddressDialog(
    url: String,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                MediaBusHaptics.performTap(context)
                onDismiss()
            }) {
                Text(stringResource(R.string.close))
            }
        },
        title = { Text(stringResource(R.string.server_address)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = url,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable {
                        MediaBusHaptics.performTap(context)
                        runCatching { uriHandler.openUri(url) }.onFailure {
                            Toast.makeText(
                                context,
                                context.getString(R.string.unable_to_open_url),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
                QrCode(url = url)
            }
        },
    )
}

@Composable
private fun QrCode(
    url: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 190.dp,
    reverseContrast: Boolean = false,
) {
    val cacheKey = remember(url) { "qr|$url|512" }
    val qrBackground = if (reverseContrast) Color.Black else Color.White
    val cachedPair by produceState<QrBitmapPair?>(
        initialValue = QrBitmapCache.get(cacheKey),
        key1 = cacheKey,
    ) {
        if (value == null) {
            val generated = withContext(Dispatchers.Default) {
                QrBitmapPair(
                    normal = qrBitmap(url, size = 512, reverseContrast = false),
                    inverted = qrBitmap(url, size = 512, reverseContrast = true),
                )
            }
            QrBitmapCache.put(cacheKey, generated)
            value = generated
        }
    }
    val bitmap = if (reverseContrast) cachedPair?.inverted else cachedPair?.normal

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.qr_content_description),
            modifier = modifier
                .size(size)
                .background(qrBackground, shape = MaterialTheme.shapes.medium)
                .padding(if (size < 120.dp) 6.dp else 10.dp),
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .background(qrBackground, shape = MaterialTheme.shapes.medium)
                .padding(if (size < 120.dp) 6.dp else 10.dp),
        )
    }
}

@Composable
private fun StatusDot(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(10.dp)
            .background(color, CircleShape),
    )
}

private data class QrBitmapPair(
    val normal: Bitmap?,
    val inverted: Bitmap?,
)

private object QrBitmapCache {
    private const val MAX_ENTRIES = 32
    private val map = object : LinkedHashMap<String, QrBitmapPair>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, QrBitmapPair>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    fun get(key: String): QrBitmapPair? = synchronized(map) { map[key] }

    fun put(
        key: String,
        value: QrBitmapPair,
    ) {
        synchronized(map) { map[key] = value }
    }
}

private fun qrBitmap(
    content: String,
    size: Int,
    reverseContrast: Boolean = false,
): Bitmap? {
    return runCatching {
        val matrix: BitMatrix = MultiFormatWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size,
        )
        Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
            for (x in 0 until size) {
                for (y in 0 until size) {
                    setPixel(
                        x,
                        y,
                        if (matrix[x, y]) {
                            if (reverseContrast) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                        } else {
                            if (reverseContrast) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                        },
                    )
                }
            }
        }
    }.getOrNull()
}
