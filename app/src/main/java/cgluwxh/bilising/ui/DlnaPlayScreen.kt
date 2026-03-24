package cgluwxh.bilising.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cgluwxh.bilising.DlnaForegroundService
import cgluwxh.bilising.controller.ConnectionState
import cgluwxh.bilising.controller.PlaybackState
import cgluwxh.bilising.controller.PlayController
import cgluwxh.bilising.ui.components.DlnaDeviceList
import cgluwxh.bilising.ui.components.QrCodeImage

@Composable
fun DlnaPlayScreen(
    serverUrl: String,
    roomId: String,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val controller = remember { PlayController(context, serverUrl, roomId) }
    var showBatteryDialog by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    DisposableEffect(Unit) {
        controller.start()

        // 启动前台 Service（持有 WakeLock + 显示通知，防止系统熄屏后杀进程）
        val serviceIntent = Intent(context, DlnaForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        // 检查是否已豁免电池优化
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
            showBatteryDialog = true
        }

        onDispose {
            controller.cleanup()
            context.stopService(Intent(context, DlnaForegroundService::class.java))
        }
    }

    val state by controller.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Top bar: back + connection status
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onNavigateBack,
                modifier = Modifier.padding(end = 12.dp)
            ) {
                Text("返回")
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (state.connectionState) {
                        ConnectionState.CONNECTING -> "连接中..."
                        ConnectionState.CONNECTED -> "已连接"
                        ConnectionState.DISCONNECTED -> "连接断开"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (state.connectionState) {
                        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Text(
                    text = "房间: $roomId",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // DLNA device list
        DlnaDeviceList(
            devices = state.dlnaDevices,
            selectedDevice = state.selectedDevice,
            isSearching = state.isSearchingDevices,
            onDeviceSelected = { controller.selectDevice(it) },
            onRefresh = { controller.searchDevices() },
            onManualIpAdd = { controller.addDeviceByIp(it) },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // Playback info
        Text(
            text = "播放信息",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "正在播放",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (state.currentSong != null) {
                    Text(
                        text = state.currentSong!!.title,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "UP主: ${state.currentSong!!.producer}  点歌: ${state.currentSong!!.user_name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "暂无歌曲",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "状态: ${state.statusMessage}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Playback controls: only visible when something is playing or paused
                if (state.currentSong != null &&
                    (state.playbackState == PlaybackState.PLAYING || state.isPaused)) {

                    Spacer(modifier = Modifier.height(8.dp))

                    // Progress slider
                    if (state.durationMs > 0) {
                        val sliderValue = if (isDragging) dragValue
                        else (state.currentPositionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)

                        Slider(
                            value = sliderValue,
                            onValueChange = { isDragging = true; dragValue = it },
                            onValueChangeFinished = {
                                controller.seekTo((dragValue * state.durationMs).toLong())
                                isDragging = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatMs(if (isDragging) (dragValue * state.durationMs).toLong() else state.currentPositionMs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = formatMs(state.durationMs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { controller.togglePause() },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text(if (state.isPaused) "▶ 继续" else "⏸ 暂停")
                    }
                }

                if (state.nextSong != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "下一首",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = state.nextSong!!.title,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "UP主: ${state.nextSong!!.producer}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (state.errorMessage.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    text = state.errorMessage,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Cut song button
        var showCutDialog by remember { mutableStateOf(false) }
        Button(
            onClick = { showCutDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("切歌")
        }
        if (showCutDialog) {
            AlertDialog(
                onDismissRequest = { showCutDialog = false },
                title = { Text("确认切歌") },
                text = { Text("确定要跳过当前歌曲吗？") },
                confirmButton = {
                    TextButton(onClick = { showCutDialog = false; controller.sendNextSong() }) {
                        Text("切歌")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCutDialog = false }) { Text("取消") }
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // QR code
        Text(
            text = "扫码点歌",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "其他用户扫码加入房间点歌",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        QrCodeImage(
            content = "https://$serverUrl/?bilising-room-id=$roomId",
            size = 300,
            modifier = Modifier
                .size(200.dp)
                .align(Alignment.CenterHorizontally)
        )
        Spacer(modifier = Modifier.height(16.dp))
    }

    // 电池优化豁免请求弹窗

    if (showBatteryDialog) {
        AlertDialog(
            onDismissRequest = { showBatteryDialog = false },
            title = { Text("建议关闭电池优化") },
            text = {
                Text("熄屏后系统可能中断网络连接和后台任务，导致投屏中断。\n\n建议在下一页中找到 BiliSing 并选择 <不限制>，以保证长时间稳定投屏。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showBatteryDialog = false
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        // 部分厂商不支持直接跳转，打开电池设置页
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }) { Text("去设置") }
            },
            dismissButton = {
                TextButton(onClick = { showBatteryDialog = false }) { Text("暂不") }
            }
        )
    }
}

/** Format milliseconds as "M:SS" (e.g. "3:07"). */
private fun formatMs(ms: Long): String {
    val sec = ms / 1000
    return "%d:%02d".format(sec / 60, sec % 60)
}
