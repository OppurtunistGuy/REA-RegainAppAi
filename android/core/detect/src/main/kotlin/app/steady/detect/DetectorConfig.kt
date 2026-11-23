package app.steady.detect

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DetectorConfig(val version: Int, val detectors: List<Detector>)

@Serializable
data class Detector(
    val surfaceId: String,
    val packageName: String,
    val anyOf: List<Matcher>,
    val anchor: Matcher? = null,
)

/**
 * Every non-null field must match the same node. A matcher with no fields set matches nothing.
 * - [viewIdEquals] / [viewIdEndsWith]: compared against the full resource id ("pkg:id/name").
 * - [textEquals]: exact text.
 * - [descContains]: content description contains the value (case-insensitive).
 * - [classEquals]: exact class name.
 * - [selected]: the node's selected state must equal this value.
 */
@Serializable
data class Matcher(
    val viewIdEquals: String? = null,
    val viewIdEndsWith: String? = null,
    val textEquals: String? = null,
    val descContains: String? = null,
    val classEquals: String? = null,
    val selected: Boolean? = null,
) {
    internal val isEmpty: Boolean
        get() = viewIdEquals == null && viewIdEndsWith == null && textEquals == null &&
            descContains == null && classEquals == null && selected == null

    fun matches(node: ViewNode): Boolean {
        if (isEmpty) return false
        if (viewIdEquals != null && node.viewId != viewIdEquals) return false
        if (viewIdEndsWith != null && node.viewId?.endsWith(viewIdEndsWith) != true) return false
        if (textEquals != null && node.text != textEquals) return false
        if (descContains != null &&
            node.contentDescription?.contains(descContains, ignoreCase = true) != true
        ) return false
        if (classEquals != null && node.className != classEquals) return false
        if (selected != null && node.isSelected != selected) return false
        return true
    }
}

object DetectorCodec {
    private val format = Json { ignoreUnknownKeys = true }

    fun decode(json: String): DetectorConfig = format.decodeFromString(DetectorConfig.serializer(), json)
}
