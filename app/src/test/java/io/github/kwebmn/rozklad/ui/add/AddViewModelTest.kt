package io.github.kwebmn.rozklad.ui.add

import io.github.kwebmn.rozklad.data.ScheduleRepository
import io.github.kwebmn.rozklad.data.remote.NetworkException
import io.github.kwebmn.rozklad.data.remote.ParsedSchedule
import io.github.kwebmn.rozklad.domain.SubscriptionType
import io.github.kwebmn.rozklad.fakes.FIXED_CLOCK
import io.github.kwebmn.rozklad.fakes.FakeLessonStore
import io.github.kwebmn.rozklad.fakes.FakeScheduleSource
import io.github.kwebmn.rozklad.fakes.MainDispatcherRule
import io.github.kwebmn.rozklad.ui.NETWORK_ERROR
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AddViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val source = FakeScheduleSource()
    private val repo = ScheduleRepository(source, FakeLessonStore(), FIXED_CLOCK)

    @Test
    fun `short query does not hit the site`() = runTest(main.dispatcher) {
        val vm = AddViewModel(repo)
        vm.setQuery("І")
        advanceUntilIdle()
        assertTrue(source.suggestCalls.isEmpty())
    }

    @Test
    fun `typing is debounced`() = runTest(main.dispatcher) {
        source.suggestions = listOf("ІПЗс-24-1")
        val vm = AddViewModel(repo)
        vm.setQuery("ІП")
        advanceTimeBy(100)
        vm.setQuery("ІПЗ")
        advanceUntilIdle()
        assertEquals(listOf("ІПЗ"), source.suggestCalls)
        assertEquals(listOf("ІПЗс-24-1"), vm.state.value.suggestions)
        assertTrue(vm.state.value.searched)
    }

    @Test
    fun `teacher search needs three characters`() = runTest(main.dispatcher) {
        val vm = AddViewModel(repo)
        vm.setType(SubscriptionType.TEACHER)
        vm.setQuery("Де")
        advanceUntilIdle()
        assertTrue(source.suggestCalls.isEmpty())
        vm.setQuery("Дем")
        advanceUntilIdle()
        assertEquals(listOf("Дем"), source.suggestCalls)
    }

    @Test
    fun `selecting a suggestion adds a subscription`() = runTest(main.dispatcher) {
        source.schedule = ParsedSchedule(SubscriptionType.GROUP, "ІПЗс-24-1", "-607", emptyList())
        val vm = AddViewModel(repo)
        vm.select("ІПЗс-24-1")
        advanceUntilIdle()
        assertEquals("-607", vm.state.value.added?.siteId)
        assertFalse(vm.state.value.loading)
        vm.consumeAdded()
        assertNull(vm.state.value.added)
    }

    @Test
    fun `network error while adding is shown`() = runTest(main.dispatcher) {
        source.error = NetworkException(IOException("down"))
        val vm = AddViewModel(repo)
        vm.select("ІПЗс-24-1")
        advanceUntilIdle()
        assertEquals(NETWORK_ERROR, vm.state.value.error)
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.added)
    }
}
