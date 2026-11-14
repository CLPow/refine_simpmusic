package com.maxrave.simpmusic.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUpdateTest {
    @Test
    fun versionCompare() {
        assertTrue(UpdateVersion.isNewer("v2.4.1", "2.4.0"))
        assertTrue(UpdateVersion.isNewer("2.10.0", "2.9.9"))
        assertTrue(UpdateVersion.isNewer("v2.5", "2.4.9"))
        assertFalse(UpdateVersion.isNewer("v2.4.0", "2.4.0"))
        assertFalse(UpdateVersion.isNewer("v2.4.0", "2.4.1"))
        assertFalse(UpdateVersion.isNewer("v2.0.41", "2.4.1"))
        assertFalse(UpdateVersion.isNewer("v2.4.1", "2.4.1-dev"))
        // A tag that is not a version is never offered.
        assertFalse(UpdateVersion.isNewer("nightly", "2.4.1"))
    }

    private fun release(
        tag: String = "v2.4.1",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: String = """{"name":"RefineMusic-2.4.1-arm64-v8a.apk","size":123,"browser_download_url":"https://example.invalid/a.apk"}""",
    ) = """{"tag_name":"$tag","body":"Notes","draft":$draft,"prerelease":$prerelease,"assets":[$assets],"extra":1}"""

    @Test
    fun parsesTheApk() {
        val parsed = parseLatestRelease(release(), "arm64-v8a")!!
        assertEquals("2.4.1", parsed.version)
        assertEquals("Notes", parsed.notes)
        assertEquals("https://example.invalid/a.apk", parsed.apkUrl)
        assertEquals(123, parsed.apkSize)
    }

    @Test
    fun picksTheApkForThisAbi() {
        val assets =
            """{"name":"RefineMusic-2.4.1-armeabi-v7a.apk","size":1,"browser_download_url":"https://example.invalid/v7.apk"},""" +
                """{"name":"RefineMusic-2.4.1-arm64-v8a.apk","size":2,"browser_download_url":"https://example.invalid/v8.apk"}"""
        assertEquals("https://example.invalid/v8.apk", parseLatestRelease(release(assets = assets), "arm64-v8a")!!.apkUrl)
    }

    @Test
    fun skipsWhatShouldNotBeOffered() {
        assertNull(parseLatestRelease(release(draft = true), null))
        assertNull(parseLatestRelease(release(prerelease = true), null))
        assertNull(parseLatestRelease(release(assets = """{"name":"source.zip","size":1,"browser_download_url":"https://example.invalid/s.zip"}"""), null))
        assertNull(parseLatestRelease("not json", null))
    }
}
