package com.explorer.fileexplorer

import android.content.Intent
import android.content.pm.PackageManager
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runtime smoke coverage for components that are resolved by Android or other apps. */
@RunWith(AndroidJUnit4::class)
class ExportedSurfaceSmokeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val packageManager = context.packageManager
    private val packageName = context.packageName

    @Test
    fun exportedComponentsAndSystemEntryPointsResolve() {
        val packageInfo = packageManager.getPackageInfo(
            packageName,
            PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES or
                PackageManager.GET_PROVIDERS or
                PackageManager.GET_META_DATA,
        )
        val activities = packageInfo.activities.orEmpty()
        val services = packageInfo.services.orEmpty()
        val providers = packageInfo.providers.orEmpty()

        val mainActivity = activities.single { it.name == "$packageName.MainActivity" }
        assertTrue(mainActivity.exported)
        assertNotNull(
            packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setPackage(packageName),
                PackageManager.MATCH_ALL,
            ),
        )

        val tileService = services.single { it.name.endsWith("ShareServerTileService") }
        assertTrue(tileService.exported)
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", tileService.permission)
        assertTrue(
            packageManager.queryIntentServices(
                Intent("android.service.quicksettings.action.QS_TILE").setPackage(packageName),
                PackageManager.MATCH_ALL,
            ).any { it.serviceInfo.name == tileService.name },
        )

        services.filter { it.name.endsWith("TransferService") || it.name.endsWith("ShareServerService") }
            .forEach { assertTrue(!it.exported, "Internal service unexpectedly exported: ${it.name}") }

        val fileProvider = providers.single { it.authority == "$packageName.provider" }
        assertTrue(!fileProvider.exported)
        assertTrue(fileProvider.grantUriPermissions)

        val documentsProvider = providers.single { it.authority == "$packageName.documents" }
        assertTrue(documentsProvider.exported)
        assertEquals("android.permission.MANAGE_DOCUMENTS", documentsProvider.readPermission)
        assertEquals("android.permission.MANAGE_DOCUMENTS", documentsProvider.writePermission)
        assertTrue(documentsProvider.grantUriPermissions)
        assertTrue(
            packageManager.queryIntentContentProviders(
                Intent("android.content.action.DOCUMENTS_PROVIDER"),
                PackageManager.MATCH_ALL,
            ).any { it.providerInfo.authority == documentsProvider.authority },
        )

        val castOptionsProvider = packageInfo.applicationInfo?.metaData?.getString(
            "com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME",
        )
        val castProviderClass = castOptionsProvider ?: error("Cast options provider metadata is missing")
        assertEquals("com.explorer.fileexplorer.feature.browser.CastOptionsProvider", castProviderClass)
        assertNotNull(Class.forName(castProviderClass).getDeclaredConstructor().newInstance())

        assertNotNull(resolvePicker(Intent.ACTION_OPEN_DOCUMENT))
        assertNotNull(resolvePicker(Intent.ACTION_CREATE_DOCUMENT))
        assertNotNull(resolvePicker(Intent.ACTION_GET_CONTENT))
    }

    @Test
    fun documentsAndFileSharingProvidersWorkThroughContentResolver() {
        val rootsUri = DocumentsContract.buildRootsUri("$packageName.documents")
        context.contentResolver.query(rootsUri, null, null, null, null).use { cursor ->
            assertNotNull(cursor)
            assertTrue(cursor!!.columnCount > 0)
        }

        val sharedFile = File(context.cacheDir, "exported-surface-smoke.txt").apply {
            writeText("smoke")
        }
        try {
            val uri = FileProvider.getUriForFile(context, "$packageName.provider", sharedFile)
            assertEquals("content", uri.scheme)
            assertEquals("$packageName.provider", uri.authority)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            assertNotNull(packageManager.resolveActivity(sendIntent, PackageManager.MATCH_ALL))
        } finally {
            sharedFile.delete()
        }
    }

    @Test
    fun mainActivityCanRecreateWithoutUserSessionState() {
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            scenario.onActivity { activity -> assertNotNull(activity.window.decorView.rootView) }
            scenario.recreate()
            scenario.onActivity { activity -> assertNotNull(activity.window.decorView.rootView) }
        } finally {
            scenario.close()
        }
    }

    private fun resolvePicker(action: String) = packageManager.resolveActivity(
        Intent(action).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        },
        PackageManager.MATCH_ALL,
    )
}
