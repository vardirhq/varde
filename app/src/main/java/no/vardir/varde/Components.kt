package no.vardir.varde

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Horizontal gutter shared by every screen. */
internal val ScreenGutter = 20.dp
internal val CardShape = RoundedCornerShape(16.dp)

/** The single card style in Varde. Clickable when [onClick] is set. */
@Composable
internal fun VardeCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val color = MaterialTheme.colorScheme.surfaceContainer
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(contentPadding), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
    if (onClick == null) Surface(modifier.fillMaxWidth(), shape = CardShape, color = color, border = border, content = body)
    else Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = CardShape, color = color, border = border, content = body)
}

private data class ToneStyle(val content: Color, val container: Color, val icon: ImageVector)

@Composable
private fun toneStyle(tone: StatusTone): ToneStyle {
    val c = VardeTheme.colors
    return when (tone) {
        StatusTone.Success -> ToneStyle(c.success, c.successContainer, Icons.Default.CheckCircle)
        StatusTone.Failure -> ToneStyle(c.danger, c.dangerContainer, Icons.Default.Warning)
        StatusTone.Pending -> ToneStyle(c.pending, c.pendingContainer, Icons.Default.Refresh)
        StatusTone.Neutral -> ToneStyle(c.neutral, c.neutralContainer, Icons.Default.Clear)
    }
}

/** Icon, color and text together, so status never depends on color alone. */
@Composable
internal fun StatusBadge(status: StatusLabel, modifier: Modifier = Modifier) {
    val style = toneStyle(status.tone)
    Surface(modifier, shape = RoundedCornerShape(50), color = style.container, contentColor = style.content) {
        Row(Modifier.padding(start = 8.dp, end = 10.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(style.icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(status.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Quiet metadata label such as "Private" or a group name. */
@Composable
internal fun Tag(text: String, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    Surface(modifier, shape = RoundedCornerShape(50),
        color = if (emphasized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** Title block at the top of a tab. */
@Composable
internal fun ScreenTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Top bar for screens below a tab: back, an optional eyebrow and title, and trailing actions. */
@Composable
internal fun DetailTopBar(
    title: String, onBack: () -> Unit, eyebrow: String? = null, subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Spacer(Modifier.weight(1f))
            actions()
        }
        Column(Modifier.padding(horizontal = ScreenGutter - 4.dp)) {
            if (eyebrow != null) Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (subtitle != null) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

internal enum class NoticeKind { Info, Warning, Error }

/** Inline message: empty states, offline copies, partial data and errors. */
@Composable
internal fun Notice(title: String, message: String? = null, kind: NoticeKind = NoticeKind.Info, modifier: Modifier = Modifier) {
    val colors = VardeTheme.colors
    val (container, accent, icon) = when (kind) {
        NoticeKind.Info -> Triple(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant, Icons.Default.Info)
        NoticeKind.Warning -> Triple(colors.pendingContainer, colors.pending, Icons.Default.Warning)
        NoticeKind.Error -> Triple(colors.dangerContainer, colors.danger, Icons.Default.Warning)
    }
    Surface(modifier.fillMaxWidth(), shape = CardShape, color = container) {
        Row(Modifier.padding(14.dp)) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = if (kind == NoticeKind.Info) MaterialTheme.colorScheme.onSurface else accent)
                if (!message.isNullOrBlank()) Text(message, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A list row inside a card, optionally navigable, with an optional status badge. */
@Composable
internal fun NavRow(title: String, subtitle: String = "", status: StatusLabel? = null, onClick: (() -> Unit)? = null) {
    VardeCard(onClick = onClick, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (status != null || subtitle.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                    if (status != null) StatusBadge(status)
                    if (status != null && subtitle.isNotBlank()) Spacer(Modifier.width(8.dp))
                    if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (onClick != null) Chevron()
        }
    }
}

@Composable
internal fun Chevron() {
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
}
