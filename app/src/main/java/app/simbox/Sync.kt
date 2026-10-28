package app.simbox

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// ---------- models ----------

data class Msg(
    val id: String, val dev: String, val src: String, val body: String, val t: Long,
    val slot: Int, val carrier: String, val u: Long, val cat: String, val amt: String?,
    val read: Boolean, val star: Boolean, val hidden: Boolean, val bf: Boolean = false,
) {
    val sim get() = "$dev:$slot"
    val chat get() = Chat(dev, slot, src)
}

data class Chat(val dev: String, val slot: Int, val src: String) { val sim get() = "$dev:$slot" }
data class Conv(val last: Msg, val count: Int, val unread: Int)
data class Device(
    val id: String, val name: String, val bat: Int, val chg: Boolean, val sims: List<Sim>,
    val seen: Long, val removed: Boolean, val ping: Long, val ack: Long, val version: String,
) {
    val title get() = Store.deviceLabel(id) ?: name
    fun online(now: Long = System.currentTimeMillis()) = now - seen < 40 * 60_000
}

/** Which messages a list shows. kind: all, unread, otp, money, personal, promo, starred, archived, muted. */
data class Filter(val dev: String? = null, val sim: String? = null, val kind: String = "all", val query: String = "")

// ---------- local database (main phone keeps every message, so search and history work offline) ----------

class Db(ctx: Context) : SQLiteOpenHelper(ctx, "simbox.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE msgs(id TEXT PRIMARY KEY, dev TEXT, src TEXT, k10 TEXT, body TEXT, t INTEGER, slot INTEGER,
            carrier TEXT, u INTEGER, cat TEXT, amt TEXT, rd INTEGER DEFAULT 0, star INTEGER DEFAULT 0, hid INTEGER DEFAULT 0)""")
        db.execSQL("CREATE INDEX msgs_t ON msgs(t DESC)")
        db.execSQL("CREATE INDEX msgs_thread ON msgs(dev, slot, src, t)")
        db.execSQL("CREATE INDEX msgs_cat ON msgs(cat, t)")
        db.execSQL("CREATE INDEX msgs_k10 ON msgs(k10)")
        db.execSQL("CREATE TABLE muted(k TEXT PRIMARY KEY)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit

    /** Inserts new messages and refreshes changed ones without touching read/star/archive state. Returns the new ones. */
    fun upsert(list: List<Msg>): List<Msg> {
        val fresh = mutableListOf<Msg>()
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (m in list) {
                val cv = ContentValues().apply {
                    put("id", m.id); put("dev", m.dev); put("src", m.src); put("k10", digits10(m.src)); put("body", m.body)
                    put("t", m.t); put("slot", m.slot); put("carrier", m.carrier); put("u", m.u); put("cat", m.cat); put("amt", m.amt)
                }
                if (db.insertWithOnConflict("msgs", null, cv.apply { put("rd", if (m.bf) 1 else 0) }, SQLiteDatabase.CONFLICT_IGNORE) != -1L) fresh += m
                else db.update("msgs", cv.apply { remove("rd") }, "id=?", arrayOf(m.id))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return fresh
    }

    private fun where(f: Filter): Pair<String, Array<String>> {
        val c = mutableListOf<String>(); val a = mutableListOf<String>()
        c += if (f.kind == "archived") "hid=1" else "hid=0"
        c += if (f.kind == "muted") "src IN (SELECT k FROM muted)" else if (f.kind == "archived") "1=1" else "src NOT IN (SELECT k FROM muted)"
        when (f.kind) {
            "unread" -> c += "rd=0"
            "starred" -> c += "star=1"
            "otp", "money", "personal", "promo" -> { c += "cat=?"; a += f.kind }
        }
        f.dev?.let { c += "dev=?"; a += it }
        f.sim?.let { val i = it.lastIndexOf(':'); c += "dev=? AND slot=?"; a += it.substring(0, i); a += it.substring(i + 1) }
        if (f.query.isNotBlank()) {
            val q = "%${f.query.trim()}%"
            val names = Sync.numbersNamed(f.query.trim())
            c += "(body LIKE ? OR src LIKE ?" + (if (names.isEmpty()) "" else " OR k10 IN (${names.joinToString(",") { "?" }})") + ")"
            a += q; a += q; a += names
        }
        return c.joinToString(" AND ") to a.toTypedArray()
    }

    private fun Cursor.msg() = Msg(
        getString(getColumnIndexOrThrow("id")), getString(getColumnIndexOrThrow("dev")), getString(getColumnIndexOrThrow("src")),
        getString(getColumnIndexOrThrow("body")), getLong(getColumnIndexOrThrow("t")), getInt(getColumnIndexOrThrow("slot")),
        getString(getColumnIndexOrThrow("carrier")), getLong(getColumnIndexOrThrow("u")), getString(getColumnIndexOrThrow("cat")),
        getString(getColumnIndexOrThrow("amt")), getInt(getColumnIndexOrThrow("rd")) == 1, getInt(getColumnIndexOrThrow("star")) == 1,
        getInt(getColumnIndexOrThrow("hid")) == 1,
    )

    private fun <T> rows(sql: String, args: Array<String>, map: Cursor.() -> T): List<T> =
        readableDatabase.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(c.map()) } }

    fun timeline(f: Filter, limit: Int): List<Msg> {
        val (w, a) = where(f)
        return rows("SELECT * FROM msgs WHERE $w ORDER BY t DESC, u DESC LIMIT $limit", a) { msg() }
    }

    fun convs(f: Filter, limit: Int): List<Conv> {
        val (w, a) = where(f)
        // SQLite returns the bare columns from the row holding MAX(t): the latest message of each thread
        return rows("SELECT *, MAX(t) AS mt, COUNT(*) AS n, SUM(rd=0) AS un FROM msgs WHERE $w GROUP BY dev, slot, src ORDER BY mt DESC, MAX(u) DESC LIMIT $limit", a) {
            Conv(msg(), getInt(getColumnIndexOrThrow("n")), getInt(getColumnIndexOrThrow("un")))
        }
    }

    fun chat(t: Chat): List<Msg> =
        rows("SELECT * FROM msgs WHERE dev=? AND slot=? AND src=? ORDER BY t DESC", arrayOf(t.dev, t.slot.toString(), t.src)) { msg() }

    fun countNewer(f: Filter, since: Long): Int {
        val (w, a) = where(f)
        return rows("SELECT COUNT(*) FROM msgs WHERE $w AND t >= ?", a + since.toString()) { getInt(0) }.first()
    }

    /** sim key -> (total, unread), excluding archived and muted. */
    fun simStats(): Map<String, Pair<Int, Int>> =
        rows("SELECT dev, slot, COUNT(*), SUM(rd=0) FROM msgs WHERE hid=0 AND src NOT IN (SELECT k FROM muted) GROUP BY dev, slot", emptyArray()) {
            "${getString(0)}:${getInt(1)}" to (getInt(2) to getInt(3))
        }.toMap()

    fun latestOtps(n: Int) = rows("SELECT * FROM msgs WHERE cat='otp' AND hid=0 ORDER BY t DESC LIMIT $n", emptyArray()) { msg() }
    fun byId(id: String) = rows("SELECT * FROM msgs WHERE id=?", arrayOf(id)) { msg() }.firstOrNull()

    private fun exec(sql: String, vararg a: Any?) = writableDatabase.execSQL(sql, a)
    fun setRead(id: String, v: Boolean) = exec("UPDATE msgs SET rd=? WHERE id=?", if (v) 1 else 0, id)
    fun setChatRead(t: Chat, v: Boolean) = exec("UPDATE msgs SET rd=? WHERE dev=? AND slot=? AND src=?", if (v) 1 else 0, t.dev, t.slot, t.src)
    fun markAllRead() = exec("UPDATE msgs SET rd=1 WHERE rd=0")
    fun setStar(id: String, v: Boolean) = exec("UPDATE msgs SET star=? WHERE id=?", if (v) 1 else 0, id)
    fun setHidden(id: String, v: Boolean) = exec("UPDATE msgs SET hid=? WHERE id=?", if (v) 1 else 0, id)
    fun setChatHidden(t: Chat, v: Boolean) = exec("UPDATE msgs SET hid=? WHERE dev=? AND slot=? AND src=?", if (v) 1 else 0, t.dev, t.slot, t.src)
    fun setMuted(src: String, v: Boolean) = if (v) exec("INSERT OR IGNORE INTO muted(k) VALUES(?)", src) else exec("DELETE FROM muted WHERE k=?", src)
    fun isMuted(src: String) = rows("SELECT 1 FROM muted WHERE k=?", arrayOf(src)) { 1 }.isNotEmpty()
    fun deleteDevice(dev: String) = exec("DELETE FROM msgs WHERE dev=?", dev)
    fun clear() { exec("DELETE FROM msgs"); exec("DELETE FROM muted") }
}

// ---------- sync engine (main phone) ----------

object Sync {
    lateinit var db: Db
    private lateinit var app: Context
    val version = MutableStateFlow(0)
    val devices = MutableStateFlow<Map<String, Device>>(emptyMap())
    val error = MutableStateFlow<String?>(null)
    val catchingUp = MutableStateFlow(true)
    val fetched = MutableStateFlow(0)
    val linked = MutableSharedFlow<String>(extraBufferCapacity = 4)
    @Volatile private var contacts: Map<String, Map<String, String>> = emptyMap()
    @Volatile private var ownContacts: Map<String, String> = emptyMap()
    val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var msgReg: ListenerRegistration? = null
    private var devReg: ListenerRegistration? = null
    private var conReg: ListenerRegistration? = null
    private const val PAGE = 500L

    fun init(ctx: Context) {
        if (::db.isInitialized) return
        app = ctx.applicationContext
        Store.init(app)
        db = Db(app)
    }

    fun bump() = version.value++
    fun later(block: () -> Unit) = io.execute { block(); bump() }

    fun start(ctx: Context) {
        init(ctx)
        if (devReg != null || Store.room == null || Store.role != "main") return
        io.execute { cleanupSelf(); loadOwnContacts() }
        listenMsgs()
        devReg = devsRef().addSnapshotListener { s, e ->
            if (e != null) { error.value = e.message; return@addSnapshotListener }
            if (s == null) return@addSnapshotListener
            val list = s.documents.mapNotNull(::parseDev).filter { it.id != Store.deviceId }
            devices.value = list.filter { !it.removed }.associateBy { it.id }
            io.execute { onDevices(list) }
        }
        conReg = contactsRef().addSnapshotListener { s, _ ->
            s ?: return@addSnapshotListener
            io.execute {
                contacts = s.documents.associate { d ->
                    d.id to (Crypto.dec(d.getString("c").orEmpty())?.let { j -> JSONObject(j).let { o -> o.keys().asSequence().associateWith(o::getString) } } ?: emptyMap())
                }
                bump()
            }
        }
    }

    fun stop() {
        listOf(msgReg, devReg, conReg).forEach { it?.remove() }
        msgReg = null; devReg = null; conReg = null
        devices.value = emptyMap(); error.value = null; catchingUp.value = true; fetched.value = 0
    }

    /** Everything uploaded after our cursor, 500 at a time; the last page stays open as the live listener. */
    private fun listenMsgs() {
        msgReg?.remove()
        msgReg = msgsRef().whereGreaterThan("u", Timestamp(Date(Store.syncCursor))).orderBy("u").limit(PAGE)
            .addSnapshotListener { s, e ->
                if (e != null) { error.value = e.message; return@addSnapshotListener }
                if (s == null || s.metadata.hasPendingWrites()) return@addSnapshotListener
                error.value = null
                val docs = s.documentChanges.filter { it.type != DocumentChange.Type.REMOVED }.map { it.document }
                val full = s.size() >= PAGE
                val maxU = s.documents.maxOfOrNull { it.getTimestamp("u")?.toDate()?.time ?: 0 } ?: 0
                io.execute {
                    val parsed = docs.mapNotNull(::parseMsg)
                    val fresh = db.upsert(parsed.filter { it.dev != Store.deviceId })
                    docs.filter { it.getBoolean("test") == true }.forEach { it.reference.delete() }
                    if (maxU > Store.syncCursor) Store.syncCursor = maxU
                    fetched.value += fresh.size
                    catchingUp.value = full
                    bump()
                    onFresh(fresh)
                    if (full) main.post { listenMsgs() }
                }
            }
    }

    private fun onFresh(fresh: List<Msg>) {
        val notify = fresh.filter { !it.bf && it.u > Store.lastNotified && !db.isMuted(it.src) }.sortedBy { it.u }
        notify.forEach { Notify.message(app, it) }
        notify.maxOfOrNull { it.u }?.let { Store.lastNotified = it }
        if (fresh.any { it.cat == "otp" }) OtpWidget.refresh(app)
    }

    private fun onDevices(list: List<Device>) {
        val known = Store.get("known")?.split(",")?.toMutableSet()
        val firstRun = known == null
        val set = known ?: mutableSetOf()
        for (d in list.filter { !it.removed }) {
            if (d.id !in set) { set += d.id; if (!firstRun) { linked.tryEmit(d.title); Notify.alert(app, "linked:${d.id}", "${d.title} is linked", "Its messages will appear in your inbox.") } }
            if (d.ping > d.ack) {
                devsRef().document(d.id).update("ack", d.ping)
                Notify.alert(app, "ping:${d.id}", "Test from ${d.title} received", "This phone is sending messages correctly.")
            }
        }
        Store.set("known", set.joinToString(","))
        Health.check(app, list.filter { !it.removed })
    }

    /** Older builds made the main phone relay too; remove that phone's leftover entry and messages once. */
    private fun cleanupSelf() {
        if (Store.flag("cs")) return
        runCatching {
            val me = Store.deviceId
            Tasks.await(devsRef().document(me).delete(), 30, TimeUnit.SECONDS)
            Tasks.await(contactsRef().document(me).delete(), 30, TimeUnit.SECONDS)
            while (true) {
                val s = Tasks.await(msgsRef().whereEqualTo("d", me).limit(400).get(), 60, TimeUnit.SECONDS)
                if (s.isEmpty) break
                val b = FirebaseFirestore.getInstance().batch(); s.documents.forEach { b.delete(it.reference) }
                Tasks.await(b.commit(), 60, TimeUnit.SECONDS)
            }
            db.deleteDevice(me)
            Store.setFlag("cs", true)
        }
    }

    /** Deletes cloud copies older than the retention window, but only ones this phone already has. */
    fun cleanupCloud() {
        val days = Store.retentionDays
        val now = System.currentTimeMillis()
        if (days <= 0 || now - Store.lastCleanup < 12 * 3_600_000 || catchingUp.value) return
        runCatching {
            val cutoff = minOf(now - days * 86_400_000L, Store.syncCursor)
            repeat(25) {
                val s = Tasks.await(msgsRef().whereLessThan("u", Timestamp(Date(cutoff))).limit(400).get(), 60, TimeUnit.SECONDS)
                if (s.isEmpty) return@runCatching
                val b = FirebaseFirestore.getInstance().batch(); s.documents.forEach { b.delete(it.reference) }
                Tasks.await(b.commit(), 60, TimeUnit.SECONDS)
            }
        }.onSuccess { Store.lastCleanup = now }
    }

    @SuppressLint("Range")
    fun loadOwnContacts() {
        if (!granted(app, Manifest.permission.READ_CONTACTS)) return
        val map = HashMap<String, String>()
        app.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) { val k = digits10(c.getString(0) ?: continue) ?: continue; map[k] = c.getString(1) ?: continue }
        }
        ownContacts = map
        bump()
    }

    /** Contact name for a sender: the relay phone's contacts first, then this phone's. */
    fun nameOf(dev: String, src: String): String? = digits10(src)?.let { contacts[dev]?.get(it) ?: ownContacts[it] }
    fun display(dev: String, src: String) = nameOf(dev, src) ?: src

    fun numbersNamed(q: String): List<String> {
        if (q.length < 2) return emptyList()
        return (contacts.values.flatMap { it.entries } + ownContacts.entries)
            .filter { it.value.contains(q, true) }.map { it.key }.distinct().take(200)
    }

    private fun parseMsg(d: DocumentSnapshot): Msg? {
        val j = JSONObject(Crypto.dec(d.getString("c") ?: return null) ?: return null)
        val from = j.optString("f"); val body = j.optString("b")
        val k = classify(from, body)
        return Msg(d.id, d.getString("d") ?: return null, from, body, d.getLong("t") ?: 0, j.optInt("s", -1), j.optString("k"),
            d.getTimestamp("u")?.toDate()?.time ?: 0, k.cat, k.amount, false, false, false, d.getBoolean("bf") == true)
    }

    private fun parseDev(d: DocumentSnapshot): Device? {
        val j = JSONObject(Crypto.dec(d.getString("c") ?: return null) ?: return null)
        val sims = j.optJSONArray("sims") ?: JSONArray()
        return Device(d.id, j.optString("n", "Phone"), j.optInt("bat"), j.optBoolean("chg"),
            List(sims.length()) { sims.getJSONObject(it).let { s -> Sim(s.optInt("s", -1), s.optString("k"), s.optString("p")) } },
            d.getLong("t") ?: 0, d.getBoolean("removed") == true, d.getLong("ping") ?: 0, d.getLong("ack") ?: 0, j.optString("v"))
    }
}

// ---------- phone health alerts ----------

object Health {
    fun check(ctx: Context, devs: List<Device>) {
        val now = System.currentTimeMillis()
        for (d in devs) {
            val off = now - d.seen > 60 * 60_000 && d.seen > 0
            if (off && !Store.flag("off:${d.id}")) Notify.alert(ctx, "off:${d.id}", "${d.title} is offline",
                "Last seen ${ago(d.seen, now)}. Its messages will arrive when it reconnects.")
            Store.setFlag("off:${d.id}", off)

            val low = d.bat in 1..14 && !d.chg
            if (low && !Store.flag("bat:${d.id}")) Notify.alert(ctx, "bat:${d.id}", "${d.title} battery is at ${d.bat}%", "Plug it in so messages keep coming.")
            if (low) Store.setFlag("bat:${d.id}", true) else if (d.bat >= 20 || d.chg) Store.setFlag("bat:${d.id}", false)

            val sig = d.sims.sortedBy { it.slot }.joinToString(", ") { "SIM ${it.slot + 1}: ${it.carrier.ifBlank { "unknown" }}" }
            val prev = Store.get("sims:${d.id}")
            if (prev != null && prev != sig) Notify.alert(ctx, "sim:${d.id}", "SIM change on ${d.title}", sig.ifBlank { "No SIM detected" })
            Store.set("sims:${d.id}", sig)
        }
    }
}

// ---------- notifications ----------

object Notify {
    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    fun openThread(ctx: Context, t: Chat?, code: Int): PendingIntent = PendingIntent.getActivity(ctx, code,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP).apply {
            if (t != null) putExtra("dev", t.dev).putExtra("slot", t.slot).putExtra("src", t.src)
        }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun action(ctx: Context, act: String, id: String, code: String? = null) = PendingIntent.getBroadcast(ctx, (act + id).hashCode(),
        Intent(ctx, ActionReceiver::class.java).setAction(act).putExtra("id", id).putExtra("code", code),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    @SuppressLint("MissingPermission")
    fun message(ctx: Context, m: Msg) {
        val phone = Sync.devices.value[m.dev]?.title ?: "Phone"
        val where = "${simTitle(m.sim, m.slot, m.carrier)} · $phone"
        val group = "dev:${m.dev}"
        val code = otp(m.body)
        val b = NotificationCompat.Builder(ctx, "sms").setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(Sync.display(m.dev, m.src)).setContentText(m.body).setSubText(where)
            .setStyle(NotificationCompat.BigTextStyle().bigText(m.body))
            .setColor(0xFF8B5CF6.toInt()).setCategory(NotificationCompat.CATEGORY_MESSAGE).setGroup(group)
            .setWhen(m.t).setShowWhen(true).setAutoCancel(true).setContentIntent(openThread(ctx, m.chat, m.id.hashCode()))
        if (code != null) b.addAction(0, "Copy $code", action(ctx, ActionReceiver.COPY, m.id, code))
        b.addAction(0, "Mark read", action(ctx, ActionReceiver.READ, m.id))
        if (Store.hideOnLock) b.setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(
            NotificationCompat.Builder(ctx, "sms").setSmallIcon(R.drawable.ic_stat).setContentTitle("New message").setContentText(where).build())
        nm(ctx).notify(m.id.hashCode(), b.build())
        nm(ctx).notify(group.hashCode(), NotificationCompat.Builder(ctx, "sms").setSmallIcon(R.drawable.ic_stat)
            .setSubText(phone).setGroup(group).setGroupSummary(true).setAutoCancel(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN).setContentIntent(openThread(ctx, null, group.hashCode())).build())
    }

    @SuppressLint("MissingPermission")
    fun alert(ctx: Context, key: String, title: String, text: String) {
        nm(ctx).notify(key.hashCode(), NotificationCompat.Builder(ctx, "alerts").setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(0xFF8B5CF6.toInt()).setAutoCancel(true).setContentIntent(openThread(ctx, null, key.hashCode())).build())
    }

    fun cancel(ctx: Context, ids: List<String>) = ids.forEach { nm(ctx).cancel(it.hashCode()) }
}

class ActionReceiver : BroadcastReceiver() {
    companion object { const val COPY = "app.simbox.COPY"; const val READ = "app.simbox.READ" }

    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra("id").orEmpty()
        when (intent.action) {
            COPY -> {
                val code = intent.getStringExtra("code") ?: return
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("code", code))
                Toast.makeText(ctx, "Code $code copied", Toast.LENGTH_SHORT).show()
                Sync.init(ctx); Sync.later { Sync.db.setRead(id, true) }
            }
            READ -> { Sync.init(ctx); Sync.later { Sync.db.setRead(id, true) } }
        }
        if (id.isNotEmpty()) Notify.cancel(ctx, listOf(id))
    }
}

// ---------- main phone: always-on sync service ----------

class WatchService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            Sync.io.execute { Health.check(this@WatchService, Sync.devices.value.values.toList()); Sync.cleanupCloud(); checkUpdate() }
            handler.postDelayed(this, 5 * 60_000)
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        val n: Notification = NotificationCompat.Builder(this, "watch").setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Syncing your phones").setOngoing(true).setContentIntent(Notify.openThread(this, null, 0)).build()
        ServiceCompat.startForeground(this, 1, n, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        if (Store.lastNotified == 0L) Store.lastNotified = System.currentTimeMillis()
        Sync.start(this)
        handler.postDelayed(tick, 60_000)
    }

    override fun onDestroy() { handler.removeCallbacks(tick); super.onDestroy() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
}

// ---------- home screen widget: latest one-time codes ----------

class OtpWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = refresh(ctx)

    companion object {
        private val rows = listOf(Triple(R.id.row1, R.id.code1, R.id.from1), Triple(R.id.row2, R.id.code2, R.id.from2), Triple(R.id.row3, R.id.code3, R.id.from3))

        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val cn = ComponentName(ctx, OtpWidget::class.java)
            if (mgr.getAppWidgetIds(cn).isEmpty()) return
            Sync.init(ctx)
            Sync.io.execute {
                val list = Sync.db.latestOtps(3)
                val v = RemoteViews(ctx.packageName, R.layout.widget_otp)
                v.setViewVisibility(R.id.empty, if (list.isEmpty()) View.VISIBLE else View.GONE)
                rows.forEachIndexed { i, (row, code, from) ->
                    val m = list.getOrNull(i)
                    v.setViewVisibility(row, if (m == null) View.GONE else View.VISIBLE)
                    if (m != null) {
                        val c = otp(m.body).orEmpty()
                        v.setTextViewText(code, c)
                        v.setTextViewText(from, "${Sync.display(m.dev, m.src)} · ${clock(m.t)}")
                        v.setOnClickPendingIntent(row, PendingIntent.getBroadcast(ctx, ("w" + m.id).hashCode(),
                            Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.COPY).putExtra("id", m.id).putExtra("code", c),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                    }
                }
                v.setOnClickPendingIntent(R.id.title, Notify.openThread(ctx, null, 99))
                mgr.updateAppWidget(cn, v)
            }
        }
    }
}
