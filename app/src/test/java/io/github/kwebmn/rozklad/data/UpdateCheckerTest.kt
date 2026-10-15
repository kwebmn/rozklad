package io.github.kwebmn.rozklad.data

import io.github.kwebmn.rozklad.data.remote.NetworkException
import io.github.kwebmn.rozklad.data.remote.ReleaseInfo
import io.github.kwebmn.rozklad.data.remote.ReleaseSource
import io.github.kwebmn.rozklad.fakes.FIXED_CLOCK
import io.github.kwebmn.rozklad.fakes.FakeSettingsStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class UpdateCheckerTest {
    private val newer = ReleaseInfo("v0.2.0", "notes", "https://x/a.apk")
    private var calls = 0
    private var release: ReleaseInfo? = newer
    private var error: Exception? = null
    private val source = ReleaseSource { calls++; error?.let { throw it }; release }
    private val now = FIXED_CLOCK.millis()

    private fun checker(settings: FakeSettingsStore) = UpdateChecker(source, settings, "0.1.0", FIXED_CLOCK)

    @Test
    fun `finds a newer release and records the check`() = runTest {
        val settings = FakeSettingsStore()
        assertEquals(newer, checker(settings).check(force = false))
        assertEquals(now, settings.flow.value.lastUpdateCheck)
    }

    @Test
    fun `automatic check runs at most daily`() = runTest {
        val settings = FakeSettingsStore(AppSettings(lastUpdateCheck = now - 60_000))
        assertNull(checker(settings).check(force = false))
        assertEquals(0, calls)
        assertEquals(newer, checker(settings).check(force = true))
        assertEquals(1, calls)
    }

    @Test
    fun `same or older version is not offered`() = runTest {
        release = newer.copy(version = "v0.1.0")
        assertNull(checker(FakeSettingsStore()).check(force = true))
    }

    @Test
    fun `network failure does not record the check`() {
        error = NetworkException(IOException("down"))
        val settings = FakeSettingsStore()
        assertThrows(NetworkException::class.java) { runBlocking { checker(settings).check(force = false) } }
        assertEquals(0L, settings.flow.value.lastUpdateCheck)
    }
}
