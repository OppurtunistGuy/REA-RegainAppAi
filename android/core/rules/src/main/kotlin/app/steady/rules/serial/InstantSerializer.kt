package app.steady.rules.serial

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * [Instant] as an ISO-8601 string, e.g. "2026-11-24T10:45:00Z".
 * Encodes in UTC (`Instant.toString()`); decodes UTC or any offset ("+05:30").
 * Use with `@Serializable(with = InstantSerializer::class)` or `@file:UseSerializers(InstantSerializer::class)`.
 */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("app.steady.rules.serial.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant {
        val s = decoder.decodeString()
        return try {
            Instant.parse(s)
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(s).toInstant()
            } catch (e: DateTimeParseException) {
                throw SerializationException("Invalid ISO-8601 instant: '$s'", e)
            }
        }
    }
}
