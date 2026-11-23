package app.steady.remote

import app.steady.data.SessionRepository
import app.steady.learning.FocusSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sends finished focus sessions to `POST /v1/sessions` while signed in, so the weekly summary
 * works. Only aggregate fields go out (id, schedule id, start, end, intercept and unlock counts);
 * see [SessionBody]. Ids already sent are remembered in [AccountStore]; the backend ignores repeats.
 */
class SessionUploader(
    private val sessions: SessionRepository,
    private val api: ApiClient,
    private val account: AccountStore,
) {
    private val mutex = Mutex()

    fun start(scope: CoroutineScope): Job = scope.launch {
        combine(account.account, sessions.sessions) { acc, list -> acc to list }
            .collect { (acc, list) -> if (acc != null) upload(list) }
    }

    /**
     * Uploads sessions not sent before and returns how many the server accepted. Stops at the first
     * network/server/auth failure (retried on the next change).
     */
    suspend fun upload(list: List<FocusSession>): Int = mutex.withLock {
        if (!account.isSignedIn) return@withLock 0
        val done = account.uploadedSessionIds()
        var sent = 0
        for (s in list.sortedBy { it.start }) {
            if (s.id in done || s.end.isBefore(s.start)) continue
            try {
                api.postSession(s)
                sent++
            } catch (e: ApiException.Network) {
                break
            } catch (e: ApiException.Unauthorized) {
                break
            } catch (e: ApiException.Server) {
                break
            } catch (e: ApiException) {
                // 400 etc.: this session can never be accepted; don't retry it forever.
            }
            account.markSessionsUploaded(listOf(s.id))
        }
        sent
    }
}
