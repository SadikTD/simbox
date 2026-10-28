package app.simbox

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Bumped on every onResume so screens re-check things the user may have changed in system settings. */
val ResumeTick = mutableIntStateOf(0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(dark, dark)
        setContent { SimboxTheme { Aurora { Root() } } }
    }

    override fun onResume() { super.onResume(); ResumeTick.intValue++ }
}

private val PERMS = buildList {
    add(Manifest.permission.RECEIVE_SMS); add(Manifest.permission.READ_SMS); add(Manifest.permission.READ_PHONE_STATE)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private fun hasSms(ctx: Context) = PERMS.take(3).all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

@Composable
private fun Root() {
    val ctx = LocalContext.current
    var room by remember { mutableStateOf(Store.room) }
    var permTick by remember { mutableIntStateOf(0) }
    var denied by remember { mutableStateOf(false) }
    val granted = remember(ResumeTick.intValue, permTick) { hasSms(ctx) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permTick++; denied = !hasSms(ctx)
    }

    LaunchedEffect(room, granted) {
        if (room == null || !granted) return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { heartbeat(ctx) } }
        startBackground(ctx)
        if (Store.role == "main") Repo.start()
    }

    val unlink = { stopBackground(ctx); Repo.stop(); Store.unlink(); room = null }
    val screen = when {
        room == null -> "welcome"
        !granted -> "perm"
        Store.role == "main" -> "main"
        else -> "relay"
    }
    AnimatedContent(screen, transitionSpec = {
        (fadeIn(tween(450)) + scaleIn(tween(450), initialScale = .96f)) togetherWith fadeOut(tween(200))
    }, label = "screen") { s ->
        when (s) {
            "welcome" -> Welcome { room = Store.room; perms.launch(PERMS) }
            "perm" -> PermissionScreen(denied) { perms.launch(PERMS) }
            "main" -> Inbox(unlink)
            else -> RelayScreen(unlink)
        }
    }
}

@Composable
private fun Welcome(onDone: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(Store.name) }
    var err by remember { mutableStateOf<String?>(null) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r ->
        val code = r.contents ?: return@rememberLauncherForActivityResult
        if (Store.join(code)) { Store.name = name; onDone() }
        else err = "That isn't a Simbox code. On your main phone, open Simbox, tap + and scan the code shown there."
    }
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Bottom) {
        AnimatedVisibility(shown, enter = fadeIn(tween(800)) + slideInVertically(tween(800)) { it / 4 }) {
            Column {
                Orbit()
                Spacer(Modifier.height(20.dp))
                Text("Simbox", style = TextStyle(brush = Brush.linearGradient(listOf(Ink, Violet, Cyan)),
                    fontSize = 56.sp, fontWeight = FontWeight.Black, letterSpacing = (-2).sp))
                Text("Every SIM, every phone, one inbox.", color = Muted, fontSize = 18.sp)
                Spacer(Modifier.height(40.dp))
                Text("Name this phone", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Field(name, { name = it }, placeholder = "e.g. Black Samsung")
                Spacer(Modifier.height(20.dp))
                GradButton("This is my main phone", Modifier.fillMaxWidth()) { Store.name = name; Store.create(); onDone() }
                Spacer(Modifier.height(12.dp))
                GlassButton("Link to my main phone", Modifier.fillMaxWidth()) {
                    err = null
                    scan.launch(ScanOptions().setPrompt("Scan the code on your main phone").setBeepEnabled(false)
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE))
                }
                err?.let { Spacer(Modifier.height(12.dp)); Text(it, color = Coral, fontSize = 14.sp) }
            }
        }
    }
}

@Composable
private fun PermissionScreen(denied: Boolean, ask: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center) {
        Orbit()
        Spacer(Modifier.height(20.dp))
        Text("Let Simbox read your SMS", color = Ink, fontSize = 32.sp, fontWeight = FontWeight.Black, lineHeight = 36.sp)
        Spacer(Modifier.height(12.dp))
        Text("Simbox reads incoming SMS and checks which SIM each one arrived on. Messages are encrypted on this phone before they leave it, so only your linked phones can read them.",
            color = Muted, fontSize = 15.sp, lineHeight = 22.sp)
        Spacer(Modifier.height(28.dp))
        GradButton("Allow access", Modifier.fillMaxWidth(), ask)
        if (denied) {
            Spacer(Modifier.height(20.dp))
            Column(Modifier.fillMaxWidth().glass(20.dp).padding(16.dp)) {
                Text("Blocked by Android?", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))
                Text("Apps installed from an APK need one extra step. Open App info, tap the three-dot menu at the top right, choose Allow restricted settings, then allow SMS and Phone.",
                    color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(12.dp))
                GlassButton("Open App info", Modifier.fillMaxWidth()) { appSettings(ctx) }
            }
        }
    }
}

@Composable
private fun RelayScreen(onUnlink: () -> Unit) {
    val ctx = LocalContext.current
    val tick = ResumeTick.intValue
    val sims = remember(tick) { activeSims(ctx) }
    val last = remember(tick) { Store.lastUpload }
    val synced by produceState(Store.synced) { while (true) { value = Store.synced; delay(1500) } }
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) { Radar() }
        Text("Relaying", color = Ink, fontSize = 44.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp)
        Text("New SMS on ${Store.name} go straight to your main phone, encrypted.", color = Muted, fontSize = 15.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(20.dp))
        Column(Modifier.fillMaxWidth().glass(20.dp).padding(16.dp)) {
            if (sims.isEmpty()) Text("No SIM detected", color = Muted)
            sims.forEachIndexed { i, s ->
                if (i > 0) Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(SimColors[i % SimColors.size], CircleShape))
                    Spacer(Modifier.width(12.dp))
                    Text("SIM ${s.slot + 1}", color = Ink, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Text(s.carrier, color = Muted)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(if (Store.backfilled) "All $synced existing messages synced" else "Syncing existing messages: $synced so far",
                color = if (Store.backfilled) Emerald else Amber, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text(if (last > 0) "Last message relayed ${ago(last, System.currentTimeMillis())}" else "Waiting for the first new message",
                color = Muted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(14.dp))
        KeepAliveCard()
        Spacer(Modifier.height(14.dp))
        UnlinkButton(onUnlink)
    }
}

@Composable
private fun Radar() {
    val t by rememberInfiniteTransition(label = "radar")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(2600), RepeatMode.Restart), label = "t")
    Canvas(Modifier.size(170.dp)) {
        for (i in 0 until 3) {
            val p = (t + i / 3f) % 1f
            drawCircle(Violet.copy(alpha = (1f - p) * .7f), size.minDimension / 2 * p, style = Stroke(3f))
        }
        drawCircle(Brush.radialGradient(listOf(Ink, Violet, Violet.copy(alpha = 0f)), center, size.minDimension * .16f), size.minDimension * .16f)
    }
}

@Composable
fun UnlinkButton(onUnlink: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    GlassButton(if (confirm) "Tap again to unlink" else "Unlink this phone", Modifier.fillMaxWidth(), if (confirm) Coral else Muted) {
        if (confirm) onUnlink() else confirm = true
    }
}
