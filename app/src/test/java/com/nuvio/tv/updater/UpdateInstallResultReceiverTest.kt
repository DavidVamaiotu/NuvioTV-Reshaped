package com.nuvio.tv.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import io.mockk.*
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateInstallResultReceiverTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var receiver: UpdateInstallResultReceiver
    private lateinit var apk: File

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>(), any<Throwable>()) } returns 0
        mockkObject(ApkInstaller, UpdateRelaunchReceiver.Companion)
        every { ApkInstaller.install(any(), any(), any(), any()) } just Runs
        every { ApkInstaller.launchSystemInstaller(any(), any()) } just Runs
        every { UpdateRelaunchReceiver.cancelReopen(any()) } just Runs
        context = mockk(relaxed = true)
        receiver = mockk()
        every { receiver.onReceive(any(), any()) } answers { callOriginal() }
        apk = temporary.newFile("update.apk")
        // Run the private retry path too; only Android installation side effects are mocked.
        every { receiver["retryAfterFailure"](context, apk, any<Boolean>(), any<Boolean>()) } answers { callOriginal() }
    }

    @After
    fun tearDown() {
        unmockkObject(ApkInstaller, UpdateRelaunchReceiver.Companion)
        unmockkStatic(Log::class)
    }

    @Test
    fun cancelledInstallDoesNotRetryOrOpenAnotherInstaller() {
        receiver.onReceive(context, result(PackageInstaller.STATUS_FAILURE_ABORTED, withDex = true))
        verify(exactly = 1) { UpdateRelaunchReceiver.cancelReopen(context) }
        verify(exactly = 0) { ApkInstaller.install(any(), any(), any(), any()) }
        verify(exactly = 0) { ApkInstaller.launchSystemInstaller(any(), any()) }
        assertTrue(apk.exists()) // The user can try the already downloaded update again later.
    }

    @Test
    fun realInstallFailureRetriesWithoutDexMetadata() {
        receiver.onReceive(context, result(PackageInstaller.STATUS_FAILURE, withDex = true))
        verify(exactly = 1) { ApkInstaller.install(context, apk, false, false) }
        verify(exactly = 0) { ApkInstaller.launchSystemInstaller(any(), any()) }
        verify { UpdateRelaunchReceiver.cancelReopen(context) }
    }

    @Test
    fun manualFailureWithoutDexMetadataOpensTheSystemInstaller() {
        receiver.onReceive(context, result(PackageInstaller.STATUS_FAILURE))
        verify(exactly = 1) { ApkInstaller.launchSystemInstaller(context, apk) }
        verify(exactly = 0) { ApkInstaller.install(any(), any(), any(), any()) }
    }

    @Test
    fun backgroundFailureWithoutDexMetadataNeverOpensAScreen() {
        receiver.onReceive(context, result(PackageInstaller.STATUS_FAILURE, background = true))
        verify(exactly = 0) { ApkInstaller.install(any(), any(), any(), any()) }
        verify(exactly = 0) { ApkInstaller.launchSystemInstaller(any(), any()) }
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test
    fun backgroundFailureWithDexMetadataRetriesSilently() {
        receiver.onReceive(context, result(PackageInstaller.STATUS_FAILURE, background = true, withDex = true))
        verify(exactly = 1) { ApkInstaller.install(context, apk, true, false) }
        verify(exactly = 0) { ApkInstaller.launchSystemInstaller(any(), any()) }
    }

    @Test
    fun backgroundConfirmationIsAbandonedWithoutPrompting() {
        receiver.onReceive(context, result(PackageInstaller.STATUS_PENDING_USER_ACTION, background = true))
        verify(exactly = 1) { context.packageManager.packageInstaller.abandonSession(42) }
        verify(exactly = 0) { context.startActivity(any()) }
        verify(exactly = 0) { ApkInstaller.install(any(), any(), any(), any()) }
    }

    @Test
    fun successfulInstallLeavesTheReopenRequestForTheNewVersion() {
        receiver.onReceive(context, result(PackageInstaller.STATUS_SUCCESS))
        verify(exactly = 0) { UpdateRelaunchReceiver.cancelReopen(any()) }
        verify(exactly = 0) { ApkInstaller.install(any(), any(), any(), any()) }
        verify(exactly = 0) { ApkInstaller.launchSystemInstaller(any(), any()) }
    }

    private fun result(status: Int, background: Boolean = false, withDex: Boolean = false): Intent = mockk {
        every { getIntExtra(PackageInstaller.EXTRA_STATUS, any()) } returns status
        every { getIntExtra(PackageInstaller.EXTRA_SESSION_ID, any()) } returns 42
        every { getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) } returns null
        every { getStringExtra("com.nuvio.tv.updater.APK_PATH") } returns apk.absolutePath
        every { getBooleanExtra("com.nuvio.tv.updater.BACKGROUND", any()) } returns background
        every { getBooleanExtra("com.nuvio.tv.updater.WITH_DEX_METADATA", any()) } returns withDex
    }
}
