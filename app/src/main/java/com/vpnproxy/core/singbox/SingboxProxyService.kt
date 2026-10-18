package com.vpnproxy.core.singbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Isolated sing-box proxy process. It only exposes a loopback mixed SOCKS/HTTP inbound;
 * it never opens a TUN. The Messenger protocol is deliberately small and process-safe.
 */
class SingboxProxyService : Service() {
    companion object {
        const val ACTION_START = "com.vpnproxy.SINGBOX_START"
        const val ACTION_STOP = "com.vpnproxy.SINGBOX_STOP"
        const val EXTRA_CONFIG = "singbox_config"
        const val MSG_START = 1
        const val MSG_STOP = 2
        const val MSG_RESULT = 3
        private const val TAG = "SingboxProxyService"
        private const val CHANNEL_ID = "singbox_proxy"
        private const val NOTIFICATION_ID = 10809
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val starting = AtomicBoolean(false)
    @Volatile private var coreService: Any? = null
    @Volatile private var lastError: String? = null
    @Volatile private var libboxSetupDone = false

    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            MSG_START -> {
                val config = message.data?.getString(EXTRA_CONFIG).orEmpty()
                val replyTo = message.replyTo
                scope.launch {
                    val ok = startCore(config)
                    val reply = Message.obtain(null, MSG_RESULT).apply {
                        arg1 = if (ok) 1 else 0
                        data = Bundle().apply { putString("error", lastError) }
                    }
                    try { replyTo?.send(reply) } catch (t: Throwable) {
                        Log.w(TAG, "Unable to return startup result to client", t)
                    }
                }
                true
            }
            MSG_STOP -> {
                scope.launch { stopCore() }
                true
            }
            else -> false
        }
    })

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> scope.launch {
                stopCore()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
            else -> startForeground(NOTIFICATION_ID, buildNotification())
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private suspend fun startCore(config: String): Boolean = withContext(Dispatchers.IO) {
        if (config.isBlank()) {
            lastError = "Sing-box config is empty"
            return@withContext false
        }
        if (coreService != null) return@withContext true
        if (!starting.compareAndSet(false, true)) {
            lastError = "Sing-box is already starting"
            return@withContext false
        }
        try {
            val libbox = Class.forName("io.nekohasekai.libbox.Libbox")
            val platformType = Class.forName("io.nekohasekai.libbox.PlatformInterface")
            val platform = Proxy.newProxyInstance(
                platformType.classLoader,
                arrayOf(platformType)
            ) { proxy, method, args ->
                when (method.name) {
                    "toString" -> "SingboxProxyPlatformInterface"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "openTun" -> -1
                    "usePlatformAutoDetectInterfaceControl",
                    "usePlatformNotification",
                    "usePlatformNeighborResolver",
                    "usePlatformWIFIMonitor",
                    "usePlatformConnectionOwnerFinder",
                    "usePlatformDefaultInterfaceMonitor",
                    "usePlatformNetworkInterfaces",
                    "underNetworkExtension",
                    "includeAllNetworks",
                    "useProcFS",
                    "usePlatformShell" -> false
                    else -> defaultValue(method.returnType)
                }
            }

            val base = filesDir.absolutePath
            val working = getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
            val temp = cacheDir.absolutePath
            if (!libboxSetupDone) {
                val setup = libbox.methods.firstOrNull { it.name == "setup" }
                    ?: error("libbox setup API not found")
                val setupArgs = setup.parameterTypes.mapIndexed { index, type ->
                    when {
                        type == String::class.java -> when (index) {
                            0 -> base
                            1 -> working
                            2 -> temp
                            else -> "warn"
                        }
                        type == Int::class.javaPrimitiveType || type == Int::class.javaObjectType ->
                            if (index == setup.parameterTypes.lastIndex) 1000 else 0
                        type == Long::class.javaPrimitiveType || type == Long::class.javaObjectType -> 1000L
                        type == Boolean::class.javaPrimitiveType || type == Boolean::class.javaObjectType -> false
                        else -> null
                    }
                }.toTypedArray()
                setup.invoke(null, *setupArgs)
                libboxSetupDone = true
            }

            val newService = libbox.methods.firstOrNull { method ->
                method.name == "newService" && method.parameterTypes.size == 2 &&
                    method.parameterTypes.any { it == String::class.java } &&
                    method.parameterTypes.any { it.isAssignableFrom(platformType) || platformType.isAssignableFrom(it) }
            } ?: error("libbox newService(config, PlatformInterface) API not found")
            val args = newService.parameterTypes.map { type ->
                when {
                    type == String::class.java -> config
                    type.isAssignableFrom(platformType) || platformType.isAssignableFrom(type) -> platform
                    else -> null
                }
            }.toTypedArray()
            val created = invokeUnwrapped(newService, null, *args)
                ?: error("libbox returned no service instance")
            invokeUnwrapped(created.javaClass.methods.firstOrNull {
                it.name == "start" && it.parameterTypes.isEmpty()
            } ?: error("libbox service start() API not found"), created)
            coreService = created
            lastError = null
            Log.i(TAG, "Sing-box proxy started on 127.0.0.1:${SingboxConfigBuilder.PROXY_PORT}")
            true
        } catch (t: Throwable) {
            lastError = (t.cause ?: t).message ?: "Unknown libbox startup error"
            Log.e(TAG, "Unable to start sing-box proxy", t)
            stopCore()
            false
        } finally {
            starting.set(false)
        }
    }

    private fun invokeUnwrapped(method: java.lang.reflect.Method, target: Any?, vararg args: Any?): Any? {
        return try {
            method.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw (e.targetException ?: e)
        }
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

    private suspend fun stopCore() = withContext(Dispatchers.IO) {
        val current = coreService
        coreService = null
        if (current != null) {
            try {
                current.javaClass.methods.firstOrNull {
                    it.name == "close" && it.parameterTypes.isEmpty()
                }?.invoke(current)
                Log.i(TAG, "Sing-box proxy stopped")
            } catch (t: Throwable) {
                Log.w(TAG, "Error stopping sing-box", t)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Sing-box proxy", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification =
        android.app.Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VPNProxy")
            .setContentText("Sing-box proxy is running")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .build()

    override fun onDestroy() {
        val current = coreService
        coreService = null
        try {
            current?.javaClass?.methods?.firstOrNull {
                it.name == "close" && it.parameterTypes.isEmpty()
            }?.invoke(current)
        } catch (t: Throwable) {
            Log.w(TAG, "Error closing sing-box during service destruction", t)
        }
        scope.cancel()
        super.onDestroy()
    }
}

/** Messenger client used by the main VPN process. */
object SingboxProxyClient {
    suspend fun start(context: Context, config: String, timeoutMs: Long = 25_000): Boolean =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, SingboxProxyService::class.java)
                .setAction(SingboxProxyService.ACTION_START)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) appContext.startForegroundService(intent)
                else appContext.startService(intent)
            } catch (t: Throwable) {
                Log.e("SingboxProxyClient", "Could not start isolated service", t)
                return@withContext false
            }

            val latch = java.util.concurrent.CountDownLatch(1)
            val result = java.util.concurrent.atomic.AtomicBoolean(false)
            val replyHandler = Handler(Looper.getMainLooper()) { message ->
                if (message.what == SingboxProxyService.MSG_RESULT) {
                    result.set(message.arg1 == 1)
                    latch.countDown()
                    true
                } else false
            }
            val replyMessenger = Messenger(replyHandler)
            var remote: Messenger? = null
            val connected = java.util.concurrent.CountDownLatch(1)
            val connection = object : android.content.ServiceConnection {
                override fun onServiceConnected(name: android.content.ComponentName?, binder: IBinder?) {
                    remote = Messenger(binder)
                    connected.countDown()
                }
                override fun onServiceDisconnected(name: android.content.ComponentName?) { remote = null }
            }
            val bound = try {
                appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (t: Throwable) {
                Log.e("SingboxProxyClient", "Could not bind isolated service", t)
                false
            }
            if (!bound) return@withContext false
            try {
                if (!connected.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) return@withContext false
                val message = Message.obtain(null, SingboxProxyService.MSG_START).apply {
                    data = Bundle().apply { putString(SingboxProxyService.EXTRA_CONFIG, config) }
                    replyTo = replyMessenger
                }
                remote?.send(message)
                latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) && result.get()
            } catch (t: Throwable) {
                Log.e("SingboxProxyClient", "Sing-box startup IPC failed", t)
                false
            } finally {
                try { appContext.unbindService(connection) } catch (_: Throwable) { }
            }
        }

    fun stop(context: Context) {
        try {
            // stopService does not create a new :singbox process for VLESS-only sessions.
            context.applicationContext.stopService(
                Intent(context.applicationContext, SingboxProxyService::class.java)
            )
        } catch (t: Throwable) {
            Log.w("SingboxProxyClient", "Unable to request sing-box shutdown", t)
        }
    }
}
