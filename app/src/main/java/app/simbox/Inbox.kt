package app.simbox

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay

data class SimRef(val key: String, val dev: String, val slot: Int, val carrier: String)

/** Colors and names for SIMs and phones, stable across the inbox. */
class Look(val sims: List<SimRef>, private val devices: Map<String, Device>) {
    fun color(key: String) = SimColors[sims.indexOfFirst { it.key == key }.coerceAtLeast(0) % SimColors.size]
    fun phone(dev: String) = devices[dev]?.name ?: "Phone"
    fun title(s: SimRef) = simTitle(s.key, s.slot, s.carrier)
    fun title(m: Msg) = simTitle(m.sim, m.slot, m.carrier)
}

@Composable
fun Inbox(onUnlink: () -> Unit) {
    val msgs by Repo.msgs.collectAsState()
    val devices by Repo.devices.collectAsState()
    val error by Repo.error.collectAsState()
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var thread by remember { mutableStateOf<Pair<String, String>?>(null) }
    var dialog by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<SimRef?>(null) }
    var labelTick by remember { mutableIntStateOf(0) }
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }

    val look = remember(msgs, devices, labelTick) {
        val refs = devices.values.flatMap { d -> d.sims.map { SimRef("${d.id}:${it.slot}", d.id, it.slot, it.carrier) } } +
            msgs.map { SimRef(it.sim, it.dev, it.slot, it.carrier) }
        Look(refs.distinctBy { it.key }.sortedWith(compareBy({ devices[it.dev]?.name ?: it.dev }, { it.slot })), devices)
    }

    BackHandler(thread != null) { thread = null }
    AnimatedContent(thread, transitionSpec = {
        if (targetState != null) (slideInHorizontally(tween(380)) { it } + fadeIn()) togetherWith (slideOutHorizontally(tween(380)) { -it / 4 } + fadeOut())
        else (slideInHorizontally(tween(380)) { -it / 4 } + fadeIn()) togetherWith (slideOutHorizontally(tween(380)) { it } + fadeOut())
    }, label = "nav") { th ->
        if (th == null) InboxList(msgs, devices, look, error, filter, { filter = it }, query, { query = it }, now,
            onOpen = { thread = it.sim to it.from }, onLink = { dialog = "link" }, onSettings = { dialog = "settings" },
            onRename = { renaming = it })
        else ThreadView(th.first, th.second, msgs, look) { thread = null }
    }

    when (dialog) {
        "link" -> Sheet({ dialog = "" }) {
            Text("Link a phone", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(6.dp))
            Text("Install Simbox on the other phone, tap Link to my main phone, and point it at this code.", color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(18.dp))
            Qr(Store.pairing(), Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            Text("This code unlocks your messages. Only show it to your own phones.", color = Coral, fontSize = 12.sp)
        }
        "settings" -> Sheet({ dialog = "" }) {
            Text("Settings", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(14.dp))
            KeepAliveCard()
            Spacer(Modifier.height(14.dp))
            Text("Tip: press and hold a SIM chip to rename it.", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
            UnlinkButton(onUnlink)
        }
    }

    renaming?.let { s ->
        var text by remember(s) { mutableStateOf(Store.label(s.key) ?: "") }
        Sheet({ renaming = null }) {
            Text("Name this SIM", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Text("${look.phone(s.dev)} · slot ${s.slot + 1} · ${s.carrier}", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            Field(text, { text = it }, placeholder = "e.g. Bank SIM")
            Spacer(Modifier.height(16.dp))
            Row {
                GlassButton("Cancel", Modifier.weight(1f)) { renaming = null }
                Spacer(Modifier.width(10.dp))
                GradButton("Save", Modifier.weight(1f)) { Store.setLabel(s.key, text); labelTick++; renaming = null }
            }
        }
    }
}

@Composable
private fun Sheet(onDismiss: () -> Unit, content: @Composable () -> Unit) = Dialog(onDismiss) {
    Column(Modifier.fillMaxWidth().panel().verticalScroll(rememberScrollState()).padding(22.dp)) { content() }
}

@Composable
private fun InboxList(
    msgs: List<Msg>, devices: Map<String, Device>, look: Look, error: String?,
    filter: String?, onFilter: (String?) -> Unit, query: String, onQuery: (String) -> Unit, now: Long,
    onOpen: (Msg) -> Unit, onLink: () -> Unit, onSettings: () -> Unit, onRename: (SimRef) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val pick = { f: String? -> haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onFilter(f) }
    val rows = remember(msgs, filter, query) {
        val shown = msgs.filter {
            (filter == null || it.sim == filter || it.dev == filter) &&
                (query.isBlank() || it.body.contains(query, true) || it.from.contains(query, true))
        }
        buildList { var last = ""; shown.forEach { m -> dayLabel(m.t).let { d -> if (d != last) { add(d); last = d } }; add(m) } }
    }

    val state = rememberLazyListState()
    val hasMore by Repo.hasMore.collectAsState()
    val loading by Repo.loading.collectAsState()
    val nearEnd by remember { derivedStateOf { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 >= state.layoutInfo.totalItemsCount - 6 } }
    LaunchedEffect(nearEnd, hasMore, loading, msgs.size) { if (nearEnd && hasMore && !loading) Repo.loadMore() }

    LazyColumn(Modifier.fillMaxSize(), state) {
        val pad = Modifier.padding(horizontal = 18.dp)
        item("head") {
            Row(pad.fillMaxWidth().statusBarsPadding().padding(top = 18.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Inbox", color = Ink, fontSize = 38.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp)
                    Text("${plural(msgs.size, "message")} · ${plural(look.sims.size, "SIM")} · ${plural(devices.size, "phone")}", color = Muted, fontSize = 13.sp)
                }
                RoundIcon(Icons.Default.Add, "Link a phone", onLink)
                Spacer(Modifier.width(10.dp))
                RoundIcon(Icons.Default.Settings, "Settings", onSettings)
            }
        }
        if (error != null) item("err") {
            Column(pad.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(20.dp)).background(Coral.copy(alpha = .14f))
                .border(1.dp, Coral.copy(alpha = .4f), RoundedCornerShape(20.dp)).padding(16.dp)) {
                Text("Can't reach Firebase", color = Ink, fontWeight = FontWeight.SemiBold)
                Text("Check that app/google-services.json is your own and the Firestore rules are published. $error", color = Muted, fontSize = 13.sp)
            }
        }
        if (devices.isNotEmpty()) item("devs") {
            LazyRow(Modifier.padding(bottom = 16.dp), contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(devices.values.sortedBy { it.name }, key = { it.id }) { d ->
                    DeviceCard(d, filter == d.id, now) { pick(if (filter == d.id) null else d.id) }
                }
            }
        }
        item("search") { Box(pad) { SearchBar(query, onQuery) } }
        item("chips") {
            LazyRow(Modifier.padding(vertical = 14.dp), contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Chip("All", null, Ink, filter == null, { pick(null) }) }
                items(look.sims, key = { it.key }) { s ->
                    Chip(look.title(s), look.phone(s.dev), look.color(s.key), filter == s.key,
                        { pick(if (filter == s.key) null else s.key) }, { onRename(s) })
                }
            }
        }
        if (rows.isEmpty()) item("empty") { Box(pad) { Empty(msgs.isEmpty()) } }
        items(rows, key = { if (it is Msg) it.id else "d$it" }) { r ->
            if (r is Msg) MsgCard(r, look, Modifier.animateItem().then(pad)) { onOpen(r) }
            else Text(r as String, Modifier.animateItem().then(pad).padding(top = 14.dp, bottom = 6.dp, start = 4.dp),
                color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        item("end") {
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 12.dp, bottom = 28.dp), contentAlignment = Alignment.Center) {
                if (hasMore) CircularProgressIndicator(Modifier.size(22.dp), color = Violet, strokeWidth = 2.dp)
                else if (msgs.isNotEmpty()) Text("That's everything", color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun DeviceCard(d: Device, selected: Boolean, now: Long, onClick: () -> Unit) {
    val online = now - d.seen < 40 * 60_000
    val bat by animateFloatAsState(d.bat / 100f, tween(900), label = "bat")
    val border by animateColorAsState(if (selected) Violet else Line, label = "border")
    val batColor = if (d.bat < 20) Coral else Emerald
    Column(Modifier.width(170.dp).clip(RoundedCornerShape(22.dp)).background(Glass)
        .border(if (selected) 2.dp else 1.dp, border, RoundedCornerShape(22.dp)).clickable(onClick = onClick).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pulse(Emerald, online)
            Spacer(Modifier.width(8.dp))
            Text(d.name, color = Ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${d.bat}%", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Black)
            if (d.chg) Text("  charging", color = Emerald, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Glass)) {
            Box(Modifier.fillMaxWidth(bat).fillMaxHeight().background(Brush.horizontalGradient(listOf(batColor.copy(alpha = .6f), batColor)), CircleShape))
        }
        Spacer(Modifier.height(8.dp))
        Text(if (online) "Seen ${ago(d.seen, now)}" else "Offline · ${ago(d.seen, now)}", color = if (online) Muted else Amber, fontSize = 12.sp)
    }
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit) =
    Row(Modifier.fillMaxWidth().glass(18.dp).padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Search, null, tint = Muted)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search messages", color = Muted, fontSize = 16.sp)
            BasicTextField(query, onQuery, Modifier.fillMaxWidth(), singleLine = true,
                textStyle = TextStyle(color = Ink, fontSize = 16.sp), cursorBrush = SolidColor(Violet))
        }
        if (query.isNotEmpty()) Icon(Icons.Default.Close, "Clear search", Modifier.clickable { onQuery("") }, tint = Muted)
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Chip(text: String, sub: String?, color: Color, selected: Boolean, onClick: () -> Unit, onLong: (() -> Unit)? = null) {
    val bg by animateColorAsState(if (selected) color else Glass, spring(stiffness = Spring.StiffnessMediumLow), label = "bg")
    val fg by animateColorAsState(if (selected) Bg else Ink, label = "fg")
    val s by animateFloatAsState(if (selected) 1.05f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "s")
    Row(Modifier.graphicsLayer { scaleX = s; scaleY = s }.clip(CircleShape).background(bg)
        .border(1.dp, if (selected) Color.Transparent else Line, CircleShape)
        .combinedClickable(onClick = onClick, onLongClick = onLong).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(if (selected) fg else color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (sub != null) Text("  $sub", color = fg.copy(alpha = .65f), fontSize = 12.sp)
    }
}

@Composable
private fun MsgCard(m: Msg, look: Look, modifier: Modifier, onClick: () -> Unit) {
    val c = look.color(m.sim)
    val code = remember(m.body) { otp(m.body) }
    Row(modifier.fillMaxWidth().padding(vertical = 5.dp).glass(22.dp).clickable(onClick = onClick).padding(14.dp)) {
        Avatar(m.from, c)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.from, Modifier.weight(1f), color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(clock(m.t), color = Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(3.dp))
            Text(m.body, color = Ink.copy(alpha = .72f), fontSize = 14.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SimTag(if (code != null) look.title(m) else "${look.title(m)} · ${look.phone(m.dev)}", c, Modifier.weight(1f, fill = false))
                if (code != null) { Spacer(Modifier.weight(1f).width(8.dp)); OtpPill(code, c) }
            }
        }
    }
}

@Composable
private fun SimTag(text: String, c: Color, modifier: Modifier = Modifier) =
    Row(modifier.clip(CircleShape).background(c.copy(alpha = .14f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(c, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, color = c, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

@Composable
private fun Empty(nothingYet: Boolean) = Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Orbit()
    Spacer(Modifier.height(14.dp))
    Text(if (nothingYet) "No messages yet" else "Nothing matches", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    Text(if (nothingYet) "Tap + to link your other phones." else "Try another SIM or search word.", color = Muted, fontSize = 14.sp)
}

@Composable
private fun ThreadView(sim: String, from: String, msgs: List<Msg>, look: Look, onBack: () -> Unit) {
    val list = remember(msgs, sim, from) { msgs.filter { it.sim == sim && it.from == from } }
    val c = look.color(sim)
    val first = list.firstOrNull()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
            Spacer(Modifier.width(12.dp))
            Avatar(from, c, 42.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(from, color = Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (first != null) Text("${look.title(first)} · ${look.phone(first.dev)}", color = c, fontSize = 12.sp)
            }
        }
        LazyColumn(Modifier.weight(1f), reverseLayout = true, contentPadding = PaddingValues(horizontal = 16.dp)) {
            item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
            items(list, key = { it.id }) { m -> Bubble(m, c, Modifier.animateItem()) }
        }
    }
}

@Composable
private fun Bubble(m: Msg, c: Color, modifier: Modifier) {
    val shape = RoundedCornerShape(topStart = 6.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = 22.dp)
    val code = remember(m.body) { otp(m.body) }
    Column(modifier.padding(vertical = 5.dp).fillMaxWidth(.9f).clip(shape).background(Glass)
        .border(1.dp, Brush.linearGradient(listOf(c.copy(alpha = .6f), Line)), shape).padding(14.dp)) {
        SelectionContainer { Text(m.body, color = Ink, fontSize = 15.sp, lineHeight = 21.sp) }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fullTime(m.t), Modifier.weight(1f), color = Muted, fontSize = 11.sp)
            if (code != null) OtpPill(code, c)
        }
    }
}
