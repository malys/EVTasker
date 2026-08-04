package com.mg4.tasker.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OTA update policy — unstable channel only. The updater installs code on a vehicle, so the
 * origin checks are the part that must not regress.
 */
class OtaUpdaterTest {

    // ---- URL allowlist ----

    @Test fun `https from an allowed host is accepted`() {
        assertTrue(OtaUpdater.isAllowedUrl("https://github.com/malys/MG4Tasker/releases/download/v1/app.apk"))
        assertTrue(OtaUpdater.isAllowedUrl("https://objects.githubusercontent.com/x.apk"))
    }

    @Test fun `http is rejected even on an allowed host`() {
        assertFalse(OtaUpdater.isAllowedUrl("http://github.com/malys/app.apk"))
    }

    @Test fun `foreign hosts are rejected`() {
        assertFalse(OtaUpdater.isAllowedUrl("https://evil.example.com/app.apk"))
    }

    @Test fun `lookalike hosts are rejected`() {
        // Exact match, never a suffix test.
        assertFalse(OtaUpdater.isAllowedUrl("https://github.com.attacker.net/app.apk"))
        assertFalse(OtaUpdater.isAllowedUrl("https://evil-github.com/app.apk"))
        assertFalse(OtaUpdater.isAllowedUrl("https://notgithub.com/app.apk"))
    }

    @Test fun `garbage urls are rejected`() {
        assertFalse(OtaUpdater.isAllowedUrl("not a url"))
        assertFalse(OtaUpdater.isAllowedUrl(""))
    }

    // ---- version comparison ----

    @Test fun `segments extracts the numeric core`() {
        assertArrayEquals(intArrayOf(1, 0, 0, 42), OtaUpdater.segments("v1.0.0.42-unstable"))
        assertArrayEquals(intArrayOf(2, 3), OtaUpdater.segments("2.3+build7"))
    }

    @Test fun `a non numeric segment becomes zero, not dropped`() {
        // Otherwise later segments shift left and a patch reads as a minor.
        assertArrayEquals(intArrayOf(1, 0, 5), OtaUpdater.segments("1.x.5"))
    }

    @Test fun `isNewer compares numerically, not lexically`() {
        assertTrue(OtaUpdater.isNewer("1.0.0.10", "1.0.0.9"))   // 10 > 9, not "10" < "9"
        assertTrue(OtaUpdater.isNewer("1.2.0", "1.1.9"))
        assertFalse(OtaUpdater.isNewer("1.0.0.5", "1.0.0.5"))   // equal is not newer
        assertFalse(OtaUpdater.isNewer("1.0.0.4", "1.0.0.5"))
    }

    @Test fun `unstable suffix does not affect comparison`() {
        assertTrue(OtaUpdater.isNewer("v1.0.0.43-unstable", "1.0.0.42-unstable"))
    }

    // ---- version from asset name ----

    @Test fun `version is read from the asset name`() {
        // The release tag is the constant "unstable", so the asset name carries the build.
        assertEquals("1.0.0.42", OtaUpdater.versionFromAssetName("MG4Tasker-unstable-1.0.0.42.apk"))
        assertEquals("1.0.0.100", OtaUpdater.versionFromAssetName("MG4Tasker-unstable-1.0.0.100.APK"))
    }

    @Test fun `asset name without a version is ignored`() {
        assertNull(OtaUpdater.versionFromAssetName("MG4Tasker-unstable.apk"))
        assertNull(OtaUpdater.versionFromAssetName("unstable"))
    }

    // ---- download file name ----

    @Test fun `download file name is sanitised for the filesystem`() {
        val name = OtaUpdater.downloadFileName("v1.0.0.42-unstable/../etc")
        assertFalse(name.contains("/"))
        assertTrue(name.startsWith("MG4Tasker-unstable-"))
        assertTrue(name.endsWith(".apk"))
    }

    @Test fun `null or empty version yields a safe name`() {
        assertTrue(OtaUpdater.downloadFileName(null).contains("unknown"))
        assertTrue(OtaUpdater.downloadFileName("").contains("unknown"))
    }

    @Test fun `pm install requires both success text and zero exit`() {
        assertTrue(OtaUpdater.installSucceeded(0, "Success\n"))
        assertFalse(OtaUpdater.installSucceeded(1, "Success\n"))
        assertFalse(OtaUpdater.installSucceeded(0, "Failure [INSTALL_FAILED]"))
    }
}
