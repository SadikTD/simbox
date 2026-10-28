package app.simbox

import android.content.ComponentName
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.produceState
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

// Off-black neutrals carry the screen; violet is the single accent. SIM hues are identification only (dots, avatars).
val Bg = Color(0xFF0E0F14)
val Panel = Color(0xFF191A22)
val Raised = Color(0xFF20212B)
val Ink = Color(0xFFECEDF2)
val Soft = Color(0xFFB9BCCB)
val Muted = Color(0xFF8B8FA3)
val Glass = Color(0x0FFFFFFF)
val Line = Color(0x17FFFFFF)
val Violet = Color(0xFF8B7CF6)
val Cyan = Color(0xFF4FC3D9)
val Pink = Color(0xFFE57BB0)
val Amber = Color(0xFFE0B35A)
val Emerald = Color(0xFF4CC38A)
val Coral = Color(0xFFE5767F)
val SimColors = listOf(Violet, Cyan, Pink, Amber, Emerald, Coral)

@Composable
fun SimboxTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = Violet, background = Bg, surface = Panel, onSurface = Ink),
) { CompositionLocalProvider(LocalContentColor provides Ink, content = content) }

fun Modifier.glass(r: Dp = 22.dp) = clip(RoundedCornerShape(r)).background(Glass).border(1.dp, Line, RoundedCornerShape(r))
fun Modifier.panel() = clip(RoundedCornerShape(28.dp)).background(Panel).border(1.dp, Line, RoundedCornerShape(28.dp))

// ---------- motion ----------

/** False when the user turned animations off in Android settings; everything then holds still. */
@Composable
fun motionOn(): Boolean {
    val ctx = LocalContext.current
    return remember { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
}

private data class Sky(val a: Color, val b: Color, val c: Color)

/** Dawn peach, daytime cyan, sunset coral, night indigo. */
private fun skyAt(hour: Int) = when (hour) {
    in 5..8 -> Sky(Color(0xFFE59A6B), Pink, Violet)
    in 9..15 -> Sky(Cyan, Violet, Color(0xFF5B8DEF))
    in 16..18 -> Sky(Color(0xFFE5876B), Pink, Violet)
    else -> Sky(Violet, Color(0xFF4C5FD7), Color(0xFF2E9BB0))
}

/** Slowly drifting light behind everything; the colors follow the time of day and cross-fade when it changes. */
@Composable
fun Aurora(content: @Composable BoxScope.() -> Unit) {
    val hour by produceState(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        while (true) { delay(5 * 60_000); value = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    }
    val sky = skyAt(hour)
    val a by animateColorAsState(sky.a, tween(4000), label = "skyA")
    val b by animateColorAsState(sky.b, tween(4000), label = "skyB")
    val c by animateColorAsState(sky.c, tween(4000), label = "skyC")
    val moving = motionOn()
    val t = rememberInfiniteTransition(label = "aurora")
    val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(26000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "x")
    val y by t.animateFloat(0f, 1f, infiniteRepeatable(tween(34000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "y")
    Box(Modifier.fillMaxSize().background(Bg).drawBehind {
        // read inside draw: the drift only redraws, it never recomposes the screen
        val px = if (moving) x else .5f
        val py = if (moving) y else .5f
        fun blob(col: Color, alpha: Float, cx: Float, cy: Float, r: Float) =
            drawCircle(Brush.radialGradient(listOf(col.copy(alpha = alpha), Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy))
        blob(a, .20f, size.width * (.05f + .55f * px), size.height * (.02f + .10f * py), size.width * .95f)
        blob(b, .12f, size.width * (.95f - .55f * py), size.height * (.38f + .16f * px), size.width * .85f)
        blob(c, .14f, size.width * (.25f + .45f * py), size.height * (1.0f - .10f * px), size.width * .9f)
    }, content = content)
}

/** Fades and lifts a list item in once, staggered by position. Only while [intro] is on, so scrolling never replays it. */
@Composable
fun Modifier.appear(index: Int, intro: Boolean): Modifier {
    val p = remember { Animatable(if (intro && index < 14) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (p.value < 1f) { delay(index * 32L); p.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
    }
    return graphicsLayer { alpha = p.value; translationY = (1f - p.value) * 18.dp.toPx() }
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

/** Status dot; online ones breathe a soft halo. */
@Composable
fun Pulse(color: Color, on: Boolean) {
    val moving = motionOn()
    val s by rememberInfiniteTransition(label = "pulse")
        .animateFloat(1f, 2.2f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "s")
    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
        if (on && moving) Box(Modifier.size(7.dp).graphicsLayer { scaleX = s; scaleY = s; alpha = (2.2f - s) / 1.2f * .6f }.background(color, CircleShape))
        else if (on) Box(Modifier.size(12.dp).background(color.copy(alpha = .22f), CircleShape))
        Box(Modifier.size(7.dp).background(if (on) color else Muted, CircleShape))
    }
}

@Composable
private fun PressButton(text: String, modifier: Modifier, bg: Brush, fg: Color, bordered: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) .97f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "press")
    Box(
        modifier.graphicsLayer { scaleX = s; scaleY = s }.clip(RoundedCornerShape(14.dp)).background(bg)
            .then(if (bordered) Modifier.border(1.dp, Line, RoundedCornerShape(14.dp)) else Modifier)
            .clickable(src, LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
}

@Composable
fun GradButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    PressButton(text, modifier, SolidColor(Violet), Color.White, false, onClick)

@Composable
fun GlassButton(text: String, modifier: Modifier = Modifier, color: Color = Ink, onClick: () -> Unit) =
    PressButton(text, modifier, SolidColor(Glass), color, true, onClick)

/** Plain 48dp icon button: no chrome, ripple on press. */
@Composable
fun RoundIcon(icon: ImageVector, desc: String, tint: Color = Ink, onClick: () -> Unit) =
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = tint)
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

/** Tinted circle: the SIM hue at low strength with the initial in full hue. Phone numbers get a person glyph. */
@Composable
fun Avatar(from: String, c: Color, size: Dp = 44.dp) =
    Box(Modifier.size(size).background(c.copy(alpha = .16f), CircleShape), contentAlignment = Alignment.Center) {
        val letter = from.firstOrNull { it.isLetter() }
        if (letter == null) Icon(androidx.compose.material.icons.Icons.Default.Person, null, Modifier.size(size * .5f), tint = c)
        else Text(letter.uppercase(), color = c, fontWeight = FontWeight.SemiBold, fontSize = (size.value * .4f).sp)
    }

/** One-time code with tap-to-copy. Compact so it sits inside a list row. */
@Composable
fun OtpPill(code: String, c: Color) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1600); copied = false } }
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Violet.copy(alpha = .14f))
            .clickable(role = Role.Button, onClickLabel = "Copy code") {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("code", code))
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                copied = true
            }.padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(code, color = Ink, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp, fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        AnimatedContent(copied, label = "copy") { Text(if (it) "Copied" else "Copy", color = Violet, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
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
        AnimatedContent(n, transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) }, label = "badge") {
            Text(if (it > 999) "999+" else it.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
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
