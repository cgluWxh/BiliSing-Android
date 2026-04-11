package cgluwxh.bilising.socket

import android.util.Log
import cgluwxh.bilising.model.Song
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.URI

class RoomSocketManager(
    private val serverUrl: String,
    private val roomId: String,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onRoomJoined: (Song?, List<Song>) -> Unit,
    private val onNowPlaying: (Song?) -> Unit,
    private val onPlaylistUpdated: (List<Song>) -> Unit,
    private val onPlaybackControl: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private var socket: Socket? = null

    companion object {
        private const val TAG = "RoomSocketManager"
    }

    fun connect() {
        try {
            val options = IO.Options.builder()
                .setTransports(arrayOf("websocket"))
                .build()

            socket = IO.socket(URI.create("https://$serverUrl"), options).apply {
                on(Socket.EVENT_CONNECT) {
                    Log.d(TAG, "Connected")
                    onConnected()
                    joinRoom()
                }

                on(Socket.EVENT_DISCONNECT) {
                    Log.d(TAG, "Disconnected")
                    onDisconnected()
                }

                on(Socket.EVENT_CONNECT_ERROR) { args ->
                    val error = args.firstOrNull()?.toString() ?: "Unknown error"
                    Log.e(TAG, "Connect error: $error")
                    onError("连接失败: $error")
                }

                on("room_joined") { args ->
                    try {
                        val data = args[0] as JSONObject
                        val currentPlaying = data.optJSONObject("current_playing")?.let { parseSong(it) }
                        val playList = data.optJSONArray("play_list")?.let { arr ->
                            (0 until arr.length()).map { parseSong(arr.getJSONObject(it)) }
                        } ?: emptyList()
                        onRoomJoined(currentPlaying, playList)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing room_joined", e)
                    }
                }

                on("now_playing") { args ->
                    try {
                        val data = args[0] as JSONObject
                        val song = data.optJSONObject("current_playing")?.let { parseSong(it) }
                        onNowPlaying(song)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing now_playing", e)
                    }
                }

                on("playlist_updated") { args ->
                    try {
                        val data = args[0] as JSONObject
                        val playList = data.optJSONArray("play_list")?.let { arr ->
                            (0 until arr.length()).map { parseSong(arr.getJSONObject(it)) }
                        } ?: emptyList()
                        onPlaylistUpdated(playList)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing playlist_updated", e)
                    }
                }

                on("playback_control") { args ->
                    try {
                        val data = args[0] as JSONObject
                        val action = data.optString("action")
                        onPlaybackControl(action)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing playback_control", e)
                    }
                }

                on("error") { args ->
                    val data = args.firstOrNull() as? JSONObject
                    val message = data?.optString("message") ?: "Unknown error"
                    onError(message)
                }

                connect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect", e)
            onError("连接失败: ${e.message}")
        }
    }

    private fun joinRoom() {
        val data = JSONObject().apply {
            put("room_id", roomId)
            put("user_name", "播放设备")
            put("user_type", "master")
        }
        socket?.emit("join_room", data)
    }

    fun sendNextSong() {
        val data = JSONObject().apply {
            put("room_id", roomId)
            put("user_name", "播放设备")
        }
        socket?.emit("next_song", data)
        Log.d(TAG, "Sent next_song")
    }

    fun disconnect() {
        socket?.off()
        socket?.disconnect()
        socket = null
    }

    private fun parseSong(json: JSONObject) = Song(
        title = json.optString("title", ""),
        producer = json.optString("producer", ""),
        url = json.optString("url", ""),
        user_name = json.optString("by", "")
    )
}
