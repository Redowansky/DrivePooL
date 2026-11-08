package app.pepper.drivepool.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pepper.drivepool.data.ACCOUNT_COLORS
import app.pepper.drivepool.data.DriveAccount
import app.pepper.drivepool.data.Updater
import app.pepper.drivepool.data.formatGb
import app.pepper.drivepool.ui.theme.CardBg
import app.pepper.drivepool.ui.theme.CardBorder
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun DashboardScreen(
    accounts: List<DriveAccount>,
    error: String?,
    hint: String?,
    updateInfo: Updater.UpdateInfo?,
    downloadState: Updater.DownloadState,
    onUpdate: () -> Unit,
    onAddDrive: () -> Unit,
    onRefresh: () -> Unit,
    onRemoveAccount: (String) -> Unit
) {
    val totalUsed = accounts.sumOf { it.usedBytes }
    val totalLimit = accounts.sumOf { it.limitBytes }
    val totalFree = accounts.sumOf { it.freeBytes }
    val totalFiles = accounts.sumOf { it.fileCount }
    val todayBytes = accounts.sumOf { it.todayBytes }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B0F))
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Top bar — own DrivePool branding.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Cloud, contentDescription = null, tint = Color(0xFF4A90E2), modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(8.dp))
            Text("DrivePool", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = Color.White)
            }
            Button(
                onClick = onAddDrive,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                shape = RoundedCornerShape(24.dp)
            ) { Text("Add Drive", fontWeight = FontWeight.Bold) }
        }

        if (updateInfo != null) {
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF12351F))
                    .padding(14.dp)
            ) {
                Text("Update available — v${updateInfo.versionName}", color = Color.White, fontWeight = FontWeight.Bold)
                Text(updateInfo.notes, color = Color(0xFF9A9AA5), fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                when (val s = downloadState) {
                    is Updater.DownloadState.Progress -> {
                        val frac = if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else 0f
                        GradientBar(fraction = frac, colors = listOf(Color(0xFF4ADE80)))
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Downloading… ${formatGb(s.done)} / ${if (s.total > 0) formatGb(s.total) else "?"}",
                            color = Color(0xFF9A9AA5), fontSize = 13.sp
                        )
                    }
                    is Updater.DownloadState.Failed -> {
                        Text("Download failed: ${s.reason}", color = Color(0xFFEF5350), fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onUpdate, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A90E2))) {
                            Text("Retry", fontWeight = FontWeight.Bold)
                        }
                    }
                    Updater.DownloadState.Done -> {
                        Text("Download finished — the installer should open. If not, tap Update again.", color = Color(0xFF9A9AA5), fontSize = 13.sp)
                    }
                    else -> {
                        Button(onClick = onUpdate, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4ADE80), contentColor = Color.Black)) {
                            Text("Update", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error, color = Color(0xFFEF5350), fontSize = 13.sp)
        }
        if (hint != null) {
            Spacer(Modifier.height(12.dp))
            Text(hint, color = Color(0xFF9A9AA5), fontSize = 13.sp)
        }

        Spacer(Modifier.height(16.dp))

        if (accounts.isEmpty()) {
            Orbit()
            Spacer(Modifier.height(16.dp))
            Text("Add your first Drive", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Tap Add Drive at the top, pick a Google account, and grant Drive read-only access. That's it — quotas are read from Google Drive and pooled here.", color = Color(0xFF9A9AA5), fontSize = 14.sp)
        } else {
            accounts.forEachIndexed { index, account ->
                AccountCard(account, index, onRemoveAccount)
                Spacer(Modifier.height(12.dp))
            }

            Orbit()
            Spacer(Modifier.height(16.dp))

            // Total pooled card.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(CardBg)
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("DrivePool", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Unified dashboard · all drives", color = Color(0xFF9A9AA5), fontSize = 13.sp)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF12351F))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF4ADE80)))
                        Spacer(Modifier.width(6.dp))
                        Text("Live Syncing", color = Color(0xFF4ADE80), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    Text(formatGb(totalUsed), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text("/ ${formatGb(totalLimit)}", color = Color(0xFF9A9AA5), fontSize = 18.sp, modifier = Modifier.align(Alignment.Bottom))
                }
                Spacer(Modifier.height(10.dp))
                GradientBar(
                    fraction = if (totalLimit > 0) totalUsed.toFloat() / totalLimit else 0f,
                    colors = listOf(Color(0xFF4A90E2), Color(0xFF4ADE80), Color(0xFFFACC15))
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("TODAY", "+${formatGb(todayBytes)}", Modifier.weight(1f))
                    StatTile("ACCOUNTS", "${accounts.size} linked", Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("FILES", "$totalFiles", Modifier.weight(1f))
                    StatTile("FREE", formatGb(totalFree), Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccountCard(account: DriveAccount, index: Int, onRemove: (String) -> Unit) {
    val color = Color(ACCOUNT_COLORS[index % ACCOUNT_COLORS.size])
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardBg)
            .combinedClickable(onClick = {}, onLongClick = { onRemove(account.email) })
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF2A2A32)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Cloud, contentDescription = null, tint = color) }
            Spacer(Modifier.width(10.dp))
            Text(account.email, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        GradientBar(fraction = account.usedFraction, colors = listOf(color))
        Spacer(Modifier.height(8.dp))
        Row {
            Text("${formatGb(account.usedBytes)} / ${formatGb(account.limitBytes)}", color = Color(0xFF9A9AA5), fontSize = 13.sp, modifier = Modifier.weight(1f))
            Box(Modifier.size(8.dp).clip(CircleShape).background(color).align(Alignment.CenterVertically))
            Spacer(Modifier.width(6.dp))
            Text(account.status, color = Color(0xFF9A9AA5), fontSize = 13.sp)
        }
    }
}

@Composable
private fun GradientBar(fraction: Float, colors: List<Color>) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF2E2E36))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (colors.size > 1) Brush.horizontalGradient(colors) else Brush.horizontalGradient(listOf(colors[0], colors[0])))
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF121216))
            .padding(14.dp)
    ) {
        Text(label, color = Color(0xFF9A9AA5), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(value, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Orbit() {
    val transition = rememberInfiniteTransition(label = "orbit")
    val angle by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "angle"
    )
    Box(
        modifier = Modifier.fillMaxWidth().height(170.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(170.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            drawCircle(Color(0xFF2A2A32), radius = 78f, style = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
            drawCircle(Color(0xFF2A2A32), radius = 52f, style = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
            drawCircle(Color(0xFFEC4899), radius = 26f, center = c)
            drawCircle(Color(0xFF4A90E2), radius = 14f, center = c)
            drawCircle(Color.White, radius = 5f, center = c)
            val dots = listOf(
                Triple(78f, angle, Color(0xFF4A90E2)),
                Triple(78f, angle + 140f, Color(0xFF4ADE80)),
                Triple(52f, -angle + 60f, Color(0xFFEC4899))
            )
            for ((r, a, col) in dots) {
                val rad = Math.toRadians(a.toDouble())
                drawCircle(col, radius = 6f, center = Offset(c.x + (r * cos(rad)).toFloat(), c.y + (r * sin(rad)).toFloat()))
            }
        }
    }
}
