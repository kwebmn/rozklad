package io.github.kwebmn.rozklad.ui.update

import io.github.kwebmn.rozklad.data.UpdateChecker
import io.github.kwebmn.rozklad.data.remote.NetworkException
import io.github.kwebmn.rozklad.data.remote.ReleaseInfo
import io.github.kwebmn.rozklad.data.remote.ReleaseSource
import io.github.kwebmn.rozklad.fakes.FIXED_CLOCK
import io.github.kwebmn.rozklad.fakes.FakeSettingsStore
import io.github.kwebmn.rozklad.fakes.MainDispatcherRule
import io.github.kwebmn.rozklad.update.ApkDownloader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    @get:Rule
    val main = MainDispatcherRule(UnconfinedTestDispatcher())

    private val newer = ReleaseInfo("v0.2.0", "notes", "https://x/a.apk")
    private var release: ReleaseInfo? = newer
    private var error: Exception? = null
    private var downloadOk = true
    private val downloader = object : ApkDownloader {
        override suspend fun download(release: ReleaseInfo) = downloadOk
    }

    private fun vm() = UpdateViewModel(
        UpdateChecker(ReleaseSource { error?.let { throw it }; release }, FakeSettingsStore(), "0.1.0", FIXED_CLOCK),
        downloader,
    )

    @Test
    fun `offers newer release then downloads it`() {
        val vm = vm()
        vm.check(force = false)
        assertEquals(UpdateState.Available(newer), vm.state.value)
        vm.download()
        assertEquals(UpdateState.Ready, vm.state.value)
    }

    @Test
    fun `failed download is reported`() {
        downloadOk = false
        val vm = vm()
        vm.check(force = true)
        vm.download()
        assertTrue(vm.state.value is UpdateState.Failed)
    }

    @Test
    fun `manual check reports up to date`() {
        release = newer.copy(version = "v0.1.0")
        val vm = vm()
        vm.check(force = true)
        assertEquals(UpdateState.UpToDate, vm.state.value)
    }

    @Test
    fun `automatic check stays silent on errors`() {
        error = NetworkException(IOException("down"))
        val vm = vm()
        vm.check(force = false)
        assertEquals(UpdateState.Idle, vm.state.value)
        vm.check(force = true)
        assertTrue(vm.state.value is UpdateState.Failed)
    }
}
