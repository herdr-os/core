package dev.r1ptt.herdr

import android.content.Context
import dev.r1ptt.data.AtomicConfigFile
import dev.r1ptt.data.ConfigCodec
import dev.r1ptt.data.EncryptedConfigPersistence
import dev.r1ptt.data.EventLog
import dev.r1ptt.data.Sealed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import org.json.JSONObject
import java.io.File

/**
 * The R1's link to its Herdr bridge: the pairing (sealed with the Keystore, never shown), the one
 * [HerdState], and the event stream that keeps it current. The stream runs only while a Herdr
 * screen is visible; in Pocket Mode nothing polls in the background, and the deck says how old it is.
 */
class Herdr(private val context: Context) {
    private val pairing = EncryptedConfigPersistence(JSONObject(), AtomicConfigFile(File(context.filesDir, "herdr-identity.enc")), Sealed,
        object : ConfigCodec<JSONObject> {
            override fun encode(value: JSONObject) = value.toString()
            override fun decode(plain: String) = JSONObject(plain)
        })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val refreshes = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null
    @Volatile private var openCall: Call? = null
    @Volatile private var visibleScreens = 0
    private val prefs = context.getSharedPreferences("herdr", Context.MODE_PRIVATE)
    private var plugged = false
    private var background = false


    val state = HerdState().apply { if (paired) setLink(Link.OFFLINE) }
    private val _version = MutableStateFlow(0)
    /** Bumps on every change to [state]; screens re-render from [state] when it moves. */
    val version: StateFlow<Int> = _version
    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 8)
    val notices = _notices.asSharedFlow()
    /** Herdr screens that can show a notice themselves; with none, notices become notifications. */
    @Volatile var screensShowing = 0
    /** Why the last sync failed, in words for the screen; null while it works. */
    var problem: String? = null
        private set

    // Last of the initializers: the launch below runs at once on the main thread, and what it calls
    // reads the fields above.
    init {
        val power = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) = power(i.action == android.content.Intent.ACTION_POWER_CONNECTED)
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_POWER_CONNECTED); addAction(android.content.Intent.ACTION_POWER_DISCONNECTED)
        }
        context.applicationContext.registerReceiver(power, filter, Context.RECEIVER_NOT_EXPORTED)
        // The sticky battery broadcast says whether the R1 was already on power at start.
        val battery = context.applicationContext.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        scope.launch { power((battery?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0) }
    }

    val paired: Boolean get() = pairing.value.optString("token").isNotBlank()
    val bridgeUrl: String get() = pairing.value.optString("url")
    val deviceId: String get() = pairing.value.optString("device_id")
    val scopes: Set<String> get() = pairing.value.optJSONArray("scopes")?.let { a -> (0 until a.length()).map(a::optString).toSet() }.orEmpty()

    fun client(): HerdrClient? = pairing.value.takeIf { paired }?.let { HerdrClient(it.optString("url"), it.optString("token")) }

    /** Blocking: claims [code] at [url] and stores the device token. */
    fun pair(url: String, code: String, name: String) {
        val claim = HerdrClient.claim(url, code, name)
        pairing.update {
            JSONObject().put("url", url.trim().trimEnd('/')).put("token", claim.getString("token"))
                .put("device_id", claim.optString("device_id")).put("scopes", claim.optJSONArray("scopes"))
        }
        EventLog.record("herdr paired as ${claim.optString("device_id")}")
        scope.launch { state.setLink(Link.OFFLINE); changed(); restart(); reconcile() }
    }

    /** Forgets the token and every cached card. */
    fun unpair() {
        pairing.update { JSONObject() }
        forget(null)
        reconcile()
    }

    /** Screens call this from onStart/onStop; the stream runs while at least one is visible. */
    /**
     * SPEC §13 power modes. Pocket (default): sync only while a Herdr screen is on. Desk: plugged
     * in, the stream stays open with the screen off. Connected: the same on battery, by choice.
     */
    val mode: String get() = when { plugged -> "Desk"; connectedOnBattery -> "Connected"; else -> "Pocket" }

    /** Connected Mode: keep the stream and radios up on battery. Costs standby time. */
    var connectedOnBattery: Boolean
        get() = prefs.getBoolean("connected_on_battery", false)
        set(value) { prefs.edit().putBoolean("connected_on_battery", value).apply(); reconcile() }

    /** Say "Claude Code on cerebro: Needs decision" aloud as well. Off by default: it is audible to anyone nearby. */
    var speakAlerts: Boolean
        get() = prefs.getBoolean("speak_alerts", false)
        set(value) { prefs.edit().putBoolean("speak_alerts", value).apply() }

    /** PttService reports external power; Desk Mode follows it. */
    fun power(connected: Boolean) { plugged = connected; reconcile() }

    private fun reconcile() {
        (context.applicationContext as? dev.r1ptt.App)?.radio?.hold("herdr", connectedOnBattery && paired)
        val want = paired && (plugged || connectedOnBattery)
        if (want == background) return
        background = want
        if (want) attach() else detach()
        changed()
    }

    fun attach() { if (visibleScreens++ == 0) restart() }
    fun detach() {
        if (--visibleScreens > 0) return
        visibleScreens = 0
        loop?.cancel(); loop = null
        openCall?.cancel()
        if (paired) { state.setLink(Link.OFFLINE); changed() }
    }

    /** Fetches a fresh snapshot soon; repeated requests collapse into one. */
    fun refresh() { refreshes.trySend(Unit) }

    private fun restart() {
        loop?.cancel(); openCall?.cancel()
        if (!paired || visibleScreens == 0) return
        loop = scope.launch {
            launch { for (r in refreshes) runCatching { sync() }.onFailure { if (it !is kotlinx.coroutines.CancellationException) lost(it.message) } }
            var backoff = 1_000L
            while (isActive) {
                val client = client() ?: return@launch
                try {
                    sync(client)
                    if (state.link == Link.UNPAIRED) return@launch
                    backoff = 1_000L
                    withContext(Dispatchers.IO) {
                        client.events(state.cursor, onOpen = { call -> openCall = call; if (visibleScreens == 0) call.cancel() }, onEvent = { e ->
                            scope.launch { if (state.accept(e)) refresh() }
                        }, onControl = { kind ->
                            scope.launch { if (kind == "revoked") forget("This R1's access was revoked on the bridge.") else refresh() }
                        })
                    }
                } catch (e: BridgeError) {
                    if (e.status == 401) { forget("This R1's access was revoked on the bridge."); return@launch }
                    lost(e.message)
                } catch (e: Exception) {
                    if (!isActive) return@launch
                    lost(e.message)
                }
                if (!isActive) return@launch
                state.setLink(Link.RECONNECTING); changed()
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private suspend fun sync(client: HerdrClient? = client()) {
        client ?: return
        try {
            val snap = withContext(Dispatchers.IO) { client.snapshot() }
            val notices = state.apply(snap)
            state.setLink(Link.LIVE)
            problem = null
            changed()
            notices.forEach { if (screensShowing > 0) _notices.tryEmit(it) else notify(it); if (speakAlerts) speak(it.text) }
        } catch (e: BridgeError) {
            if (e.status == 401) forget("This R1's access was revoked on the bridge.") else throw e
        }
    }

    private fun lost(message: String?) {
        problem = message ?: "Can't reach the bridge"
        EventLog.record("herdr stream lost: $problem")
    }

    /** A revoked or unpaired device keeps nothing it could show: the token and all cached cards go. */
    private fun forget(reason: String?) {
        if (reason != null) runCatching { pairing.update { JSONObject() } }
        loop?.cancel(); loop = null; openCall?.cancel()
        state.clear()
        state.setLink(Link.UNPAIRED)
        problem = reason
        changed()
        reconcile()
    }

    private fun changed() { _version.value++ }

    /** A card that now needs you, with no Herdr screen on: an Android notification, private on the lock screen. */
    internal fun notify(n: Notice) {
        val app = context.applicationContext as? dev.r1ptt.App ?: return
        if (app.workspace.blocked || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(android.app.NotificationChannel(CHANNEL, "Agents that need you", android.app.NotificationManager.IMPORTANCE_HIGH)
                .apply { lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET })
            val open = android.app.PendingIntent.getActivity(context, 0, android.content.Intent(context, dev.r1ptt.herdr.ui.HerdActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(n.sessionId.hashCode(), android.app.Notification.Builder(context, CHANNEL).setSmallIcon(dev.r1ptt.R.drawable.herdr_sheep)
                .setContentTitle(n.state.label).setContentText(n.text).setContentIntent(open)
                .setVisibility(android.app.Notification.VISIBILITY_SECRET).setAutoCancel(true).build())
        }
    }

    // ponytail: one Speaker per alert; a burst of alerts can overlap, queue them if that happens in practice.
    /** Says [text] in the R1's speech voice; null when no voice is set up. [done] runs on the play thread. */
    fun speak(text: String, done: () -> Unit = {}): dev.r1ptt.audio.Speaker? {
        val app = context.applicationContext as? dev.r1ptt.App ?: return null
        val cfg = app.store.value
        if (!cfg.tts.enabled || cfg.missingSpeechKey || cfg.quiet) return null
        return runCatching {
            dev.r1ptt.audio.Speaker(dev.r1ptt.net.TtsClient { app.store.value }::call, cfg.tts.sampleRate).apply {
                start(); say(text); finish { err -> if (err != null) EventLog.record("herdr speak failed: $err"); done() }
            }
        }.getOrNull()
    }

    companion object {
        private const val CHANNEL = "herdr_attention"
    }
}
