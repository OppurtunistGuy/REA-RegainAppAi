package app.steady.feature.learning

import app.steady.data.CardRepository
import app.steady.data.SessionRepository
import app.steady.learning.Card
import app.steady.learning.FocusSession
import app.steady.learning.Scheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Shared fixtures for Phase 8 tests. Tue 24 Nov 2026, Asia/Kolkata (UTC+5:30). */
object Fx {
    val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    val tuesday: LocalDate = LocalDate.of(2026, 11, 24)
    fun at(date: LocalDate, h: Int, m: Int = 0): Instant = date.atTime(h, m).atZone(zone).toInstant()
    fun tue(h: Int, m: Int = 0): Instant = at(tuesday, h, m)
    val now: Instant = tue(16, 7)

    fun card(id: String, due: Instant, createdAt: Instant = now, deck: String = Decks.PHYSICS, front: String = "Q$id", back: String = "A$id") =
        Card(id, deck, front, back, createdAt, Scheduler.newState(due))

    fun session(id: String, start: Instant, minutes: Long, intercepts: Int = 0, unlocks: Int = 0, scheduleId: String? = null) =
        FocusSession(id, scheduleId, start, start.plusSeconds(minutes * 60), intercepts, unlocks)

    fun ids(): () -> String { var n = 0; return { "id${++n}" } }
}

/** A clock whose time tests can move. */
class MutableClock(var instant: Instant, private val zone: ZoneId = Fx.zone) : Clock() {
    override fun instant(): Instant = instant
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId?): Clock = MutableClock(instant, zone ?: ZoneOffset.UTC)
}

class FakeCardRepository(initial: List<Card> = emptyList()) : CardRepository {
    val state = MutableStateFlow(initial)
    val upserts = mutableListOf<List<Card>>()
    override val cards: Flow<List<Card>> = state
    override suspend fun upsert(cards: List<Card>) {
        upserts += cards
        state.update { old ->
            val byId = cards.associateBy { it.id }
            old.map { byId[it.id] ?: it } + cards.filter { c -> old.none { it.id == c.id } }
        }
    }
    val deletes = mutableListOf<Collection<String>>()
    override suspend fun delete(ids: Collection<String>) {
        deletes += ids
        state.update { old -> old.filterNot { it.id in ids } }
    }
}

class FakeSessionRepository(
    sessions: List<FocusSession> = emptyList(),
    intercepts: List<Pair<String, Instant>> = emptyList(),
    private val zone: ZoneId = Fx.zone,
) : SessionRepository {
    val sessionState = MutableStateFlow(sessions)
    val interceptState = MutableStateFlow(intercepts)
    override val sessions: Flow<List<FocusSession>> = sessionState
    override suspend fun add(s: FocusSession) = sessionState.update { it + s }
    override suspend fun recordIntercept(surfaceId: String, at: Instant) = interceptState.update { it + (surfaceId to at) }
    override fun interceptsOn(date: LocalDate): Flow<Int> =
        interceptState.map { l -> l.count { it.second.atZone(zone).toLocalDate() == date } }
    override fun interceptsBySurface(from: LocalDate, toExclusive: LocalDate): Flow<Map<String, Int>> =
        interceptState.map { l ->
            l.filter { val d = it.second.atZone(zone).toLocalDate(); !d.isBefore(from) && d.isBefore(toExclusive) }
                .groupingBy { it.first }.eachCount()
        }
}
