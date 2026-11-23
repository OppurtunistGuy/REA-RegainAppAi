package app.steady.data

import android.util.Log
import app.steady.detect.DetectorCodec
import app.steady.detect.DetectorConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** Where the newest detector config is kept between app runs. */
interface DetectorConfigPersistence {
    fun read(): String?
    fun write(json: String)
}

/** A file in the app's private files dir. Holds no personal data. */
class FileDetectorConfigPersistence(private val file: File) : DetectorConfigPersistence {
    override fun read(): String? = try {
        if (file.exists()) file.readText() else null
    } catch (e: IOException) {
        null
    }

    override fun write(json: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) {
            file.writeText(json)
            tmp.delete()
        }
    }
}

/**
 * The detector config the accessibility service uses. Starts from the newer of the shipped asset
 * ([seed]) and the last downloaded copy, and is refreshed from `GET /v1/detectors` on each sync.
 * Only a config with a strictly higher version replaces the current one; it is persisted so the
 * update survives restarts and works offline. Detector configs hold view ids and labels only.
 */
class DetectorConfigStore(
    seed: () -> DetectorConfig,
    private val persistence: DetectorConfigPersistence,
    private val log: (String, Throwable?) -> Unit = { msg, e -> Log.w(TAG, msg, e) },
) {
    private val _current: MutableStateFlow<DetectorConfig>
    val current: StateFlow<DetectorConfig>
    private val mutex = Mutex()

    init {
        val shipped = seed()
        val saved = persistence.read()?.let { text ->
            runCatching { DetectorCodec.decode(text) }.onFailure { log("Ignoring unreadable saved detector config", it) }.getOrNull()
        }
        _current = MutableStateFlow(if (saved != null && saved.version > shipped.version) saved else shipped)
        current = _current.asStateFlow()
    }

    /** Applies [candidate] if it is newer. Returns true when it replaced the current config. */
    suspend fun offer(candidate: DetectorConfig?): Boolean = mutex.withLock {
        if (candidate == null || candidate.version <= _current.value.version || candidate.detectors.isEmpty()) {
            return@withLock false
        }
        try {
            persistence.write(json.encodeToString(DetectorConfig.serializer(), candidate))
        } catch (e: IOException) {
            log("Could not persist detector config", e) // still use it for this run
        }
        _current.value = candidate
        true
    }

    /** Fetches from [api] and applies the result when newer. Network failures leave the config as is. */
    suspend fun refresh(api: SyncApi): Boolean {
        val fetched = try {
            api.detectors()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return offer(fetched)
    }

    private companion object {
        const val TAG = "SteadyDetectors"
        val json = Json { encodeDefaults = false }
    }
}
