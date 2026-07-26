package com.famage.remoconnect

import com.famage.remoconnect.data.model.RemoteKey
import com.famage.remoconnect.data.protocol.AdbProtocolEngine
import com.famage.remoconnect.data.protocol.AndroidTvRemoteEngine
import com.famage.remoconnect.data.repository.TvDeviceRepository
import com.famage.remoconnect.ui.viewmodel.AppTab
import com.famage.remoconnect.ui.viewmodel.RemoteViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var viewModel: RemoteViewModel
    private lateinit var repository: TvDeviceRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = TvDeviceRepository(
            discoveryService = null,
            adbEngine = AdbProtocolEngine(),
            androidTvEngine = AndroidTvRemoteEngine(),
            irEngine = null
        )
        viewModel = RemoteViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testTabSelection() {
        assertEquals(AppTab.REMOTE, viewModel.selectedTab.value)
        viewModel.selectTab(AppTab.TOUCHPAD)
        assertEquals(AppTab.TOUCHPAD, viewModel.selectedTab.value)
        viewModel.selectTab(AppTab.APPS)
        assertEquals(AppTab.APPS, viewModel.selectedTab.value)
    }

    @Test
    fun testPressKeyUpdatesLastPressed() {
        viewModel.pressKey(RemoteKey.POWER)
        assertEquals(RemoteKey.POWER, viewModel.lastPressedKey.value)
    }

    @Test
    fun testKeyboardDialogVisibility() {
        assertFalse(viewModel.showKeyboardDialog.value)
        viewModel.openKeyboardDialog()
        assertTrue(viewModel.showKeyboardDialog.value)
        viewModel.dismissKeyboardDialog()
        assertFalse(viewModel.showKeyboardDialog.value)
    }

    @Test
    fun testSavedDevicesInitiallyEmpty() {
        val devices = viewModel.savedDevices.value
        assertTrue("Saved devices should start empty without mock devices", devices.isEmpty())
    }

    @Test
    fun testAddManualTvPopulatesSavedDevices() {
        viewModel.addManualTv("Living Room TV", "192.168.74.3", 6466)
        val devices = viewModel.savedDevices.value
        assertEquals(1, devices.size)
        assertEquals("Living Room TV", devices.first().name)
    }

    @Test
    fun testMultipleTabTransitions() {
        val tabs = listOf(AppTab.REMOTE, AppTab.TOUCHPAD, AppTab.APPS, AppTab.DISCOVERY)
        for (tab in tabs) {
            viewModel.selectTab(tab)
            assertEquals(tab, viewModel.selectedTab.value)
        }
    }
}
