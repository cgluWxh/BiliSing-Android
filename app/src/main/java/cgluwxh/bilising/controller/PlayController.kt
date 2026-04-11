package cgluwxh.bilising.controller

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import cgluwxh.bilising.DlnaForegroundService
import cgluwxh.bilising.bili.BiliParser
import cgluwxh.bilising.bili.BiliPlayUrlApi
import cgluwxh.bilising.dlna.DlnaDevice
import cgluwxh.bilising.dlna.DlnaManager
import cgluwxh.bilising.model.Song
import cgluwxh.bilising.socket.RoomSocketManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED }
enum class PlaybackState { IDLE, FETCHING, PUSHING, PLAYING, STOPPED, ERROR }

data class DlnaUiState(
    val connectionState: ConnectionState = ConnectionState.CONNECTING,
    val dlnaDevices: List<DlnaDevice> = emptyList(),
    val selectedDevice: DlnaDevice? = null,
    val isSearchingDevices: Boolean = false,
    val currentSong: Song? = null,
    val nextSong: Song? = null,
    val playbackState: PlaybackState = PlaybackState.IDLE,
    val statusMessage: String = "",
    val errorMessage: String = "",
    val isPaused: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L
)

class PlayController(
    private val context: Context,
    val serverUrl: String,
    val roomId: String
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _uiState = MutableStateFlow(DlnaUiState())
    val uiState: StateFlow<DlnaUiState> = _uiState.asStateFlow()

    private val dlnaManager = DlnaManager(context)
    private var socketManager: RoomSocketManager? = null
    private var pollJob: Job? = null
    private var searchJob: Job? = null

    // Track pending play when no device is selected yet
    private var pendingSong: Song? = null
    private var pendingPlayUrl: String? = null
    private var wasPlaying = false

    // Persisted across Socket.IO reconnects: URL of whatever was last pushed to DLNA
    private val prefs = context.getSharedPreferences("bilising_play_state", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "PlayController"
        private const val KEY_PUSHED_SONG_URL = "pushed_song_url"
    }

    fun start() {
        connectSocket()
        searchDevices()
    }

    fun searchDevices() {
        searchJob?.cancel()
        searchJob = scope.launch {
            updateState { copy(isSearchingDevices = true, dlnaDevices = emptyList()) }
            try {
                dlnaManager.discoverDevices { device ->
                    updateState {
                        if (dlnaDevices.any { it.controlUrl == device.controlUrl }) this
                        else copy(dlnaDevices = dlnaDevices + device)
                    }
                }
            } catch (e: CancellationException) {
                // 用户选择了设备，正常取消
            } catch (e: Exception) {
                Log.e(TAG, "Device discovery failed", e)
                updateState { copy(errorMessage = "设备搜索失败: ${e.message}") }
            } finally {
                updateState { copy(isSearchingDevices = false) }
            }
        }
    }

    fun selectDevice(device: DlnaDevice) {
        searchJob?.cancel() // 用户已选设备，停止继续扫描
        searchJob = null
        updateState { copy(selectedDevice = device, errorMessage = "") }

        // If there's a pending play (URL was fetched but no device was selected), push now
        val url = pendingPlayUrl
        val song = pendingSong
        if (url != null && song != null) {
            pendingPlayUrl = null
            pendingSong = null
            scope.launch { pushToDevice(device, song, url) }
        }
    }

    fun sendNextSong() {
        socketManager?.sendNextSong()
    }

    fun addDeviceByIp(ip: String) {
        scope.launch {
            updateState { copy(isSearchingDevices = true, errorMessage = "") }
            try {
                val device = dlnaManager.addDeviceByIp(ip)
                if (device != null) {
                    updateState { copy(dlnaDevices = dlnaDevices + device, isSearchingDevices = false) }
                } else {
                    updateState { copy(isSearchingDevices = false, errorMessage = "未能在 $ip 找到DLNA设备，请确认IP和端口") }
                }
            } catch (e: Exception) {
                updateState { copy(isSearchingDevices = false, errorMessage = "连接失败: ${e.message}") }
            }
        }
    }

    private fun connectSocket() {
        socketManager = RoomSocketManager(
            serverUrl = serverUrl,
            roomId = roomId,
            onConnected = {
                updateState { copy(connectionState = ConnectionState.CONNECTED) }
            },
            onDisconnected = {
                updateState { copy(connectionState = ConnectionState.DISCONNECTED) }
            },
            onRoomJoined = { currentPlaying, playList ->
                updateState { copy(nextSong = playList.firstOrNull()) }
                if (currentPlaying != null) {
                    playSong(currentPlaying)
                }
            },
            onNowPlaying = { song ->
                if (song != null) {
                    playSong(song)
                } else {
                    stopPolling()
                    updateState {
                        copy(
                            currentSong = null,
                            playbackState = PlaybackState.IDLE,
                            statusMessage = "暂无歌曲"
                        )
                    }
                }
            },
            onPlaylistUpdated = { playList ->
                updateState { copy(nextSong = playList.firstOrNull()) }
            },
            onPlaybackControl = { action ->
                handlePlaybackControl(action)
            },
            onError = { error ->
                updateState { copy(errorMessage = error) }
            }
        )
        socketManager?.connect()
    }

    private fun handlePlaybackControl(action: String) {
        val device = _uiState.value.selectedDevice ?: return
        scope.launch {
            try {
                when (action) {
                    "play_from_start" -> {
                        dlnaManager.seek(device, 0)
                        dlnaManager.play(device)
                    }
                    "play_pause" -> {
                        val state = dlnaManager.getTransportState(device)
                        if (state == "PLAYING" || state == "TRANSITIONING") {
                            dlnaManager.pause(device)
                        } else {
                            dlnaManager.play(device)
                        }
                    }
                    "volume_up" -> {
                        val v = dlnaManager.getVolume(device)
                        dlnaManager.setVolume(device, minOf(100, v + 10))
                    }
                    "volume_down" -> {
                        val v = dlnaManager.getVolume(device)
                        dlnaManager.setVolume(device, maxOf(0, v - 10))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed playback control: $action", e)
            }
        }
    }

    private fun playSong(song: Song) {
        // If the same song is already loaded on the DLNA device (e.g. after a Socket.IO
        // reconnect), don't restart playback from zero.
        val pushedUrl = prefs.getString(KEY_PUSHED_SONG_URL, null)
        val curState = _uiState.value.playbackState
        if (song.url == pushedUrl &&
            (curState == PlaybackState.PLAYING || curState == PlaybackState.PUSHING || curState == PlaybackState.FETCHING)) {
            Log.d(TAG, "Same song already on device, skipping restart: ${song.title}")
            return
        }

        stopPolling()
        pendingPlayUrl = null
        pendingSong = null

        updateState {
            copy(
                currentSong = song,
                playbackState = PlaybackState.FETCHING,
                statusMessage = "获取播放地址中...",
                errorMessage = ""
            )
        }

        scope.launch {
            try {
                val videoId = BiliParser.parse(song.url) ?: run {
                    handlePlayError("无法解析视频URL", song)
                    return@launch
                }

                val videoInfo = try {
                    BiliPlayUrlApi.getVideoInfoWithPage(videoId.bvid, videoId.page)
                } catch (e: Exception) {
                    handlePlayError("获取视频信息失败: ${e.message}", song)
                    return@launch
                }

                val playUrl = try {
                    BiliPlayUrlApi.getPlayUrl(videoInfo.aid, videoInfo.cid)
                } catch (e: Exception) {
                    handlePlayError("获取播放地址失败: ${e.message}", song)
                    return@launch
                }

                val device = _uiState.value.selectedDevice
                if (device == null) {
                    // Save for when device is selected
                    pendingSong = song
                    pendingPlayUrl = playUrl
                    updateState { copy(statusMessage = "已获取播放地址，请选择DLNA设备") }
                    return@launch
                }

                pushToDevice(device, song, playUrl)
            } catch (e: Exception) {
                handlePlayError("播放失败: ${e.message}", song)
            }
        }
    }

    private suspend fun pushToDevice(device: DlnaDevice, song: Song, playUrl: String) {
        updateState { copy(playbackState = PlaybackState.PUSHING, statusMessage = "推送到设备中...") }
        try {
            try { dlnaManager.stop(device) } catch (e: Exception) { /* ignore */ }
            delay(500)
            dlnaManager.setAVTransportURI(device, playUrl, song.title)
            delay(500)
            dlnaManager.play(device)

            wasPlaying = false
            prefs.edit().putString(KEY_PUSHED_SONG_URL, song.url).apply()
            updateState { copy(playbackState = PlaybackState.PLAYING, statusMessage = "播放中", isPaused = false, currentPositionMs = 0L, durationMs = 0L) }
            startPolling(device)
        } catch (e: Exception) {
            handlePlayError("推送到设备失败: ${e.message}", song)
        }
    }

    private fun startPolling(device: DlnaDevice) {
        pollJob = scope.launch {
            while (true) {
                delay(2000)
                try {
                    val (transportState, posInfo) = withContext(Dispatchers.IO) {
                        val s = dlnaManager.getTransportState(device)
                        val p = try { dlnaManager.getPositionInfo(device) } catch (_: Exception) { null }
                        s to p
                    }
                    Log.d(TAG, "Transport state: $transportState pos=${posInfo?.positionMs}/${posInfo?.durationMs}")

                    if (posInfo != null && posInfo.durationMs > 0) {
                        updateState { copy(currentPositionMs = posInfo.positionMs, durationMs = posInfo.durationMs) }
                    }

                    when {
                        transportState == "PLAYING" || transportState == "PAUSED_PLAYBACK" -> wasPlaying = true
                        wasPlaying && (transportState == "STOPPED" || transportState == "NO_MEDIA_PRESENT") -> {
                            wasPlaying = false
                            prefs.edit().remove(KEY_PUSHED_SONG_URL).apply()
                            updateState { copy(playbackState = PlaybackState.STOPPED, statusMessage = "播放结束", currentPositionMs = 0L, durationMs = 0L) }
                            delay(1000)
                            socketManager?.sendNextSong()
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Polling error: ${e.message}")
                }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        wasPlaying = false
    }

    fun togglePause() {
        val device = _uiState.value.selectedDevice ?: return
        val paused = _uiState.value.isPaused
        scope.launch {
            try {
                if (paused) {
                    withContext(Dispatchers.IO) { dlnaManager.play(device) }
                    updateState { copy(isPaused = false, statusMessage = "播放中") }
                } else {
                    withContext(Dispatchers.IO) { dlnaManager.pause(device) }
                    updateState { copy(isPaused = true, statusMessage = "已暂停") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Toggle pause failed: ${e.message}")
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val device = _uiState.value.selectedDevice ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) { dlnaManager.seek(device, positionMs) }
                updateState { copy(currentPositionMs = positionMs) }
            } catch (e: Exception) {
                Log.e(TAG, "Seek failed: ${e.message}")
            }
        }
    }

    private suspend fun handlePlayError(message: String, song: Song) {
        Log.e(TAG, message)
        prefs.edit().remove(KEY_PUSHED_SONG_URL).apply()
        updateState { copy(playbackState = PlaybackState.ERROR, statusMessage = message, errorMessage = message) }
        delay(3000)
        socketManager?.sendNextSong()
    }

    private fun updateState(update: DlnaUiState.() -> DlnaUiState) {
        scope.launch(Dispatchers.Main) {
            val newState = _uiState.value.update()
            _uiState.value = newState
            // 同步更新前台 Service 通知文字
            if (newState.statusMessage.isNotBlank()) {
                updateServiceNotification(newState.statusMessage)
            }
        }
    }

    private fun updateServiceNotification(status: String) {
        try {
            val intent = Intent(context, DlnaForegroundService::class.java).apply {
                putExtra(DlnaForegroundService.EXTRA_STATUS, status)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (_: Exception) { /* Service 可能已停止，忽略 */ }
    }

    fun cleanup() {
        searchJob?.cancel()
        stopPolling()
        socketManager?.disconnect()
        scope.cancel()
    }
}
