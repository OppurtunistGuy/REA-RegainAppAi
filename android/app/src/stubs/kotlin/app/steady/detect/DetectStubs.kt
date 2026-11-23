// TODO(phase12): remove stubs source set once core modules merged.
// Minimal stand-in for core/detect (Phase 3): only the config types the app shell needs.
package app.steady.detect

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class DetectorConfig(val version: Int, val detectors: List<Detector>)

@Serializable data class Detector(
    val surfaceId: String,
    val packageName: String,
    val anyOf: List<Matcher>,
    val anchor: Matcher? = null,
)

@Serializable data class Matcher(
    val viewIdEquals: String? = null,
    val viewIdEndsWith: String? = null,
    val textEquals: String? = null,
    val descContains: String? = null,
    val classEquals: String? = null,
    val selected: Boolean? = null,
)

object DetectorCodec {
    private val json = Json { ignoreUnknownKeys = true }
    fun decode(json: String): DetectorConfig = this.json.decodeFromString(DetectorConfig.serializer(), json)
}
