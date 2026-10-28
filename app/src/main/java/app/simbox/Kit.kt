package app.simbox

import android.content.ComponentName
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

// ---------- palette ----------

val Bg = Color(0xFF07080F)
val Panel = Color(0xFF12141F)
val Ink = Color(0xFFF4F5FA)
val Muted = Color(0xFF9AA0B4)
val Glass = Color(0x14FFFFFF)
val Line = Color(0x1FFFFFFF)
val Violet = Color(0xFF8B5CF6)
val Cyan = Color(0xFF22D3EE)
val Pink = Color(0xFFF472B6)
val Amber = Color(0xFFFBBF24)
val Emerald = Color(0xFF34D399)
val Coral = Color(0xFFFB7185)
val SimColors = listOf(Violet, Cyan, Pink, Amber, Emerald, Coral)

@Composable
fun SimboxTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = Violet, background = Bg, surface = Panel, onSurface = Ink),
) { CompositionLocalProvider(LocalContentColor provides Ink, content = content) }

fun Modifier.glass(r: Dp = 22.dp) = clip(RoundedCornerShape(r)).background(Glass).border(1.dp, Line, RoundedCornerShape(r))
fun Modifier.panel() = clip(RoundedCornerShape(28.dp)).background(Panel).border(1.dp, Line, RoundedCornerShape(28.dp))

// ---------- background: slow drifting aurora, palette follows the time of day ----------

@Composable
fun Aurora(content: @Composable BoxScope.() -> Unit) {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val (c1, c2, c3) = when (hour) {
        in 5..10 -> Triple(Amber, Pink, Violet)
        in 11..16 -> Triple(Cyan, Violet, Emerald)
        in 17..20 -> Triple(Pink, Violet, Amber)
        else -> Triple(Violet, Color(0xFF3B82F6), Pink)
    }
    val tr = rememberInfiniteTransition(label = "aurora")
    val a by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(17000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    val b by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(23000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    Box(Modifier.fillMaxSize().background(Bg).drawBehind {
        fun blob(c: Color, x: Float, y: Float, r: Float) =
            drawCircle(Brush.radialGradient(listOf(c.copy(alpha = .26f), Color.Transparent), Offset(x, y), r), r, Offset(x, y))
        blob(c1, size.width * (0.1f + 0.7f * a), size.height * (0.06f + 0.1f * b), size.width * 0.95f)
        blob(c2, size.width * (0.95f - 0.7f * b), size.height * (0.45f + 0.15f * a), size.width * 0.8f)
        blob(c3, size.width * (0.2f + 0.5f * b), size.height * (0.98f - 0.12f * a), size.width * 0.9f)
    }, content = content)
}

// ---------- building blocks ----------

@Composable
fun Orbit(modifier: Modifier = Modifier) {
    val r by rememberInfiniteTransition(label = "orbit")
        .animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "r")
    Canvas(modifier.size(96.dp)) {
        val rad = size.minDimension * .34f
        drawCircle(Line, rad, center, style = Stroke(2f))
        drawCircle(Brush.radialGradient(listOf(Ink, Violet.copy(alpha = .9f), Color.Transparent), center, size.minDimension * .2f), size.minDimension * .2f, center)
        listOf(Violet, Cyan, Pink).forEachIndexed { i, c ->
            val ang = Math.toRadians((r + i * 120).toDouble())
            val p = Offset(center.x + rad * cos(ang).toFloat(), center.y + rad * sin(ang).toFloat())
            drawCircle(c.copy(alpha = .3f), size.minDimension * .12f, p)
            drawCircle(c, size.minDimension * .06f, p)
        }
    }
}

@Composable
fun Pulse(color: Color, on: Boolean) {
    val s by rememberInfiniteTransition(label = "pulse")
        .animateFloat(1f, 2.4f, infiniteRepeatable(tween(1500), RepeatMode.Restart), label = "s")
    Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
        if (on) Box(Modifier.size(8.dp).graphicsLayer { scaleX = s; scaleY = s; alpha = (2.4f - s) / 1.4f }.background(color, CircleShape))
        Box(Modifier.size(8.dp).background(if (on) color else Muted, CircleShape))
    }
}

@Composable
private fun PressButton(text: String, modifier: Modifier, bg: Brush, fg: Color, bordered: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) .96f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "press")
    Box(
        modifier.graphicsLayer { scaleX = s; scaleY = s }.clip(RoundedCornerShape(18.dp)).background(bg)
            .then(if (bordered) Modifier.border(1.dp, Line, RoundedCornerShape(18.dp)) else Modifier)
            .clickable(src, LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(vertical = 16.dp, horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
}

@Composable
fun GradButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    PressButton(text, modifier, Brush.linearGradient(listOf(Violet, Pink)), Color.White, false, onClick)

@Composable
fun GlassButton(text: String, modifier: Modifier = Modifier, color: Color = Ink, onClick: () -> Unit) =
    PressButton(text, modifier, SolidColor(Glass), color, true, onClick)

@Composable
fun RoundIcon(icon: ImageVector, desc: String, onClick: () -> Unit) =
    Box(Modifier.size(46.dp).glass(23.dp).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = Ink)
    }

@Composable
fun Field(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "") =
    BasicTextField(value, onChange, modifier.fillMaxWidth(), singleLine = true,
        textStyle = TextStyle(color = Ink, fontSize = 16.sp), cursorBrush = SolidColor(Violet),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().glass(16.dp).padding(horizontal = 16.dp, vertical = 15.dp)) {
                if (value.isEmpty()) Text(placeholder, color = Muted, fontSize = 16.sp)
                inner()
            }
        })

@Composable
fun Avatar(from: String, c: Color, size: Dp = 46.dp) =
    Box(Modifier.size(size).background(Brush.linearGradient(listOf(c, c.copy(alpha = .45f))), CircleShape), contentAlignment = Alignment.Center) {
        Text(from.firstOrNull { it.isLetter() }?.uppercase() ?: "#", color = Bg, fontWeight = FontWeight.Black, fontSize = (size.value * .38f).sp)
    }

@Composable
fun OtpPill(code: String, c: Color) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1600); copied = false } }
    Row(
        Modifier.clip(CircleShape).background(c.copy(alpha = .18f)).border(1.dp, c.copy(alpha = .45f), CircleShape)
            .clickable(role = Role.Button) {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("code", code))
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                copied = true
            }.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(code, color = Ink, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp, fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        AnimatedContent(copied, label = "copy") { Text(if (it) "Copied" else "Copy", color = c, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
fun Qr(text: String, modifier: Modifier = Modifier) {
    val m = remember(text) { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1)) }
    Canvas(modifier.aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(14.dp)) {
        val cell = size.width / m.width
        for (y in 0 until m.height) for (x in 0 until m.width)
            if (m[x, y]) drawRect(Bg, Offset(x * cell, y * cell), Size(cell + .6f, cell + .6f))
    }
}

/** Battery-optimization status + fix-it buttons. Shared by the main phone's settings and the relay screen. */
@Composable
fun KeepAliveCard() {
    val ctx = LocalContext.current
    val ok = remember(ResumeTick.intValue) { batteryOk(ctx) }
    Column(Modifier.fillMaxWidth().glass(20.dp).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pulse(if (ok) Emerald else Amber, ok)
            Spacer(Modifier.width(10.dp))
            Text("Background running", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(if (ok) "Allowed. Android won't put Simbox to sleep." else "Android may pause Simbox to save battery, and messages would arrive late.",
            color = Muted, fontSize = 14.sp)
        if (!ok) { Spacer(Modifier.height(12.dp)); GradButton("Allow", Modifier.fillMaxWidth()) { askBattery(ctx) } }
        Spacer(Modifier.height(12.dp))
        Text("On Xiaomi, Oppo, Vivo, Realme and Samsung, also open App info and turn on Autostart, and set Battery to No restrictions.",
            color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(10.dp))
        GlassButton("Open App info", Modifier.fillMaxWidth()) { appSettings(ctx) }
    }
}

// ---------- helpers ----------

fun batteryOk(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

@android.annotation.SuppressLint("BatteryLife")
fun askBattery(ctx: Context) =
    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))

fun appSettings(ctx: Context) =
    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))

fun clock(t: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(t))
fun fullTime(t: Long): String = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(t))
fun ago(t: Long, now: Long): String = when {
    t == 0L -> "never"
    now - t < 60_000 -> "just now"
    else -> DateUtils.getRelativeTimeSpanString(t, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

fun dayLabel(t: Long): String = when {
    DateUtils.isToday(t) -> "Today"
    DateUtils.isToday(t + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
    else -> SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date(t))
}


// ---------- more building blocks ----------

@Composable
fun Shimmer(modifier: Modifier = Modifier) {
    val x by rememberInfiniteTransition(label = "shimmer")
        .animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "x")
    Box(modifier.drawBehind {
        drawRect(Brush.linearGradient(listOf(Glass, Color(0x26FFFFFF), Glass),
            start = Offset(size.width * x - size.width * .5f, 0f), end = Offset(size.width * x + size.width * .5f, size.height)))
    })
}

@Composable
fun ShimmerCard() = Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).glass(22.dp).padding(14.dp)) {
    Shimmer(Modifier.size(46.dp).clip(CircleShape))
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
        Shimmer(Modifier.fillMaxWidth(.45f).height(14.dp).clip(CircleShape))
        Spacer(Modifier.height(10.dp))
        Shimmer(Modifier.fillMaxWidth().height(11.dp).clip(CircleShape))
        Spacer(Modifier.height(6.dp))
        Shimmer(Modifier.fillMaxWidth(.7f).height(11.dp).clip(CircleShape))
    }
}

@Composable
fun SectionTitle(text: String) =
    Text(text.uppercase(), Modifier.padding(start = 6.dp, top = 22.dp, bottom = 8.dp), color = Muted, fontSize = 12.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 1.sp)

@Composable
fun Card(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().glass(20.dp), content = content)

@Composable
fun ToggleRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!checked) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(sub, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange, colors = SwitchDefaults.colors(
            checkedTrackColor = Violet, checkedThumbColor = Color.White, uncheckedTrackColor = Glass, uncheckedBorderColor = Line, uncheckedThumbColor = Muted))
    }

@Composable
fun NavRow(title: String, sub: String? = null, color: Color = Ink, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = color, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (sub != null) Text(sub, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Muted)
    }

@Composable
fun Divider() = Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(Line))

@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) =
    Row(modifier.clip(CircleShape).background(Glass).border(1.dp, Line, CircleShape).padding(4.dp)) {
        options.forEachIndexed { i, o ->
            val bg by animateColorAsState(if (i == selected) Ink else Color.Transparent, label = "seg")
            val fg by animateColorAsState(if (i == selected) Bg else Muted, label = "segfg")
            Box(Modifier.weight(1f).clip(CircleShape).background(bg).clickable(role = Role.Tab) { onSelect(i) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center) { Text(o, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
        }
    }

@Composable
fun SheetRow(text: String, color: Color = Ink, onClick: () -> Unit) =
    Text(text, Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 24.dp, vertical = 15.dp),
        color = color, fontSize = 16.sp)

@Composable
fun AmountPill(amt: String) {
    val c = when { amt.startsWith("+") -> Emerald; amt.startsWith("-") -> Coral; else -> Amber }
    Text(amt, Modifier.clip(CircleShape).background(c.copy(alpha = .16f)).padding(horizontal = 10.dp, vertical = 5.dp),
        color = c, fontSize = 13.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun CountBadge(n: Int, color: Color = Violet) {
    if (n <= 0) return
    Box(Modifier.height(20.dp).widthIn(min = 20.dp).clip(CircleShape).background(color).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Text(if (n > 999) "999+" else n.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

private val linkRx = Regex("(https?://\\S+|www\\.\\S+)|(\\*\\d[\\d*]*#)|(\\+?\\d[\\d -]{8,}\\d)")

/** Message text with tappable links, USSD codes and phone numbers. */
fun linkified(text: String, color: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in linkRx.findAll(text)) {
        append(text.substring(last, m.range.first))
        val v = m.value.trimEnd('.', ',', ')')
        val url = if (m.groups[1] != null) (if (v.startsWith("www")) "https://$v" else v)
            else "tel:" + Uri.encode(v.filter { it.isDigit() || it in "+*#" })
        withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline)))) { append(v) }
        append(m.value.substring(v.length))
        last = m.range.last + 1
    }
    append(text.substring(last))
}

fun copyText(ctx: Context, text: String, label: String = "Copied") {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("text", text))
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, label, Toast.LENGTH_SHORT).show()
}

fun shareText(ctx: Context, text: String) =
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))

fun openUrl(ctx: Context, url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

private val oem = Build.MANUFACTURER.lowercase()
val needsAutostart = listOf("xiaomi", "redmi", "poco", "oppo", "realme", "oneplus", "vivo", "iqoo", "samsung", "huawei", "honor").any { it in oem }
val isXiaomi = listOf("xiaomi", "redmi", "poco").any { it in oem }

/** Opens the phone maker's autostart / background manager, falling back to App info. */
fun openAutostart(ctx: Context) {
    val targets = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
    )
    for ((pkg, cls) in targets) {
        if (runCatching { ctx.startActivity(Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
    appSettings(ctx)
}
