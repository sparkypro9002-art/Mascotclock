package com.hotaro.mascot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hotaro.mascot.ui.theme.*
import kotlin.math.roundToInt

@Composable
fun AlarmVolumeControls(
    volumePercent: Int,
    onVolumeChange: (Int) -> Unit,
    lockVolumeWhileRinging: Boolean,
    onLockVolumeChange: (Boolean) -> Unit
) {
    Surface(shape = RoundedCornerShape(16.dp), color = surfaceContainerHighDark, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (volumePercent == 0) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp, null, tint = primaryDark)
                    Spacer(Modifier.width(12.dp))
                    Text("Alarm Volume", color = onSurfaceDark, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Text("$volumePercent%", color = primaryDark, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Slider(
                value = volumePercent / 100f,
                onValueChange = { onVolumeChange((it * 100).roundToInt().coerceIn(0, 100)) },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(thumbColor = primaryDark, activeTrackColor = primaryDark)
            )
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = outlineVariantDark.copy(alpha = 0.5f))
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Lock, null, tint = primaryDark)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Lock Volume While Ringing", color = onSurfaceDark, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("Cannot reduce volume while alarm rings", color = onSurfaceVariantDark, fontSize = 13.sp)
                    }
                }
                Switch(checked = lockVolumeWhileRinging, onCheckedChange = onLockVolumeChange)
            }
        }
    }
}
