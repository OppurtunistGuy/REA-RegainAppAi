package app.steady.feature.setup

import app.steady.MainDispatcherRule
import app.steady.data.FakeEntitlements
import app.steady.data.FakePlanRepository
import app.steady.data.Features
import app.steady.rules.KeyHolder
import app.steady.rules.Plan
import app.steady.rules.Relation
import app.steady.rules.Schedule
import app.steady.rules.Surface
import app.steady.rules.SurfaceMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalTime

class SetupViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private var n = 0
    private fun vm(repo: FakePlanRepository = FakePlanRepository(), features: Set<String> = emptySet()) =
        SetupViewModel(repo, FakeEntitlements(features)) { "id${++n}" }

    @Test fun defaults_matchPrototype_andSaveWritesPlan() = runTest {
        val repo = FakePlanRepository()
        val vm = vm(repo)
        val s = vm.state.value
        assertFalse(s.loading)
        assertFalse(s.isEdit)
        assertEquals(setOf("instagram.reels", "youtube.shorts"), s.selectedDistractions)
        assertEquals(setOf(SetupDefaults.COACHING_ID, SetupDefaults.NIGHT_ID), s.selectedSchedules)
        assertEquals(Relation.SELF, s.relation)
        assertTrue(s.canSave)

        vm.save()
        assertTrue(vm.state.value.saved)
        val plan = repo.saved.single()
        assertEquals(1, plan.version)
        assertEquals(SetupDefaults.presets, plan.schedules)
        assertNull(plan.keyHolder)

        vm.consumeSaved()
        assertFalse(vm.state.value.saved)
    }

    @Test fun friendNeedsName() = runTest {
        val vm = vm()
        vm.setRelation(Relation.FRIEND)
        assertTrue(vm.state.value.needsName)
        assertFalse(vm.state.value.canSave)
        vm.setKeyHolderName("Asha")
        assertTrue(vm.state.value.canSave)
    }

    @Test fun needsAtLeastOneDistractionAndSchedule() = runTest {
        val vm = vm()
        vm.toggleDistraction("instagram.reels")
        vm.toggleDistraction("youtube.shorts")
        assertFalse(vm.state.value.canSave)
        vm.toggleDistraction("snapchat.spotlight")
        assertTrue(vm.state.value.canSave)
        vm.toggleSchedule(SetupDefaults.COACHING_ID)
        vm.toggleSchedule(SetupDefaults.NIGHT_ID)
        assertFalse(vm.state.value.canSave)
        vm.save()
        assertFalse(vm.state.value.saved)
    }

    @Test fun customSchedule_addEditDelete_respectsFreeLimit() = runTest {
        val vm = vm()
        vm.newSchedule()
        vm.updateDraft(vm.state.value.editor!!.copy(label = "Sunday revision", days = setOf(7), start = LocalTime.of(10, 0), end = LocalTime.of(12, 0)))
        vm.saveDraft()
        var s = vm.state.value
        assertNull(s.editor)
        val custom = s.schedules.last()
        assertEquals("custom.id1", custom.id)
        // Free plan already has 2 selected → added but not selected.
        assertFalse(custom.id in s.selectedSchedules)
        assertTrue(s.limitHit)
        vm.toggleSchedule(custom.id)
        assertTrue(vm.state.value.limitHit)

        vm.toggleSchedule(SetupDefaults.NIGHT_ID)
        vm.toggleSchedule(custom.id)
        s = vm.state.value
        assertFalse(s.limitHit)
        assertEquals(setOf(SetupDefaults.COACHING_ID, custom.id), s.selectedSchedules)

        vm.editSchedule(custom.id)
        vm.updateDraft(vm.state.value.editor!!.copy(end = LocalTime.of(13, 0)))
        vm.saveDraft()
        assertEquals("13:00", vm.state.value.schedules.first { it.id == custom.id }.end)

        vm.deleteSchedule(SetupDefaults.COACHING_ID) // presets can't be deleted
        assertEquals(3, vm.state.value.schedules.size)
        vm.deleteSchedule(custom.id)
        assertEquals(2, vm.state.value.schedules.size)
        assertEquals(setOf(SetupDefaults.COACHING_ID), vm.state.value.selectedSchedules)
    }

    @Test fun invalidDraftIsNotSaved_cancelCloses() = runTest {
        val vm = vm()
        vm.newSchedule()
        vm.saveDraft() // blank label
        assertTrue(vm.state.value.editor != null)
        vm.cancelDraft()
        assertNull(vm.state.value.editor)
        assertEquals(2, vm.state.value.schedules.size)
    }

    @Test fun plusHasNoScheduleLimit() = runTest {
        val vm = vm(features = setOf(Features.UNLIMITED_SCHEDULES))
        assertNull(vm.state.value.scheduleLimit)
        vm.newSchedule()
        vm.updateDraft(vm.state.value.editor!!.copy(label = "Extra"))
        vm.saveDraft()
        assertEquals(3, vm.state.value.selectedSchedules.size)
    }

    @Test fun editMode_prefillsFromSavedPlan_andBumpsVersion() = runTest {
        val custom = Schedule("custom.x", "Sunday", setOf(7), "10:00", "12:00")
        val existing = Plan(
            version = 7,
            distractions = listOf(Surface("snapchat.spotlight", SurfaceMode.HIDE_SECTION)),
            schedules = listOf(custom),
            keyHolder = KeyHolder("k", "Mum", Relation.PARENT),
        )
        val repo = FakePlanRepository(existing)
        val vm = vm(repo)
        val s = vm.state.value
        assertTrue(s.isEdit)
        assertEquals(setOf("snapchat.spotlight"), s.selectedDistractions)
        assertEquals(setOf("custom.x"), s.selectedSchedules)
        assertEquals(SetupDefaults.presets + custom, s.schedules)
        assertEquals(Relation.PARENT, s.relation)
        assertEquals("Mum", s.keyHolderName)

        vm.save()
        val saved = repo.saved.single()
        assertEquals(8, saved.version)
        assertEquals("k", saved.keyHolder!!.id)
    }
}
