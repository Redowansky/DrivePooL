// DrivePool — Copyright (c) 2026 Redowansky
// Licensed under the PolyForm Noncommercial License 1.0.0.
// See LICENSE in the repository root. Commercial use requires permission.

package app.pepper.drivepool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pepper.drivepool.ui.theme.CardBg

/** Settings (v0.11.0): a plain screen, opened from the gear icon
 *  on the dashboard. First setting: where "Save to device" puts
 *  files — any folder on the phone, not just Downloads. */
@Composable
fun SettingsScreen(
    downloadFolderName: String?,
    onPickDownloadFolder: () -> Unit,
    onResetDownloadFolder: () -> Unit,
    stagedVersionName: String? = null,
    onInstallStaged: () -> Unit = {},
    versionName: String,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B0F))
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                "Settings", color = Color.White, fontSize = 20.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(16.dp))

        Text(
            "Downloads", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CardBg)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Folder, contentDescription = null, tint = Color(0xFF4A90E2))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Download folder", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(
                        downloadFolderName ?: "Downloads",
                        color = Color(0xFF9A9AA5), fontSize = 14.sp
                    )
                }
                Button(
                    onClick = onPickDownloadFolder,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A90E2))
                ) { Text("Change", fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Files you save to your device go into this folder. " +
                    "Pick any folder on your phone — including an SD card. " +
                    "If the folder can't be opened anymore, saving falls " +
                    "back to telling you to pick it again.",
                color = Color(0xFF9A9AA5), fontSize = 14.sp
            )
            if (downloadFolderName != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onResetDownloadFolder) {
                    Text("Use Downloads instead", color = Color(0xFF4A90E2))
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "Updates", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CardBg)
                .padding(16.dp)
        ) {
            Text("Silent updater", color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Checks for updates in the background and installs " +
                    "them itself — no tap at all, the F-Droid way. " +
                    "The very first update may still ask once; after " +
                    "that, new versions just land.",
                color = Color(0xFF9A9AA5), fontSize = 14.sp
            )
            if (stagedVersionName != null) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onInstallStaged,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A90E2))
                ) { Text("Install v$stagedVersionName now", fontWeight = FontWeight.Bold) }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "DrivePool v$versionName",
            color = Color(0xFF9A9AA5), fontSize = 13.sp
        )
    }
}
