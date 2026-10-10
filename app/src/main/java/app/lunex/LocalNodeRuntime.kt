package app.lunex

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class LocalNodeUiState(
    val peers: List<LanPeer> = emptyList(),
    val status: String = "Запуск узла Lunex…",
    val lastMessage: LocalNodeMessage? = null
)

/**
 * Process-wide node runtime shared by the foreground service and the Compose UI.
 * The SQLite database is durable; this singleton only owns the live network socket.
 */
internal object LocalNodeRuntime {
    private val mutableState = MutableStateFlow(LocalNodeUiState())
    val state: StateFlow<LocalNodeUiState> = mutableState.asStateFlow()

    @Volatile private var storeInstance: LocalNodeStore? = null
    @Volatile private var transportInstance: LocalNodeTransport? = null

    @Synchronized
    fun store(context: Context): LocalNodeStore {
        storeInstance?.let { return it }
        return LocalNodeStore(context.applicationContext).also { storeInstance = it }
    }

    @Synchronized
    fun ensure(context: Context): LocalNodeTransport {
        transportInstance?.let {
            it.start()
            return it
        }
        val store = store(context)
        val transport = LocalNodeTransport(
            context.applicationContext,
            store,
            onPeersChanged = { peers -> mutableState.update { it.copy(peers = peers) } },
            onMessage = { message -> mutableState.update { it.copy(lastMessage = message) } },
            onStatus = { status -> mutableState.update { it.copy(status = status) } }
        )
        transportInstance = transport
        transport.start()
        return transport
    }

    @Synchronized
    fun pause() {
        transportInstance?.stop()
        mutableState.update { it.copy(peers = emptyList(), status = "Узел приостановлен: приложение неактивно") }
    }

    @Synchronized
    fun stop() {
        transportInstance?.stop()
        transportInstance = null
        mutableState.value = LocalNodeUiState(status = "Узел остановлен")
    }
}
