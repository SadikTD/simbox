package app.simbox

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
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
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.firestore.SetOptions
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Bumped on every onResume so screens re-check things the user may have changed in system settings. */
val ResumeTick = mutableIntStateOf(0)
val Locked = mutableStateOf(false)
private var stoppedAt = 0L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(dark, dark)
        if (savedInstanceState == null && Store.appLock) Locked.value = true
        handle(intent)
        setContent { SimboxTheme { Aurora { if (Locked.value) LockScreen() else Root() } } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    private fun handle(i: Intent?) {
        val src = i?.getStringExtra("src") ?: return
        OpenChat.value = Chat(i.getStringExtra("dev").orEmpty(), i.getIntExtra("slot", -1), src)
    }

    override fun onStart() {
        super.onStart()
        if (Store.appLock && stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > 30_000) Locked.value = true
    }

    override fun onStop() { super.onStop(); stoppedAt = System.currentTimeMillis() }
    override fun onResume() { super.onResume(); ResumeTick.intValue++ }
}

// ---------- app lock ----------

private fun authenticate(act: ComponentActivity, legacy: () -> Unit, onOk: () -> Unit) {
    if (Build.VERSION.SDK_INT >= 28) {
        val b = BiometricPrompt.Builder(act).setTitle("Unlock Simbox").setSubtitle("Your messages are protected")
        if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        else @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
        b.build().authenticate(CancellationSignal(), act.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
        })
    } else legacy()
}

@Composable
private fun LockScreen() {
    val act = LocalContext.current as ComponentActivity
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { if (it.resultCode == android.app.Activity.RESULT_OK) Locked.value = false }
    val unlock = {
        authenticate(act, {
            val i = act.getSystemService(KeyguardManager::class.java).createConfirmDeviceCredentialIntent("Unlock Simbox", null)
            if (i == null) Locked.value = false else legacy.launch(i)
        }) { Locked.value = false }
    }
    LaunchedEffect(Unit) { unlock() }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Orbit()
        Spacer(Modifier.height(24.dp))
        Text("Simbox is locked", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text("Unlock to read your messages.", color = Muted, fontSize = 15.sp)
        Spacer(Modifier.height(28.dp))
        GradButton("Unlock", Modifier.fillMaxWidth()) { unlock() }
    }
}

// ---------- root ----------

private val RELAY_PERMS = buildList {
    add(Manifest.permission.RECEIVE_SMS); add(Manifest.permission.READ_SMS); add(Manifest.permission.READ_PHONE_STATE)
    if (Build.VERSION.SDK_INT >= 26) add(Manifest.permission.READ_PHONE_NUMBERS)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private fun hasSms(ctx: Context) = RELAY_PERMS.take(3).all { granted(ctx, it) }

@Composable
private fun Root() {
    val ctx = LocalContext.current
    var room by remember { mutableStateOf(Store.room) }
    var permTick by remember { mutableIntStateOf(0) }
    var denied by remember { mutableStateOf(false) }
    val role = remember(room) { Store.role }
    val smsOk = remember(ResumeTick.intValue, permTick) { hasSms(ctx) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permTick++; denied = !hasSms(ctx) }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // a relay removed from the main phone unlinks itself in the background; notice that when we come back
    LaunchedEffect(ResumeTick.intValue) { if (Store.room != room) room = Store.room }
    LaunchedEffect(room, role, smsOk) {
        if (room == null) return@LaunchedEffect
        if (role == "main") {
            Sync.start(ctx)
            startBackground(ctx)
            if (Build.VERSION.SDK_INT >= 33 && !granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (smsOk) startBackground(ctx)
        withContext(Dispatchers.IO) { checkUpdate() }
    }

    val unlink = {
        stopBackground(ctx); Sync.stop()
        if (Store.role == "main") Sync.io.execute { Sync.db.clear() }
        Store.unlink(); room = null
    }
    val screen = when {
        room == null -> "welcome"
        role == "main" -> "main"
        !smsOk -> "perm"
        else -> "relay"
    }
    AnimatedContent(screen, transitionSpec = {
        (fadeIn(tween(450)) + scaleIn(tween(450), initialScale = .96f)) togetherWith fadeOut(tween(200))
    }, label = "screen") { s ->
        when (s) {
            "welcome" -> Welcome { room = Store.room; if (Store.role == "relay") perms.launch(RELAY_PERMS) }
            "perm" -> PermissionScreen(denied) { perms.launch(RELAY_PERMS) }
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
                Spacer(Modifier.height(36.dp))
                Text("Name this phone", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Field(name, { name = it }, placeholder = "e.g. Black Samsung")
                Spacer(Modifier.height(20.dp))
                RoleCard("This is my main phone", "Read messages from all your other phones here.", true) { Store.name = name; Store.create(); onDone() }
                Spacer(Modifier.height(12.dp))
                RoleCard("Send this phone's messages", "Scan the code on your main phone to link this one.", false) {
                    err = null
                    scan.launch(ScanOptions().setPrompt("Scan the code on your main phone").setBeepEnabled(false).setDesiredBarcodeFormats(ScanOptions.QR_CODE))
                }
                err?.let { Spacer(Modifier.height(12.dp)); Text(it, color = Coral, fontSize = 14.sp) }
            }
        }
    }
}

@Composable
private fun RoleCard(title: String, sub: String, primary: Boolean, onClick: () -> Unit) {
    val bg = if (primary) Brush.linearGradient(listOf(Violet, Pink)) else Brush.linearGradient(listOf(Glass, Glass))
    Column(Modifier.fillMaxWidth().glass(20.dp).background(bg).clickRole(onClick).padding(18.dp)) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(sub, color = Color.White.copy(alpha = .8f), fontSize = 13.sp)
    }
}

private fun Modifier.clickRole(onClick: () -> Unit) = clickable(role = Role.Button, onClick = onClick)

@Composable
private fun PermissionScreen(denied: Boolean, ask: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center) {
        Orbit()
        Spacer(Modifier.height(20.dp))
        Text("Let Simbox read this phone's SMS", color = Ink, fontSize = 30.sp, fontWeight = FontWeight.Black, lineHeight = 34.sp)
        Spacer(Modifier.height(12.dp))
        Text("Simbox reads incoming SMS and which SIM they arrived on, then sends them to your main phone. They're encrypted before they leave this phone.",
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

// ---------- sender phone ----------

@Composable
private fun RelayScreen(onUnlink: () -> Unit) {
    val ctx = LocalContext.current
    val tick = ResumeTick.intValue
    var local by remember { mutableIntStateOf(0) }
    val sims = remember(tick) { activeSims(ctx) }
    val synced by produceState(Store.synced) { while (true) { value = Store.synced; delay(1500) } }
    val contactsOk = remember(tick, local) { granted(ctx, Manifest.permission.READ_CONTACTS) }
    val batteryOk = remember(tick) { batteryOk(ctx) }
    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { local++; if (it) startBackground(ctx) }
    var pingAt by remember { mutableLongStateOf(Store.pingAt) }
    var ack by remember { mutableLongStateOf(0L) }
    val update = remember(tick) { updateAvailable() }
    DisposableEffect(Unit) {
        val reg = devsRef().document(Store.deviceId).addSnapshotListener { s, _ -> ack = s?.getLong("ack") ?: 0 }
        onDispose { reg.remove() }
    }
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(1000); value = System.currentTimeMillis() } }

    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) { Radar() }
        Text("Sending", color = Ink, fontSize = 44.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp)
        Text("New SMS on ${Store.name} go to your main phone, encrypted. You can close this app.", color = Muted, fontSize = 15.sp, lineHeight = 21.sp)

        if (update != null) {
            Spacer(Modifier.height(16.dp))
            GradButton("Update to Simbox ${update.first}", Modifier.fillMaxWidth()) { openUrl(ctx, update.second) }
        }

        SectionTitle("This phone")
        Card {
            Column(Modifier.padding(16.dp)) {
                if (sims.isEmpty()) Text("No SIM detected", color = Muted)
                sims.forEachIndexed { i, s ->
                    if (i > 0) Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(SimColors[i % SimColors.size], CircleShape))
                        Spacer(Modifier.width(12.dp))
                        Text("SIM ${s.slot + 1}", color = Ink, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        Text(listOf(s.carrier, s.number).filter { it.isNotBlank() }.joinToString(" · "), color = Muted)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(if (Store.backfilled) "All ${"%,d".format(synced)} existing messages sent" else "Sending existing messages: ${"%,d".format(synced)} so far",
                    color = if (Store.backfilled) Emerald else Amber, fontSize = 13.sp)
                Text(if (Store.lastUpload > 0) "Last new message sent ${ago(Store.lastUpload, now)}" else "Waiting for the first new message",
                    color = Muted, fontSize = 13.sp)
            }
        }

        SectionTitle("Setup checklist")
        Card {
            Check("SMS access", "Allowed", true)
            Divider()
            Check("Background running", if (batteryOk) "Allowed" else "Android may pause Simbox to save battery", batteryOk, "Allow") { askBattery(ctx) }
            if (needsAutostart) {
                Divider()
                val done = remember(local) { Store.flag("autostart") }
                Check("Autostart", if (done) "Marked as done" else "Turn on Autostart for Simbox so it runs after restarts", done,
                    if (done) null else "Open") { openAutostart(ctx); Store.setFlag("autostart", true); local++ }
            }
            if (isXiaomi) {
                Divider()
                val done = remember(local) { Store.flag("xsms") }
                Check("Service SMS (Xiaomi)", if (done) "Marked as done" else "In App info › Other permissions, allow Notification/Service SMS or bank and OTP SMS are hidden",
                    done, if (done) null else "Open") { appSettings(ctx); Store.setFlag("xsms", true); local++ }
            }
            Divider()
            Check("Contact names", if (contactsOk) "Your main phone shows names instead of numbers" else "Optional: show names instead of numbers", contactsOk,
                if (contactsOk) null else "Allow") { askContacts.launch(Manifest.permission.READ_CONTACTS) }
            Divider()
            val state = when {
                pingAt == 0L -> 0
                ack >= pingAt -> 2
                now - pingAt > 30_000 -> 3
                else -> 1
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                    when (state) {
                        1 -> CircularProgressIndicator(Modifier.size(20.dp), color = Violet, strokeWidth = 2.dp)
                        2 -> Done(true)
                        else -> Done(false)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Test connection", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(when (state) {
                        0 -> "Check that your main phone gets messages from this one"
                        1 -> "Waiting for your main phone…"
                        2 -> "Your main phone received it"
                        else -> "No reply yet. Is the main phone online with Simbox running?"
                    }, color = if (state == 2) Emerald else Muted, fontSize = 13.sp)
                }
                if (state != 1) Text(if (state == 2) "Again" else "Send", Modifier.clickRole {
                    val t = System.currentTimeMillis(); Store.pingAt = t; pingAt = t
                    devsRef().document(Store.deviceId).set(mapOf("ping" to t), SetOptions.merge())
                }.padding(8.dp), color = Violet, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(20.dp))
        UnlinkButton(onUnlink)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Done(ok: Boolean) = Box(Modifier.size(24.dp).background(if (ok) Emerald else Glass, CircleShape), contentAlignment = Alignment.Center) {
    if (ok) Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = Bg)
}

@Composable
private fun Check(title: String, sub: String, ok: Boolean, action: String? = null, onAction: () -> Unit = {}) =
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Done(ok)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(sub, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        if (action != null && !ok) Text(action, Modifier.clickRole(onAction).padding(8.dp), color = Violet, fontWeight = FontWeight.Bold)
    }

@Composable
private fun Radar() {
    val t by rememberInfiniteTransition(label = "radar")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(2600), RepeatMode.Restart), label = "t")
    Canvas(Modifier.size(150.dp)) {
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
