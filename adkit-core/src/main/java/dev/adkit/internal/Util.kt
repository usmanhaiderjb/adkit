package dev.adkit.internal

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.inputmethodservice.InputMethodService
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

internal object AdLog {
    @Volatile var enabled = false
    private const val TAG = "AdKit"

    fun d(msg: String) { if (enabled) Log.d(TAG, msg) }
    fun w(msg: String) { if (enabled) Log.w(TAG, msg) }
    fun e(msg: String, t: Throwable? = null) { Log.e(TAG, msg, t) }
}

internal object Main {
    val handler = Handler(Looper.getMainLooper())

    val isMain: Boolean get() = Looper.myLooper() == Looper.getMainLooper()

    /** Next-Gen SDK callbacks arrive on background threads; everything public goes through here. */
    fun run(block: () -> Unit) {
        if (isMain) block() else handler.post(block)
    }

    fun post(block: () -> Unit) { handler.post(block) }

    fun delay(ms: Long, block: Runnable) { handler.postDelayed(block, ms) }

    fun cancel(block: Runnable) { handler.removeCallbacks(block) }
}

internal fun now(): Long = SystemClock.elapsedRealtime()
internal fun wallNow(): Long = System.currentTimeMillis()

/** Wraps a callback so it fires at most once, always on main. */
internal class Once<T>(private val target: (T) -> Unit) {
    private val fired = AtomicBoolean(false)
    val isDone: Boolean get() = fired.get()

    operator fun invoke(value: T) {
        if (fired.compareAndSet(false, true)) Main.run { target(value) }
    }
}

internal val Activity.isAlive: Boolean
    get() = !isFinishing && !isDestroyed

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Keyboard apps: ads must never render inside the IME window (Play policy). */
internal tailrec fun Context.isImeContext(): Boolean = when (this) {
    is InputMethodService -> true
    is ContextWrapper -> baseContext.isImeContext()
    else -> false
}

internal object Network {
    private var cm: ConnectivityManager? = null
    private val listeners = mutableSetOf<() -> Unit>()

    fun init(context: Context) {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        cm = manager
        runCatching {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val copy = synchronized(listeners) { listeners.toList() }
                    Main.post { copy.forEach { it() } }
                }
            })
        }
    }

    val isOnline: Boolean
        get() {
            val manager = cm ?: return true
            val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

    fun onAvailable(listener: () -> Unit) { synchronized(listeners) { listeners += listener } }
    fun remove(listener: () -> Unit) { synchronized(listeners) { listeners -= listener } }
}

internal object Prefs {
    private const val FILE = "adkit"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun remoteJson(c: Context): String? = sp(c).getString("remote_json", null)
    fun setRemoteJson(c: Context, json: String) = sp(c).edit().putString("remote_json", json).apply()

    fun launchCount(c: Context): Int = sp(c).getInt("launch_count", 0)
    fun bumpLaunchCount(c: Context): Int {
        val n = launchCount(c) + 1
        sp(c).edit().putInt("launch_count", n).apply()
        return n
    }

    fun counter(c: Context, placement: String, default: Int): Int = sp(c).getInt("cnt_$placement", default)
    fun setCounter(c: Context, placement: String, value: Int) = sp(c).edit().putInt("cnt_$placement", value).apply()

    fun lastShown(c: Context, placement: String): Long = sp(c).getLong("last_$placement", 0L)
    fun setLastShown(c: Context, placement: String, wallMs: Long) =
        sp(c).edit().putLong("last_$placement", wallMs).apply()
}
