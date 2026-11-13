package com.hotaro.strictclock

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun AlarmVolumeControls(
    volumePercent: Int,
    onVolumeChange: (Int) -> Unit,
    lockVolumeWhileRinging: Boolean,
    onLockVolumeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (volumePercent == 0)
                            Icons.AutoMirrored.Filled.VolumeOff
                        else
                            Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Alarm Volume",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Alarm Volume",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Text(
                    text = "$volumePercent%",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Slider(
                value = volumePercent / 100f,
                onValueChange = { fraction ->
                    onVolumeChange((fraction * 100).roundToInt().coerceIn(0, 100))
                },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Lock Volume",
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Lock Volume While Ringing",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Block volume reduction until alarm is dismissed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = lockVolumeWhileRinging,
                    onCheckedChange = onLockVolumeChange
                )
            }
        }
    }
}
