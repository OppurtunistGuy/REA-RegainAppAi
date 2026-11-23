package app.steady.feature.settings

import app.steady.MainDispatcherRule
import app.steady.R
import app.steady.remote.ApiClient
import app.steady.remote.FakeBillingGateway
import app.steady.remote.InMemoryAccountStore
import app.steady.remote.MemPlanRepo
import app.steady.remote.MemSessionRepo
import app.steady.remote.MemUnlockRepo
import app.steady.remote.RemoteModule
import app.steady.remote.error
import app.steady.remote.json
import app.steady.remote.plan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private val account = InMemoryAccountStore()
    private val verifyBodies = mutableListOf<String>()
    private val calls = mutableListOf<String>()
    @Volatile private var deleteStatus = 204

    @Before fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path.also { calls += "${request.method} $it" }) {
                    "/v1/me" -> if (deleteStatus == 204) MockResponse().setResponseCode(204) else error(deleteStatus, "server_error")
                    "/v1/auth/logout" -> MockResponse().setResponseCode(204)
                    "/v1/auth/otp/start" -> MockResponse().setResponseCode(204)
                    "/v1/auth/otp/verify" -> {
                        val body = request.body.readUtf8()
                        verifyBodies += body
                        if (body.contains("\"000000\"")) json(200, """{"token":"${"k".repeat(40)}","userId":"u7"}""")
                        else error(401, "invalid_code")
                    }
                    "/v1/entitlements" -> json(200, """{"tier":"free","features":[]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        scope = CoroutineScope(SupervisorJob() + main.dispatcher)
    }

    @After fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun vm(): SettingsViewModel {
        val api = ApiClient(baseUrl = { server.url("/").toString() }, token = { account.account.value?.token }, io = main.dispatcher)
        val module = RemoteModule(account, api, FakeBillingGateway(), MemPlanRepo(plan(1)), MemSessionRepo(), MemUnlockRepo(), scope)
        return SettingsViewModel(module)
    }

    @Test fun otpSignInFlowWithName() = runTest {
        val vm = vm()
        vm.setContact(" Riya@Example.com ")
        vm.setName("Riya")
        vm.sendCode()
        assertEquals(OtpStep.CODE, vm.state.value.step)
        vm.setCode("12a3456789")
        assertEquals("123456", vm.state.value.code)
        vm.verify()
        assertEquals(R.string.acct_err_code, vm.state.value.error)
        assertNull(account.account.value)

        vm.setCode("000000")
        vm.verify()
        val acc = account.account.value!!
        assertEquals("u7", acc.userId)
        assertEquals("Riya", acc.name)
        assertEquals("Riya", ApiClient.json.parseToJsonElement(verifyBodies.last()).jsonObject["name"].toString().trim('"'))
        assertEquals(acc, vm.state.value.account)
    }

    @Test fun deleteSyncedDataCallsDeleteMeThenSignsOut() = runTest {
        account.signIn(app.steady.remote.TEST_ACCOUNT)
        val vm = vm()
        vm.askDelete(true)
        assertEquals(true, vm.state.value.confirmDelete)
        vm.deleteSyncedData()
        assertEquals(true, "DELETE /v1/me" in calls)
        assertNull(account.account.value)
        assertEquals(R.string.settings_delete_done, vm.state.value.notice)
        assertEquals(false, vm.state.value.confirmDelete)
    }

    @Test fun deleteSyncedDataFailureKeepsAccount() = runTest {
        deleteStatus = 500
        account.signIn(app.steady.remote.TEST_ACCOUNT)
        val vm = vm()
        vm.askDelete(true)
        vm.deleteSyncedData()
        assertEquals(app.steady.remote.TEST_ACCOUNT, account.account.value)
        assertEquals(R.string.acct_err_server, vm.state.value.error)
        assertEquals(false, vm.state.value.confirmDelete)
    }

    @Test fun signOutRevokesTokenOnServer() = runTest {
        account.signIn(app.steady.remote.TEST_ACCOUNT)
        val vm = vm()
        vm.signOut()
        assertNull(account.account.value)
        testScheduler.advanceUntilIdle()
        assertEquals(true, "POST /v1/auth/logout" in calls)
    }

    @Test fun baseUrlValidated() = runTest {
        val vm = vm()
        vm.setBaseUrl("nope")
        vm.saveBaseUrl()
        assertEquals(R.string.settings_server_invalid, vm.state.value.error)
        vm.setBaseUrl("https://api.example.com/")
        vm.saveBaseUrl()
        assertEquals("https://api.example.com", account.baseUrl.value)
    }
}
