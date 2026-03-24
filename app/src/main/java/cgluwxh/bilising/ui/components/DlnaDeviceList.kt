package cgluwxh.bilising.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cgluwxh.bilising.dlna.DlnaDevice

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DlnaDeviceList(
    devices: List<DlnaDevice>,
    selectedDevice: DlnaDevice?,
    isSearching: Boolean,
    onDeviceSelected: (DlnaDevice) -> Unit,
    onRefresh: () -> Unit,
    onManualIpAdd: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showIpDialog by remember { mutableStateOf(false) }
    var ipInput by remember { mutableStateOf("") }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "选择投屏设备",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            if (isSearching) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                // Long press = manual IP input, short press = refresh
                Text(
                    text = "刷新",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .combinedClickable(
                            onClick = onRefresh,
                            onLongClick = { showIpDialog = true }
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        Text(
            text = "长按 <刷新> 可手动输入设备IP",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        if (!isSearching && devices.isEmpty()) {
            Text(
                text = "未发现设备，请确认与大屏在同一WiFi下",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        devices.forEach { device ->
            val isSelected = device == selectedDevice
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onDeviceSelected(device) },
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = device.ipAddress,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showIpDialog) {
        AlertDialog(
            onDismissRequest = { showIpDialog = false; ipInput = "" },
            title = { Text("手动添加DLNA设备") },
            text = {
                Column {
                    Text(
                        text = "输入设备IP地址，如不知端口可只填IP，将自动扫描常用端口",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = ipInput,
                        onValueChange = { ipInput = it },
                        label = { Text("IP地址") },
                        placeholder = { Text("192.168.1.100 或 192.168.1.100:1234") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (ipInput.isNotBlank()) {
                            onManualIpAdd(ipInput.trim())
                            showIpDialog = false
                            ipInput = ""
                        }
                    },
                    enabled = ipInput.isNotBlank()
                ) { Text("连接") }
            },
            dismissButton = {
                TextButton(onClick = { showIpDialog = false; ipInput = "" }) { Text("取消") }
            }
        )
    }
}
