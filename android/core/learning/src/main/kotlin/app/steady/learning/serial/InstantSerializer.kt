package app.steady.learning.serial

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

// TODO(phase12): switch to app.steady.rules.serial.InstantSerializer once core/rules (Phase 2) is merged,
// and restore `api(project(":core:rules"))` in build.gradle.kts. Wire format is identical (ISO-8601 string).

/** Serializes [java.time.Instant] as an ISO-8601 string, e.g. `"2026-11-23T09:00:00Z"`. */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("app.steady.learning.serial.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}
