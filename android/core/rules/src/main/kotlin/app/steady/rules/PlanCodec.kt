package app.steady.rules

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * JSON codec for [Plan]. Strict: unknown keys are rejected (so nothing outside the schema is ever
 * accepted), defaults are always written. Decoding also checks schedule times and days.
 */
object PlanCodec {
    val json: Json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    private val hhmm = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")

    /** @throws SerializationException on malformed JSON, unknown keys or invalid schedule fields. */
    fun decode(json: String): Plan {
        val plan = this.json.decodeFromString(Plan.serializer(), json)
        validate(plan)
        return plan
    }

    fun encode(plan: Plan): String = json.encodeToString(Plan.serializer(), plan)

    private fun validate(plan: Plan) {
        for (s in plan.schedules) {
            if (s.id.isEmpty()) throw SerializationException("Schedule id must not be empty")
            if (!hhmm.matches(s.start)) throw SerializationException("Schedule ${s.id}: bad start '${s.start}'")
            if (!hhmm.matches(s.end)) throw SerializationException("Schedule ${s.id}: bad end '${s.end}'")
            if (s.days.isEmpty() || s.days.any { it !in 1..7 }) {
                throw SerializationException("Schedule ${s.id}: days must be 1..7 and non-empty")
            }
        }
    }
}
