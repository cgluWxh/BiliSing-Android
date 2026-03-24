package cgluwxh.bilising

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cgluwxh.bilising.ui.DlnaPlayScreen
import cgluwxh.bilising.ui.HomeScreen
import cgluwxh.bilising.ui.WebViewScreen
import cgluwxh.bilising.ui.theme.BiliSingTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URL

private enum class Screen { HOME, WEBVIEW, DLNA }

class MainActivity : ComponentActivity() {
    private var userScript: String = ""
    private lateinit var sharedPrefs: SharedPreferences

    companion object {
        private const val PREFS_NAME = "bilising_prefs"
        private const val KEY_SCRIPT_URL = "script_url"
        private const val DEFAULT_SCRIPT_URL = "sing.bilibiili.com"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setContent {
            BiliSingTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                var screen by remember { mutableStateOf(Screen.HOME) }
                var roomId by remember { mutableStateOf("") }
                var serverUrl by remember { mutableStateOf("") }
                var errorMessage by remember { mutableStateOf("") }

                when (screen) {
                    Screen.HOME -> HomeScreen(
                        errorMessage = errorMessage,
                        savedScriptUrl = sharedPrefs.getString(KEY_SCRIPT_URL, DEFAULT_SCRIPT_URL)
                            ?: DEFAULT_SCRIPT_URL,
                        onNavigateToWebView = { scriptUrl, room ->
                            roomId = room
                            serverUrl = scriptUrl
                            errorMessage = ""
                            sharedPrefs.edit().putString(KEY_SCRIPT_URL, scriptUrl).apply()
                            MainScope().launch {
                                try {
                                    userScript = downloadScript("https://$scriptUrl/static/bilising.user.js")
                                    screen = Screen.WEBVIEW
                                } catch (e: Exception) {
                                    errorMessage = "下载脚本失败: ${e.message}"
                                }
                            }
                        },
                        onNavigateToDlna = { scriptUrl, room ->
                            serverUrl = scriptUrl
                            roomId = room
                            errorMessage = ""
                            sharedPrefs.edit().putString(KEY_SCRIPT_URL, scriptUrl).apply()
                            screen = Screen.DLNA
                        }
                    )

                    Screen.WEBVIEW -> WebViewScreen(
                        roomId = roomId,
                        userScript = userScript,
                        serverUrl = serverUrl
                    )

                    Screen.DLNA -> DlnaPlayScreen(
                        serverUrl = serverUrl,
                        roomId = roomId,
                        onNavigateBack = {
                            errorMessage = ""
                            screen = Screen.HOME
                        }
                    )
                }
                } // Surface
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupFullScreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setupFullScreen()
    }

    private fun setupFullScreen() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window?.insetsController?.let { controller ->
                    controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window?.decorView?.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
            }
            window?.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window?.attributes?.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun downloadScript(url: String): String {
        return withContext(Dispatchers.IO) {
            try {
                URL(url).readText()
            } catch (e: IOException) {
                throw IOException("无法下载脚本: ${e.message}")
            }
        }
    }
}
