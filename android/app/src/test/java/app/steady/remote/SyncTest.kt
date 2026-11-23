package app.steady.remote

import app.steady.data.EmptyPlan
import app.steady.rules.PlanCodec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class SyncMergeTest {
    @Test fun pushesWhenServerEmpty() =
        assertEquals(SyncMerge.Decision.PushLocal(plan(1)), SyncMerge.decide(plan(4), null))

    @Test fun nothingWhenBothEmpty() = assertEquals(SyncMerge.Decision.InSync, SyncMerge.decide(EmptyPlan, null))

    @Test fun serverWinsWhenLocalNotConfigured() =
        assertEquals(SyncMerge.Decision.UseServer(plan(2)), SyncMerge.decide(EmptyPlan, plan(2)))

    @Test fun higherLocalIsPushedAsServerPlusOne() =
        assertEquals(SyncMerge.Decision.PushLocal(plan(4, "Mine")), SyncMerge.decide(plan(7, "Mine"), plan(3)))

    @Test fun higherServerWins() =
        assertEquals(SyncMerge.Decision.UseServer(plan(5, "Theirs")), SyncMerge.decide(plan(4, "Mine"), plan(5, "Theirs")))

    @Test fun tieSameContentInSync() = assertEquals(SyncMerge.Decision.InSync, SyncMerge.decide(plan(3), plan(3)))

    @Test fun tieDifferentContentServerWins() =
        assertEquals(SyncMerge.Decision.UseServer(plan(3, "Theirs")), SyncMerge.decide(plan(3, "Mine"), plan(3, "Theirs")))
}

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSyncTest {
    private lateinit var server: MockWebServer
    private val account = InMemoryAccountStore(TEST_ACCOUNT)
    private lateinit var sync: RemoteSyncApi

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        sync = RemoteSyncApi(apiFor(server, account), account)
    }

    @After fun tearDown() = server.shutdown()

    @Test fun signedOutIsLocalOnly() = runTest {
        val out = RemoteSyncApi(apiFor(server, InMemoryAccountStore()), InMemoryAccountStore())
        assertNull(out.pullPlan())
        assertEquals(plan(2), out.pushPlan(plan(2)))
        assertEquals(0, server.requestCount)
    }

    @Test fun push200ReturnsStoredPlan() = runTest {
        server.enqueue(json(200, plan(1).json()))
        assertEquals(plan(1), sync.pushPlan(plan(1)))
    }

    @Test fun push409ReturnsServerPlan() = runTest {
        server.enqueue(error(409, "version_conflict", ""","current":${plan(6, "Theirs").json()}"""))
        assertEquals(plan(6, "Theirs"), sync.pushPlan(plan(2)))
    }

    @Test fun push409WithNoServerPlanReturnsVersionZero() = runTest {
        server.enqueue(error(409, "version_conflict", ""","current":null"""))
        assertEquals(0, sync.pushPlan(plan(2)).version)
    }

    @Test fun push401Throws() = runTest {
        server.enqueue(error(401, "unauthorized"))
        try {
            sync.pushPlan(plan(1)); throw AssertionError("expected Unauthorized")
        } catch (_: ApiException.Unauthorized) {
        }
    }

    @Test fun pull404IsNull() = runTest {
        server.enqueue(error(404, "not_found"))
        assertNull(sync.pullPlan())
    }

    @Test fun detectorsNullWhenOffline() = runTest {
        server.shutdown()
        assertNull(sync.detectors())
    }

    @Test fun detectorsDecoded() = runTest {
        server.enqueue(json(200, """{"version":2,"detectors":[]}"""))
        assertEquals(2, sync.detectors()?.version)
    }

    // ---- coordinator ----

    private fun coordinator(repo: MemPlanRepo, acc: AccountStore = account) =
        SyncCoordinator(repo, RemoteSyncApi(apiFor(server, acc), acc), acc, now = { Instant.EPOCH }, debounceMs = 2_000)

    @Test fun firstSyncPushesLocalAsVersionOne() = runTest {
        val repo = MemPlanRepo(plan(4))
        server.enqueue(error(404, "not_found"))
        server.enqueue(json(200, plan(1).json()))
        val status = coordinator(repo).syncOnce()
        assertEquals(SyncStatus.Synced(Instant.EPOCH, 1), status)
        server.takeRequest()
        assertEquals(plan(1), PlanCodec.decode(server.takeRequest().body.readUtf8()))
        assertEquals(plan(1), repo.current.value)
    }

    @Test fun serverNewerReplacesLocal() = runTest {
        val repo = MemPlanRepo(plan(2, "Mine"))
        server.enqueue(json(200, plan(5, "Theirs").json()))
        coordinator(repo).syncOnce()
        assertEquals(plan(5, "Theirs"), repo.current.value)
        assertEquals(1, server.requestCount)
    }

    @Test fun conflictMergeRepushesLocalWhenStillNewer() = runTest {
        // Pull says v3; between pull and push the extension wrote v4. Local is v9 → re-push as v5.
        val repo = MemPlanRepo(plan(9, "Mine"))
        server.enqueue(json(200, plan(3).json()))
        server.enqueue(error(409, "version_conflict", ""","current":${plan(4, "Theirs").json()}"""))
        server.enqueue(json(200, plan(5, "Mine").json()))
        val status = coordinator(repo).syncOnce()
        assertEquals(SyncStatus.Synced(Instant.EPOCH, 5), status)
        server.takeRequest()
        assertEquals(4, PlanCodec.decode(server.takeRequest().body.readUtf8()).version)
        assertEquals(plan(5, "Mine"), PlanCodec.decode(server.takeRequest().body.readUtf8()))
        assertEquals(plan(5, "Mine"), repo.current.value)
    }

    @Test fun conflictMergeTakesServerWhenItIsHigher() = runTest {
        val repo = MemPlanRepo(plan(4, "Mine"))
        server.enqueue(json(200, plan(3).json()))
        server.enqueue(error(409, "version_conflict", ""","current":${plan(8, "Theirs").json()}"""))
        coordinator(repo).syncOnce()
        assertEquals(plan(8, "Theirs"), repo.current.value)
        assertEquals(2, server.requestCount)
    }

    @Test fun unauthorizedSignsOut() = runTest {
        val acc = InMemoryAccountStore(TEST_ACCOUNT)
        server.enqueue(error(401, "unauthorized"))
        assertEquals(SyncStatus.SignInAgain, coordinator(MemPlanRepo(plan(1)), acc).syncOnce())
        assertNull(acc.account.value)
    }

    @Test fun offlineStatus() = runTest {
        server.shutdown()
        assertEquals(SyncStatus.Offline, coordinator(MemPlanRepo(plan(1))).syncOnce())
    }

    @Test fun signedOutDoesNothing() = runTest {
        assertEquals(SyncStatus.SignedOut, coordinator(MemPlanRepo(plan(1)), InMemoryAccountStore()).syncOnce())
        assertEquals(0, server.requestCount)
    }

    @Test fun debouncedTriggerOnStartAndOnPlanChange() = runTest {
        val repo = MemPlanRepo(plan(1))
        server.enqueue(json(200, plan(1).json())) // start: in sync
        server.enqueue(json(200, plan(1).json())) // after edit: pull v1
        server.enqueue(json(200, plan(2, "Edited").json())) // push v2
        val io = StandardTestDispatcher(testScheduler)
        val c = SyncCoordinator(repo, RemoteSyncApi(apiFor(server, account, io), account), account,
            now = { Instant.EPOCH }, debounceMs = 2_000)
        val job = c.start(backgroundScope)
        runCurrent()
        assertEquals(0, server.requestCount)
        advanceTimeBy(2_001); runCurrent()
        assertEquals(1, server.requestCount)
        repo.current.value = plan(2, "Edited")
        repo.current.value = plan(2, "Edited") // same value: no extra trigger
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, server.requestCount)
        advanceTimeBy(1_500); runCurrent()
        assertEquals(3, server.requestCount)
        assertTrue(c.status.value is SyncStatus.Synced)
        job.cancel()
    }
}
