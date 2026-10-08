package com.cnanjappa.inventory.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cnanjappa.inventory.R
import com.cnanjappa.inventory.data.Variant
import com.cnanjappa.inventory.domain.MAX_QTY
import com.cnanjappa.inventory.domain.Validate

// ---------------- Theme ----------------

/** Inter (SIL OFL) bundled as one variable font, so the look is identical on every phone and offline. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun inter(weight: Int) = Font(
    R.font.inter, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)
val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))

private val Ink = Color(0xFF141A24)
private val Muted = Color(0xFF5B6472)
private val Primary = Color(0xFF1D4ED8)
private val Hairline = Color(0xFFE3E7ED)
val SuccessGreen = Color(0xFF15803D)
val SuccessGreenDark = Color(0xFF6EDC94)

private val Light: ColorScheme = lightColorScheme(
    primary = Primary, onPrimary = Color.White,
    primaryContainer = Color(0xFFE6EDFC), onPrimaryContainer = Color(0xFF12307F),
    secondaryContainer = Color(0xFFEEF1F5), onSecondaryContainer = Ink,
    background = Color(0xFFF5F6F8), onBackground = Ink,
    surface = Color.White, onSurface = Ink,
    surfaceVariant = Color(0xFFF0F2F5), onSurfaceVariant = Muted,
    surfaceContainer = Color.White, surfaceContainerLow = Color(0xFFF8F9FB),
    outline = Color(0xFFCDD3DC), outlineVariant = Hairline,
    error = Color(0xFFB42318),
)
private val Dark: ColorScheme = darkColorScheme(
    primary = Color(0xFF9DB8F7), onPrimary = Color(0xFF0B2160),
    primaryContainer = Color(0xFF1F3A85), onPrimaryContainer = Color(0xFFDCE6FD),
    background = Color(0xFF0E1116), surface = Color(0xFF171B22), surfaceContainer = Color(0xFF171B22),
    surfaceVariant = Color(0xFF222832), outlineVariant = Color(0xFF2A313C),
)

private fun type(): Typography {
    val base = TextStyle(fontFamily = Inter, letterSpacing = 0.sp)
    return Typography(
        headlineLarge = base.copy(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = base.copy(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        headlineSmall = base.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleLarge = base.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = base.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = base.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        bodyLarge = base.copy(fontSize = 17.sp, lineHeight = 24.sp),
        bodyMedium = base.copy(fontSize = 15.sp, lineHeight = 21.sp),
        bodySmall = base.copy(fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = base.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = base.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
        labelSmall = base.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    )
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = remember { type() }, content = content)
}

@Composable
fun successColor() = if (isSystemInDarkTheme()) SuccessGreenDark else SuccessGreen

val CardShape = RoundedCornerShape(20.dp)
private val ButtonShape = RoundedCornerShape(16.dp)
private const val PRESS_MS = 120

/** Subtle press feedback (slight scale) shared by tappable surfaces; follows system animation scale. */
@Composable
private fun pressScale(source: MutableInteractionSource): Float {
    val pressed by source.collectIsPressedAsState()
    return animateFloatAsState(if (pressed) 0.98f else 1f, tween(PRESS_MS), label = "press").value
}

// ---------------- Surfaces ----------------

/** White card with a hairline border: the basic grouping surface on every screen. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

/** Icon in a soft tinted circle, used on tiles and headers. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color, background: Color, size: Int = 48) {
    Box(Modifier.size(size.dp).background(background, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.5f).dp))
    }
}

// ---------------- Buttons and choices ----------------

/** Large full-width action. The whole surface (text, icon and edges) is one touch target. */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    container: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onPrimary,
) {
    val source = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = source,
        modifier = modifier.fillMaxWidth().heightIn(min = 58.dp).scale(pressScale(source)),
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = source,
        modifier = modifier.heightIn(min = 50.dp).scale(pressScale(source)),
        shape = ButtonShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/** Selection pill: changes a choice only, never stock. Selected = tinted fill, primary border and a check. */
@Composable
fun ChoiceButton(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, swatch: Color? = null) {
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) scheme.primaryContainer else scheme.surface, tween(PRESS_MS), label = "chipBg")
    val source = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = source,
        modifier = modifier.sizeIn(minWidth = 56.dp, minHeight = 48.dp).scale(pressScale(source)).semantics { this.selected = selected },
        shape = RoundedCornerShape(14.dp),
        color = bg,
        contentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                swatch != null -> {
                    Box(Modifier.size(18.dp).background(swatch, CircleShape).border(1.dp, Color(0xFF9AA3AF), CircleShape))
                    Spacer(Modifier.width(8.dp))
                }
                selected -> {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp), tint = scheme.primary)
                    Spacer(Modifier.width(6.dp))
                }
            }
            Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/**
 * Large −/+ draft quantity. Changes only the draft number; the labelled action button commits.
 * Tapping the number allows typing a large quantity.
 */
@Composable
fun QtyStepper(value: Int, onChange: (Int) -> Unit, min: Int = 0, max: Int = MAX_QTY, label: String? = null) {
    var typing by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null) Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                StepButton(Icons.Default.Remove, "Less", value > min) { onChange(value - 1) }
                TextButton(onClick = { typing = true }, modifier = Modifier.widthIn(min = 104.dp).heightIn(min = 60.dp)) {
                    Text("$value", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
                }
                StepButton(Icons.Default.Add, "More", value < max) { onChange(value + 1) }
            }
        }
    }
    if (typing) {
        var text by rememberSaveable { mutableStateOf(value.toString()) }
        val parsed = Validate.quantity(text, min)?.takeIf { it <= max }
        AlertDialog(
            onDismissRequest = { typing = false },
            title = { Text("Enter quantity") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(7) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = parsed == null,
                    supportingText = { if (parsed == null) Text("Whole number from $min") },
                )
            },
            confirmButton = { TextButton(enabled = parsed != null, onClick = { onChange(parsed!!); typing = false }) { Text("Use") } },
            dismissButton = { TextButton(onClick = { typing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StepButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick, enabled = enabled, interactionSource = source,
        modifier = Modifier.size(60.dp).scale(pressScale(source)),
        shape = RoundedCornerShape(14.dp),
        color = if (enabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon, contentDescription = label, modifier = Modifier.size(28.dp),
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
        }
    }
}

// ---------------- Messages ----------------

enum class Tone { SUCCESS, ERROR, INFO }

data class Msg(val tone: Tone, val text: String, val detail: String? = null)

/** Inline feedback announced to accessibility services. Colour is never the only signal: icon + words. */
@Composable
fun MessageCard(msg: Msg, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    val (accent, icon) = when (msg.tone) {
        Tone.SUCCESS -> successColor() to Icons.Default.CheckCircle
        Tone.ERROR -> scheme.error to Icons.Default.ErrorOutline
        Tone.INFO -> scheme.primary to Icons.Default.Info
    }
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = CardShape,
        color = accent.copy(alpha = 0.07f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(10.dp))
                Text(msg.text, color = accent, style = MaterialTheme.typography.titleLarge)
            }
            if (msg.detail != null) Text(msg.detail, style = MaterialTheme.typography.bodyLarge)
            actions()
        }
    }
}

@Composable
fun FieldHint(text: String?) {
    if (text != null) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = modifier.padding(top = 4.dp))
}

/** Product identity with a prominent stock figure; used at the top of stock screens. */
@Composable
fun VariantHeader(v: Variant, showStock: Boolean = true) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (v.company.isNotEmpty()) Text(v.company.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
            Text(listOf(v.name, v.model).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.headlineSmall)
            Text(v.detail, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (v.archived) Text("Archived", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
        }
        if (showStock) Row(verticalAlignment = Alignment.Bottom) {
            Text("${v.qty}", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 40.sp, lineHeight = 44.sp))
            Spacer(Modifier.width(8.dp))
            Text(if (v.qty == 1) "piece left" else "pieces left", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

fun piecesLeft(n: Int) = if (n == 1) "1 piece left" else "$n pieces left"
fun pieces(n: Int) = if (n == 1) "1 piece" else "$n pieces"

/** Simple colour swatches for common names; unknown names show no swatch. */
fun swatchFor(name: String): Color? = when (name.trim().lowercase()) {
    "white" -> Color.White
    "cream" -> Color(0xFFF3E9D2)
    "black" -> Color.Black
    "blue" -> Color(0xFF1E4FA8)
    "navy", "navy blue" -> Color(0xFF1B2A4A)
    "red" -> Color(0xFFC62828)
    "maroon" -> Color(0xFF6D1B2A)
    "green" -> Color(0xFF2E7D32)
    "olive" -> Color(0xFF708238)
    "grey", "gray" -> Color(0xFF9E9E9E)
    "yellow" -> Color(0xFFF9D648)
    "brown" -> Color(0xFF6D4C41)
    "pink" -> Color(0xFFF2A2B8)
    "sandal", "beige" -> Color(0xFFE3CFA6)
    "gold", "golden" -> Color(0xFFD4AF37)
    else -> null
}
