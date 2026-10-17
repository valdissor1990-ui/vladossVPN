package com.vpnproxy.ui.servers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpnproxy.smartconnect.ScoringEngine.ScoredServer

@Composable
fun ServerGridCard(
    server: ScoredServer,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Если задан — показываем compositeScore / label из Feature 3. */
    scoreOverride: String? = null
) {
    val c = server.config
    val flag = extractFlag(c.name)
    val title = c.name.replace(flag, "").trim().ifBlank { c.name }
    val protocol = c.transport.uppercase().ifBlank { "VLESS" }
    val pingLabel = scoreOverride ?: when {
        server.latencyMs in 1..9998 -> "${server.latencyMs}ms"
        else -> "—"
    }
    val pingColor = when {
        pingLabel == "Slow" || pingLabel == "N/A" -> Color(0xFFE53935)
        pingLabel.endsWith("ms") -> {
            val ms = pingLabel.removeSuffix("ms").toLongOrNull() ?: 0L
            when {
                ms in 1 until 100 -> Color(0xFF43A047)
                ms in 100 until 300 -> Color(0xFFF9A825)
                ms >= 300 -> Color(0xFFE53935)
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            }
        }
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (flag.isNotBlank()) {
                    Text(text = flag, fontSize = 18.sp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${c.address}:${c.port}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = protocol,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = pingLabel,
                    color = pingColor,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/** Достаёт emoji-флаг из начала имени сервера, если есть. */
private fun extractFlag(name: String): String {
    if (name.isEmpty()) return ""
    val first = name.codePoints().findFirst().orElse(-1)
    if (first < 0) return ""
    val isRi = first in 0x1F1E6..0x1F1FF
    if (!isRi) return ""
    val cps = name.codePoints().toArray()
    if (cps.size >= 2 && cps[1] in 0x1F1E6..0x1F1FF) {
        return String(cps, 0, 2)
    }
    return String(cps, 0, 1)
}
