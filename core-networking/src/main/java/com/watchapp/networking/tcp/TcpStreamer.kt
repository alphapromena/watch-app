package com.watchapp.networking.tcp

import android.content.Context
import android.util.Log
import com.watchapp.contracts.DeviceConfig
import com.watchapp.contracts.SensorEvent
import com.watchapp.contracts.Streamer
import com.watchapp.contracts.StreamerState
import com.watchapp.networking.buffer.FrameBuffer
import com.watchapp.networking.buffer.PendingFrameDatabase
import com.watchapp.networking.codec.FrameCodec
import com.watchapp.networking.codec.ProtocolBuilder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * FCAF v2 [Streamer] over a single persistent TCP connection.
 *
 * Threading: a dedicated single-thread executor owns *all* mutable state
 * (socket, output, lifecycle job, state flow updates) and serializes outbound
 * writes. Inbound reads run on [readDispatcher] (the multi-threaded
 * [Dispatchers.IO] by default) so a long blocking `read()` does not starve the
 * outbound side. State updates from the reader fan back in via channels.
 *
 * Lifecycle: [connect] is idempotent. It launches a single lifecycle coroutine
 * that loops connect → upLogin → drain offline buffer → read inbound → on
 * failure, transition to RECONNECTING, sleep [ReconnectPolicy.nextDelayMs],
 * repeat. [disconnect] cancels the loop and tears down the socket.
 *
 * Outbound ordering: [enqueue] and [sendHeartbeat] post to an unbounded
 * [Channel] consumed by a permanent processor coroutine. The processor writes
 * directly to the socket when CONNECTED; otherwise the framed bytes go to the
 * Room-backed [FrameBuffer] for later FIFO drain. Frames buffered while offline
 * are flushed on the next successful connect.
 *
 * Wake on network: [onNetworkAvailable] short-circuits the backoff sleep so
 * we attempt to reconnect immediately when app-shell's `ConnectivityManager`
 * reports the network is back.
 */
class TcpStreamer @JvmOverloads constructor(
    private val deviceConfig: () -> DeviceConfig,
    private val frameBuffer: FrameBuffer,
    private val protocolBuilder: ProtocolBuilder = ProtocolBuilder(deviceConfig),
    private val socketFactory: SocketFactory = SocketFactory.Default,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    ioExecutor: ExecutorService = defaultIoExecutor(),
    private val readDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : Streamer {

    private val ioDispatcher: CoroutineDispatcher = ioExecutor.asCoroutineDispatcher()
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val ops: Channel<Op> = Channel(Channel.UNLIMITED)
    private val wakeup: Channel<Unit> = Channel(Channel.CONFLATED)
    private val _state: MutableStateFlow<StreamerState> = MutableStateFlow(StreamerState.DISCONNECTED)

    /** Current connection state. Backed by a [MutableStateFlow] for app-shell to observe. */
    override val state: StateFlow<StreamerState> = _state.asStateFlow()

    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var lifecycleJob: Job? = null

    init {
        scope.launch { processOps() }
    }

    /**
     * Idempotent. Starts the connect/reconnect loop. Returns once the loop has
     * been scheduled — does not wait for the first CONNECTED state.
     */
    override suspend fun connect() {
        withContext(ioDispatcher) {
            if (lifecycleJob?.isActive == true) return@withContext
            lifecycleJob = scope.launch { runLifecycle() }
        }
    }

    /**
     * Non-blocking. Posts [event] to the outbound queue; the processor either
     * writes it to the socket (if CONNECTED) or persists it to [FrameBuffer].
     */
    override fun enqueue(event: SensorEvent) {
        ops.trySend(Op.SendEvent(event))
    }

    /** Non-blocking. Posts an `upHeartbeat` frame to the outbound queue. */
    override fun sendHeartbeat() {
        ops.trySend(Op.SendHeartbeat)
    }

    /** Cancels the lifecycle loop and tears down the socket. The processor coroutine
     *  keeps running so any further [enqueue] calls land in [FrameBuffer]. */
    override suspend fun disconnect() {
        withContext(ioDispatcher) {
            lifecycleJob?.cancelAndJoin()
            lifecycleJob = null
            closeSocketSilently()
            _state.value = StreamerState.DISCONNECTED
        }
    }

    /**
     * Bridge from app-shell's `ConnectivityManager.NetworkCallback#onAvailable`.
     * Resets [ReconnectPolicy] and wakes the lifecycle loop early so it can
     * attempt to reconnect immediately rather than waiting out the slow-mode
     * delay.
     */
    fun onNetworkAvailable() {
        reconnectPolicy.onNetworkAvailable()
        wakeup.trySend(Unit)
    }

    /**
     * Bridge from app-shell's `ConnectivityManager.NetworkCallback#onLost`.
     * Forces [ReconnectPolicy] into slow mode immediately.
     */
    fun onNetworkLost() {
        reconnectPolicy.onNetworkLost()
    }

    // region private — outbound

    /** Permanent consumer — drains [ops] for the lifetime of the streamer. */
    private suspend fun processOps() {
        for (op in ops) {
            try {
                val json = when (op) {
                    is Op.SendEvent -> protocolBuilder.fromEvent(op.event)
                    Op.SendHeartbeat -> protocolBuilder.upHeartbeat()
                }
                sendOrBuffer(FrameCodec.encode(json))
            } catch (t: Throwable) {
                Log.w(TAG, "processOps: unexpected error: ${t.message}")
            }
        }
    }

    private suspend fun sendOrBuffer(frame: ByteArray) {
        val out = output
        if (_state.value == StreamerState.CONNECTED && out != null) {
            try {
                out.write(frame)
                out.flush()
                return
            } catch (e: IOException) {
                Log.w(TAG, "write failed; buffering and forcing reconnect: ${e.message}")
                closeSocketSilently()
            }
        }
        frameBuffer.enqueue(frame)
    }

    // endregion

    // region private — lifecycle

    private suspend fun runLifecycle() {
        while (currentCoroutineContext().isActive) {
            _state.value = StreamerState.CONNECTING
            try {
                openSocketAndLogin()
                _state.value = StreamerState.CONNECTED
                reconnectPolicy.onConnected()
                drainOfflineBuffer()
                readUntilFailure()
                _state.value = StreamerState.RECONNECTING
            } catch (e: IOException) {
                Log.w(TAG, "connect failed: ${e.message}")
                _state.value = StreamerState.RECONNECTING
            } finally {
                closeSocketSilently()
            }
            sleepInterruptible(reconnectPolicy.nextDelayMs(clock()))
        }
    }

    private fun openSocketAndLogin() {
        val cfg = deviceConfig()
        val s = socketFactory.create(cfg.serverHost, cfg.serverPort)
        val out = s.getOutputStream()
        socket = s
        output = out
        val login = FrameCodec.encode(protocolBuilder.upLogin())
        out.write(login)
        out.flush()
    }

    private suspend fun drainOfflineBuffer() {
        val out = output ?: return
        while (currentCoroutineContext().isActive) {
            val batch = frameBuffer.peekOldest()
            if (batch.isEmpty()) return
            for (frame in batch) {
                try {
                    out.write(frame.bytes)
                    out.flush()
                } catch (e: IOException) {
                    Log.w(TAG, "drain failed mid-batch: ${e.message}")
                    throw e
                }
                frameBuffer.delete(frame)
            }
        }
    }

    /**
     * Blocks (on [readDispatcher]) reading inbound frames until EOF or IOException.
     * Returns normally either way; the caller transitions to RECONNECTING.
     */
    private suspend fun readUntilFailure() {
        val s = socket ?: return
        withContext(readDispatcher) {
            try {
                val input = s.getInputStream()
                while (currentCoroutineContext().isActive) {
                    val payload = FrameCodec.decode(input)
                    Log.d(TAG, "downlink: $payload")
                }
            } catch (e: IOException) {
                Log.d(TAG, "inbound stream ended: ${e.message}")
            }
        }
    }

    private suspend fun sleepInterruptible(ms: Long) {
        if (ms <= 0L) return
        // Drain any stale wake-up signal so the next call only honours new ones.
        while (wakeup.tryReceive().isSuccess) Unit
        withTimeoutOrNull(ms) { wakeup.receive() }
    }

    private fun closeSocketSilently() {
        try {
            socket?.close()
        } catch (_: IOException) {
            // already gone, fine
        }
        socket = null
        output = null
    }

    // endregion

    /** Outbound work queued from [enqueue] / [sendHeartbeat]. */
    private sealed interface Op {
        data class SendEvent(val event: SensorEvent) : Op
        data object SendHeartbeat : Op
    }

    /**
     * SPI for socket creation. Production uses [Default]; tests pass a fake
     * factory that returns sockets backed by in-memory streams.
     */
    fun interface SocketFactory {
        @Throws(IOException::class)
        fun create(host: String, port: Int): Socket

        companion object {
            /**
             * Plain `java.net.Socket` with TCP_NODELAY (low latency for small
             * frames) and a 10-second connect timeout (caps the duration of
             * any single connect attempt — the lifecycle loop already retries).
             */
            val Default: SocketFactory = SocketFactory { host, port ->
                Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                }
            }
        }
    }

    companion object {
        private const val TAG: String = "TcpStreamer"
        private const val CONNECT_TIMEOUT_MS: Int = 10_000

        private fun defaultIoExecutor(): ExecutorService =
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "watch-app-streamer-io").apply { isDaemon = true }
            }
    }
}

/**
 * Convenience factory for app-shell. Builds the Room database from
 * `context.applicationContext`, wraps its DAO in [FrameBuffer], and returns
 * a fully-wired [TcpStreamer]. Callers do not need `androidx.room.RoomDatabase`
 * on their classpath — Room stays an internal implementation detail of
 * `:core-networking`.
 *
 * Equivalent to:
 * ```
 * val db = PendingFrameDatabase.create(context.applicationContext)
 * TcpStreamer(deviceConfigProvider, FrameBuffer(db.pendingFrameDao()))
 * ```
 *
 * Direct construction via the primary constructor is still supported for
 * tests that want to inject a mocked [FrameBuffer].
 */
fun TcpStreamer.Companion.create(
    context: Context,
    deviceConfigProvider: () -> DeviceConfig,
): TcpStreamer {
    val database = PendingFrameDatabase.create(context.applicationContext)
    val buffer = FrameBuffer(dao = database.pendingFrameDao())
    return TcpStreamer(
        deviceConfig = deviceConfigProvider,
        frameBuffer = buffer,
    )
}
