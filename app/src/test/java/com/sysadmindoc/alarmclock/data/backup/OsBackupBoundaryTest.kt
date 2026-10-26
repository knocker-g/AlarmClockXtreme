package com.sysadmindoc.alarmclock.data.backup

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
class OsBackupBoundaryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `AndroidManifest disables allowBackup and removes backupAgent`() {
        val appInfo = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA
        )

        assertNull("backupAgent MUST be null in AndroidManifest", appInfo.backupAgentName)

        val projectDir = File(System.getProperty("user.dir") ?: ".")
        val manifestFile = File(projectDir, "src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml must exist", manifestFile.exists())

        val db = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = db.parse(manifestFile)
        val appNodes = doc.getElementsByTagName("application")
        assertTrue("application tag must exist in manifest", appNodes.length > 0)

        val appElement = appNodes.item(0) as org.w3c.dom.Element
        assertEquals("android:allowBackup MUST be false", "false", appElement.getAttribute("android:allowBackup"))
        assertFalse("android:backupAgent MUST NOT be present", appElement.hasAttribute("android:backupAgent"))
        assertFalse("android:restoreAnyVersion MUST NOT be present", appElement.hasAttribute("android:restoreAnyVersion"))
    }

    @Test
    fun `backup_rules xml excludes all storage domains for API 26-30`() {
        val projectDir = File(System.getProperty("user.dir") ?: ".")
        val backupRulesFile = File(projectDir, "src/main/res/xml/backup_rules.xml")
        assertTrue("backup_rules.xml must exist", backupRulesFile.exists())

        val db = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = db.parse(backupRulesFile)
        val excludeNodes = doc.getElementsByTagName("exclude")

        val excludedDomains = mutableSetOf<String>()
        for (i in 0 until excludeNodes.length) {
            val elem = excludeNodes.item(i) as org.w3c.dom.Element
            excludedDomains.add(elem.getAttribute("domain"))
            assertEquals("path MUST be '.' for full domain exclusion", ".", elem.getAttribute("path"))
        }

        val requiredDomains = setOf("database", "sharedpref", "file", "external", "root")
        assertTrue("backup_rules.xml MUST exclude all storage domains $requiredDomains", excludedDomains.containsAll(requiredDomains))
    }

    @Test
    fun `data_extraction_rules xml excludes cloud-backup and device-transfer for API 31+`() {
        val projectDir = File(System.getProperty("user.dir") ?: ".")
        val dataExtractionRulesFile = File(projectDir, "src/main/res/xml/data_extraction_rules.xml")
        assertTrue("data_extraction_rules.xml must exist", dataExtractionRulesFile.exists())

        val db = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = db.parse(dataExtractionRulesFile)

        val cloudNodes = doc.getElementsByTagName("cloud-backup")
        assertTrue("cloud-backup tag must exist", cloudNodes.length > 0)
        val cloudElement = cloudNodes.item(0) as org.w3c.dom.Element
        val cloudExcludes = cloudElement.getElementsByTagName("exclude")

        val cloudDomains = mutableSetOf<String>()
        for (i in 0 until cloudExcludes.length) {
            val elem = cloudExcludes.item(i) as org.w3c.dom.Element
            cloudDomains.add(elem.getAttribute("domain"))
            assertEquals(".", elem.getAttribute("path"))
        }

        val deviceTransferNodes = doc.getElementsByTagName("device-transfer")
        assertTrue("device-transfer tag must exist", deviceTransferNodes.length > 0)
        val deviceTransferElement = deviceTransferNodes.item(0) as org.w3c.dom.Element
        val deviceTransferExcludes = deviceTransferElement.getElementsByTagName("exclude")

        val deviceTransferDomains = mutableSetOf<String>()
        for (i in 0 until deviceTransferExcludes.length) {
            val elem = deviceTransferExcludes.item(i) as org.w3c.dom.Element
            deviceTransferDomains.add(elem.getAttribute("domain"))
            assertEquals(".", elem.getAttribute("path"))
        }

        val requiredDomains = setOf("database", "sharedpref", "file", "external", "root")
        assertTrue("cloud-backup MUST exclude all storage domains", cloudDomains.containsAll(requiredDomains))
        assertTrue("device-transfer MUST exclude all storage domains", deviceTransferDomains.containsAll(requiredDomains))
    }
}
