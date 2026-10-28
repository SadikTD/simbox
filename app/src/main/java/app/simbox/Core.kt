package app.simbox

import android.annotation.SuppressLint
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.provider.Telephony
import android.telephony.SubscriptionManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            NotificationChannel("sms", "Messages", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel("watch", "Background sync", NotificationManager.IMPORTANCE_MIN),
        ))
    }
}

// ---------- local settings ----------

object Store {
    private lateinit var p: SharedPreferences
    fun init(ctx: Context) { if (!::p.isInitialized) p = ctx.applicationContext.getSharedPreferences("simbox", 0) }
    private fun str(k: String) = p.getString(k, null)
    private fun put(k: String, v: String?) = p.edit().putString(k, v).apply()
    private fun long(k: String) = p.getLong(k, 0)
    private fun putLong(k: String, v: Long) = p.edit().putLong(k, v).apply()

    var room: String? get() = str("room"); set(v) = put("room", v)
    var key: String? get() = str("key"); set(v) = put("key", v)
    var role: String? get() = str("role"); set(v) = put("role", v)
    var name: String get() = str("name") ?: Build.MODEL; set(v) = put("name", v.trim().ifBlank { Build.MODEL })
    val deviceId: String get() = str("id") ?: UUID.randomUUID().toString().replace("-", "").take(12).also { put("id", it) }
    var lastNotified: Long get() = long("ln"); set(v) = putLong("ln", v)
    var lastUpload: Long get() = long("lu"); set(v) = putLong("lu", v)
    var synced: Int get() = p.getInt("sy", 0); set(v) = p.edit().putInt("sy", v).apply()
    var backfilled: Boolean get() = p.getBoolean("bf", false); set(v) = p.edit().putBoolean("bf", v).apply()

    fun label(sim: String): String? = str("label:$sim")
    fun setLabel(sim: String, v: String) = put("label:$sim", v.trim().ifBlank { null })

    fun pairing() = "simbox1|$room|$key"
    fun create() { room = rand(18); key = rand(32); role = "main" }
    fun join(code: String): Boolean {
        val x = code.trim().split("|")
        if (x.size != 3 || x[0] != "simbox1" || x[1].length < 20) return false
        room = x[1]; key = x[2]; role = "relay"; backfilled = false
        return true
    }
    fun unlink() = p.edit().remove("room").remove("key").remove("role").remove("bf").remove("ln").remove("sy").apply()
}

// ---------- end-to-end encryption (AES-256-GCM, key only lives on paired phones) ----------

private val b64e = Base64.getUrlEncoder().withoutPadding()
private val b64d = Base64.getUrlDecoder()
fun rand(n: Int): String = b64e.encodeToString(ByteArray(n).also(SecureRandom()::nextBytes))

object Crypto {
    private fun cipher(mode: Int, key: String, iv: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding")
        .apply { init(mode, SecretKeySpec(b64d.decode(key), "AES"), GCMParameterSpec(128, iv)) }

    fun enc(plain: String, key: String = Store.key!!): String {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        return b64e.encodeToString(iv + cipher(Cipher.ENCRYPT_MODE, key, iv).doFinal(plain.toByteArray()))
    }

    fun dec(data: String, key: String = Store.key!!): String? = runCatching {
        val b = b64d.decode(data)
        String(cipher(Cipher.DECRYPT_MODE, key, b.copyOf(12)).doFinal(b, 12, b.size - 12))
    }.getOrNull()
}

// ---------- OTP detection ----------

private val otpHint = Regex("\\b(otp|code|pin|password|passcode)\\b|verif|কোড|পিন", RegexOption.IGNORE_CASE)
private val otpNum = Regex("(?<!\\d)\\d{4,8}(?!\\d)")

/** The one-time code in an SMS, if it looks like one: the first 4-8 digit number after the hint word, else the first in the text. */
fun otp(body: String): String? {
    val hint = otpHint.find(body) ?: return null
    return (otpNum.find(body, hint.range.first) ?: otpNum.find(body))?.value
}

// ---------- SIM info ----------

data class Sim(val slot: Int, val carrier: String)

@SuppressLint("MissingPermission")
fun activeSims(ctx: Context): List<Sim> = runCatching {
    ctx.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
        .map { Sim(it.simSlotIndex, it.carrierName?.toString().orEmpty()) }
}.getOrDefault(emptyList())

@SuppressLint("MissingPermission")
fun simFor(ctx: Context, subId: Int): Sim {
    val info = runCatching { ctx.getSystemService(SubscriptionManager::class.java).getActiveSubscriptionInfo(subId) }.getOrNull()
    if (info != null) return Sim(info.simSlotIndex, info.carrierName?.toString().orEmpty())
    return activeSims(ctx).singleOrNull() ?: Sim(-1, "")
}

fun simTitle(key: String, slot: Int, carrier: String) =
    Store.label(key) ?: "${carrier.ifBlank { "SIM" }} ${if (slot < 0) "?" else slot + 1}"

// ---------- Firestore ----------

private fun roomRef() = FirebaseFirestore.getInstance().collection("rooms").document(Store.room!!)
fun msgsRef() = roomRef().collection("msgs")
fun devsRef() = roomRef().collection("devices")

/** Doc id is a hash of the message, so the live receiver and the history backfill never create duplicates. */
fun msgDoc(from: String, body: String, t: Long, sim: Sim, backfill: Boolean = false): Pair<String, Map<String, Any>> {
    val id = MessageDigest.getInstance("SHA-256").digest("${Store.deviceId}|$from|$t|$body".toByteArray())
        .joinToString("") { "%02x".format(it) }.take(24)
    val c = Crypto.enc(JSONObject().put("f", from).put("b", body).put("s", sim.slot).put("k", sim.carrier).toString())
    val m = mutableMapOf<String, Any>("d" to Store.deviceId, "t" to t, "c" to c, "u" to FieldValue.serverTimestamp())
    if (backfill) m["bf"] = true
    return id to m
}

fun heartbeat(ctx: Context): Task<Void> {
    val bm = ctx.getSystemService(BatteryManager::class.java)
    val sims = JSONArray(activeSims(ctx).map { JSONObject().put("s", it.slot).put("k", it.carrier) })
    val info = JSONObject().put("n", Store.name).put("bat", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
        .put("chg", bm.isCharging).put("sims", sims).put("role", Store.role)
    return devsRef().document(Store.deviceId).set(mapOf("t" to System.currentTimeMillis(), "c" to Crypto.enc(info.toString())))
}

/** Uploads every SMS already in this phone's inbox, so old messages show on the main phone too. Safe to re-run: ids dedupe. */
fun backfill(ctx: Context): Int {
    var n = 0
    var batch = FirebaseFirestore.getInstance().batch()
    var inBatch = 0
    ctx.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, null, null, null, "date DESC")?.use { c ->
        val iAddr = c.getColumnIndex("address"); val iBody = c.getColumnIndex("body")
        val iSent = c.getColumnIndex("date_sent"); val iDate = c.getColumnIndex("date"); val iSub = c.getColumnIndex("sub_id")
        while (c.moveToNext()) {
            val sent = if (iSent >= 0) c.getLong(iSent) else 0
            val sim = if (iSub >= 0) simFor(ctx, c.getInt(iSub)) else Sim(-1, "")
            val (id, m) = msgDoc(c.getString(iAddr) ?: "Unknown", c.getString(iBody) ?: "", if (sent > 0) sent else c.getLong(iDate), sim, true)
            batch.set(msgsRef().document(id), m)
            n++; inBatch++
            if (inBatch == 400) {
                Tasks.await(batch.commit(), 60, TimeUnit.SECONDS)
                Store.synced = n
                batch = FirebaseFirestore.getInstance().batch(); inBatch = 0
            }
        }
    }
    if (inBatch > 0) Tasks.await(batch.commit(), 60, TimeUnit.SECONDS)
    Store.synced = n
    return n
}

class BackfillWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        Store.init(applicationContext)
        if (Store.room == null || Store.backfilled) return Result.success()
        return runCatching { backfill(applicationContext); Store.backfilled = true }
            .fold({ Result.success() }, { Result.retry() })
    }
}

// ---------- relay: catch SMS -> upload ----------

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        Store.init(ctx)
        if (Store.room == null) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        if (parts.isEmpty()) return
        val subId = intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", intent.getIntExtra("subscription", -1))
        val sim = simFor(ctx, subId)
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf(
                "f" to (parts[0].displayOriginatingAddress ?: "Unknown"),
                "b" to parts.joinToString("") { it.displayMessageBody.orEmpty() },
                "t" to parts[0].timestampMillis, "s" to sim.slot, "k" to sim.carrier,
            ))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 20, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueue(req)
    }
}

class UploadWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        Store.init(applicationContext)
        if (Store.room == null) return Result.success()
        val d = inputData
        val (id, m) = msgDoc(d.getString("f")!!, d.getString("b")!!, d.getLong("t", 0), Sim(d.getInt("s", -1), d.getString("k").orEmpty()))
        return try {
            Tasks.await(msgsRef().document(id).set(m), 30, TimeUnit.SECONDS)
            Store.lastUpload = System.currentTimeMillis()
            heartbeat(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

class HeartbeatWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        Store.init(applicationContext)
        if (Store.room == null) return Result.success()
        return runCatching { Tasks.await(heartbeat(applicationContext), 30, TimeUnit.SECONDS) }
            .fold({ Result.success() }, { Result.retry() })
    }
}

fun startBackground(ctx: Context) {
    if (!Store.backfilled) WorkManager.getInstance(ctx).enqueueUniqueWork("backfill", ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<BackfillWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS).build())
    WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("hb", ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    if (Store.role == "main") ContextCompat.startForegroundService(ctx, Intent(ctx, WatchService::class.java))
}

fun stopBackground(ctx: Context) {
    WorkManager.getInstance(ctx).cancelUniqueWork("hb")
    WorkManager.getInstance(ctx).cancelUniqueWork("backfill")
    ctx.stopService(Intent(ctx, WatchService::class.java))
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        if (Store.room != null) startBackground(ctx)
    }
}

// ---------- viewer: live data ----------

data class Msg(
    val id: String, val dev: String, val from: String, val body: String, val t: Long,
    val slot: Int, val carrier: String, val backfill: Boolean, val uploaded: Long,
) { val sim get() = "$dev:$slot" }

data class Device(val id: String, val name: String, val bat: Int, val chg: Boolean, val sims: List<Sim>, val seen: Long)

object Repo {
    val msgs = MutableStateFlow<List<Msg>>(emptyList())
    val devices = MutableStateFlow<Map<String, Device>>(emptyMap())
    val error = MutableStateFlow<String?>(null)
    val hasMore = MutableStateFlow(true)
    val loading = MutableStateFlow(false)
    var onNew: ((Msg) -> Unit)? = null
    private const val PAGE = 300L
    private var regs: List<ListenerRegistration> = emptyList()
    private val all = HashMap<String, Msg>()
    private var oldest: DocumentSnapshot? = null

    private fun merge(docs: List<DocumentSnapshot>) {
        docs.forEach { d -> parseMsg(d)?.let { all[d.id] = it } }
        docs.lastOrNull()?.let { last -> if ((last.getLong("t") ?: 0) < (oldest?.getLong("t") ?: Long.MAX_VALUE)) oldest = last }
        msgs.value = all.values.sortedByDescending { it.t }
    }

    /** Live: newest page, kept in sync (also drives notifications). Older pages load on demand via [loadMore]. */
    fun start() {
        if (regs.isNotEmpty() || Store.room == null) return
        regs = listOf(
            msgsRef().orderBy("t", Query.Direction.DESCENDING).limit(PAGE).addSnapshotListener { s, e ->
                error.value = e?.message
                if (s == null) return@addSnapshotListener
                if (oldest == null) hasMore.value = s.size() >= PAGE
                merge(s.documents)
                s.documentChanges.filter { it.type == DocumentChange.Type.ADDED }
                    .mapNotNull { parseMsg(it.document) }.sortedBy { it.uploaded }
                    .forEach { onNew?.invoke(it) }
            },
            devsRef().addSnapshotListener { s, e ->
                if (e != null) error.value = e.message
                if (s != null) devices.value = s.documents.mapNotNull(::parseDev).associateBy { it.id }
            },
        )
    }

    fun loadMore() {
        val after = oldest ?: return
        if (loading.value || !hasMore.value) return
        loading.value = true
        msgsRef().orderBy("t", Query.Direction.DESCENDING).startAfter(after).limit(PAGE).get()
            .addOnSuccessListener { s -> hasMore.value = s.size() >= PAGE; merge(s.documents); loading.value = false }
            .addOnFailureListener { e -> error.value = e.message; loading.value = false }
    }

    fun stop() {
        regs.forEach { it.remove() }
        regs = emptyList(); all.clear(); oldest = null
        msgs.value = emptyList(); devices.value = emptyMap(); error.value = null; hasMore.value = true
    }

    private fun parseMsg(d: DocumentSnapshot): Msg? {
        val j = JSONObject(Crypto.dec(d.getString("c") ?: return null) ?: return null)
        return Msg(d.id, d.getString("d") ?: return null, j.optString("f"), j.optString("b"), d.getLong("t") ?: 0,
            j.optInt("s", -1), j.optString("k"), d.getBoolean("bf") == true, d.getTimestamp("u")?.toDate()?.time ?: 0)
    }

    private fun parseDev(d: DocumentSnapshot): Device? {
        val j = JSONObject(Crypto.dec(d.getString("c") ?: return null) ?: return null)
        val sims = j.optJSONArray("sims") ?: JSONArray()
        return Device(d.id, j.optString("n", "Phone"), j.optInt("bat"), j.optBoolean("chg"),
            List(sims.length()) { sims.getJSONObject(it).let { s -> Sim(s.optInt("s", -1), s.optString("k")) } },
            d.getLong("t") ?: 0)
    }
}

// ---------- main phone: always-on listener that turns new messages into notifications ----------

fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 0,
    Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)

class WatchService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        val n = NotificationCompat.Builder(this, "watch").setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Watching your phones").setOngoing(true).setContentIntent(openApp(this)).build()
        ServiceCompat.startForeground(this, 1, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        if (Store.lastNotified == 0L) Store.lastNotified = System.currentTimeMillis()
        Repo.onNew = { m ->
            if (m.dev != Store.deviceId && !m.backfill && m.uploaded > Store.lastNotified) {
                Store.lastNotified = m.uploaded
                notifyMsg(m)
            }
        }
        Repo.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    @SuppressLint("MissingPermission")
    private fun notifyMsg(m: Msg) {
        val n: Notification = NotificationCompat.Builder(this, "sms").setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(m.from).setContentText(m.body)
            .setSubText("${simTitle(m.sim, m.slot, m.carrier)} · ${Repo.devices.value[m.dev]?.name ?: "Phone"}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(m.body))
            .setColor(0xFF8B5CF6.toInt()).setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true).setContentIntent(openApp(this)).build()
        getSystemService(NotificationManager::class.java).notify(m.id.hashCode(), n)
    }
}
