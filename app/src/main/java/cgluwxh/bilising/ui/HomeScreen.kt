package cgluwxh.bilising.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    errorMessage: String,
    savedScriptUrl: String,
    onNavigateToWebView: (String, String) -> Unit,
    onNavigateToDlna: (String, String) -> Unit
) {
    var scriptUrl by remember { mutableStateOf(savedScriptUrl) }
    var roomId by remember { mutableStateOf("") }
    var isLoadingWebView by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "BiliSing",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "哔哩哔哩投屏控制工具",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        OutlinedTextField(
            value = scriptUrl,
            onValueChange = {
                scriptUrl = it
                if (isLoadingWebView) isLoadingWebView = false
            },
            label = { Text("服务器地址") },
            placeholder = { Text("example.com") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            enabled = !isLoadingWebView,
            singleLine = true
        )

        OutlinedTextField(
            value = roomId,
            onValueChange = {
                roomId = it
                if (isLoadingWebView) isLoadingWebView = false
            },
            label = { Text("房间号码") },
            placeholder = { Text("输入房间ID") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            enabled = !isLoadingWebView,
            singleLine = true
        )

        if (errorMessage.isNotBlank()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        val inputReady = !isLoadingWebView && scriptUrl.isNotBlank() && roomId.isNotBlank()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 本机播放
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = {
                        if (inputReady) {
                            isLoadingWebView = true
                            onNavigateToWebView(scriptUrl, roomId)
                        }
                    },
                    enabled = inputReady,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isLoadingWebView) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("加载中...")
                    } else {
                        Text("本机播放")
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "本机直接作为播放设备",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            // 投屏播放
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                OutlinedButton(
                    onClick = {
                        if (scriptUrl.isNotBlank() && roomId.isNotBlank()) {
                            onNavigateToDlna(scriptUrl, roomId)
                        }
                    },
                    enabled = scriptUrl.isNotBlank() && roomId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("投屏播放")
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "通过DLNA推送到大屏",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
