package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.update.UpdatePolicy
import kotlin.test.*
import org.junit.Test

class UpdatePolicyTest {
    private val download = "https://github.com/SukbeomH/beta7compact/releases/download/v0.5.0/beta7compact-0.5.0.apk"
    private fun release(tag: String = "v0.5.0", flags: String = "\"draft\": false, \"prerelease\": false",
        assets: String = asset("beta7compact-0.5.0.apk", download)) = """
        {"url": "https://api.github.com/repos/SukbeomH/beta7compact/releases/1", "tag_name": "$tag", $flags,
         "html_url": "https://github.com/SukbeomH/beta7compact/releases/tag/$tag", "name": null,
         "body": "- 픽셀 아이콘\r\n- \"필름\" 썸네일 ✨", "author": {"login": "someone", "id": 1},
         "assets": [$assets]}"""
    private fun asset(name: String, url: String, size: String = "12345678", extra: String = "") =
        """{"name": "$name", "size": $size, "content_type": "application/vnd.android.package-archive", "browser_download_url": "$url"$extra}"""

    @Test fun versionsCompareNumericallyAndIgnoreThePrefix() {
        assertEquals(listOf(0, 10, 0), UpdatePolicy.parseVersion("v0.10.0"))
        assertTrue(UpdatePolicy.isNewer("0.4.0", "v0.5.0"))
        assertTrue(UpdatePolicy.isNewer("0.9.0", "0.10.0"))
        assertTrue(UpdatePolicy.isNewer("0.4.0", "0.4.0.1"))
        assertFalse(UpdatePolicy.isNewer("0.4.0", "v0.4.0"))
        assertFalse(UpdatePolicy.isNewer("1.0", "1.0.0"))
        assertFalse(UpdatePolicy.isNewer("0.5.0", "0.4.9"))
    }

    @Test fun unreadableVersionsNeverTriggerAnUpdate() {
        for (bad in listOf("", "latest", "v1.2-beta", "1..2", "1.2.3.4.5", "v", "-1.0", "9999999.0")) {
            assertNull(UpdatePolicy.parseVersion(bad), bad)
            assertFalse(UpdatePolicy.isNewer("0.4.0", bad), bad)
        }
        assertFalse(UpdatePolicy.isNewer("debug", "v9.0.0"))
    }

    @Test fun automaticCheckRunsAtMostOncePerDayUnlessTheClockMovedBack() {
        val day = UpdatePolicy.CHECK_INTERVAL_MS
        assertTrue(UpdatePolicy.shouldCheck(true, 1_000L, 0L))
        assertFalse(UpdatePolicy.shouldCheck(true, 10 * day + day - 1, 10 * day))
        assertTrue(UpdatePolicy.shouldCheck(true, 11 * day, 10 * day))
        assertTrue(UpdatePolicy.shouldCheck(true, 5 * day, 10 * day))
        assertFalse(UpdatePolicy.shouldCheck(false, 11 * day, 0L))
    }

    @Test fun latestReleaseIsReadFromTheGitHubResponse() {
        val parsed = assertNotNull(UpdatePolicy.latestRelease(release(
            assets = asset("notes.txt", "https://github.com/SukbeomH/beta7compact/releases/download/v0.5.0/notes.txt") + "," +
                asset("beta7compact-0.5.0.apk", download, extra = ", \"digest\": \"sha256:${"AB".repeat(32)}\""))))
        assertEquals("0.5.0", parsed.version)
        assertEquals("v0.5.0", parsed.tag)
        assertEquals("beta7compact-0.5.0.apk", parsed.apkName)
        assertEquals(download, parsed.apkUrl)
        assertEquals(12345678L, parsed.apkSize)
        assertEquals("ab".repeat(32), parsed.sha256)
        assertEquals("https://github.com/SukbeomH/beta7compact/releases/tag/v0.5.0", parsed.page)
        assertEquals("- 픽셀 아이콘\r\n- \"필름\" 썸네일 ✨", parsed.notes)
    }

    @Test fun digestIsOptionalAndMalformedDigestsAreIgnored() {
        assertNull(assertNotNull(UpdatePolicy.latestRelease(release())).sha256)
        assertNull(assertNotNull(UpdatePolicy.latestRelease(release(
            assets = asset("a.apk", download, extra = ", \"digest\": \"md5:abcd\"")))).sha256)
    }

    @Test fun releasesOutsideTheContractAreNotOffered() {
        assertNull(UpdatePolicy.latestRelease(release(flags = "\"draft\": true, \"prerelease\": false")))
        assertNull(UpdatePolicy.latestRelease(release(flags = "\"draft\": false, \"prerelease\": true")))
        assertNull(UpdatePolicy.latestRelease(release(tag = "nightly")))
        assertNull(UpdatePolicy.latestRelease(release(assets = "")))
        assertNull(UpdatePolicy.latestRelease(release(assets = asset("readme.md", download))))
        // APKs hosted anywhere but this repository's release downloads are refused.
        assertNull(UpdatePolicy.latestRelease(release(assets = asset("x.apk", "https://example.com/x.apk"))))
        assertNull(UpdatePolicy.latestRelease(release(assets = asset("x.apk", "http://github.com/SukbeomH/beta7compact/releases/download/v0.5.0/x.apk"))))
        assertNull(UpdatePolicy.latestRelease(release(assets = asset("x.apk", "https://github.com/someone-else/beta7compact/releases/download/v0.5.0/x.apk"))))
        assertNull(UpdatePolicy.latestRelease(release(assets = asset("x.apk", download, size = "0"))))
        assertNull(UpdatePolicy.latestRelease(release(assets = asset("x.apk", download, size = "${UpdatePolicy.MAX_APK_BYTES + 1}"))))
    }

    @Test fun brokenResponsesAreRejectedWithoutThrowing() {
        for (bad in listOf("", "[]", "null", "{", "{\"tag_name\": \"v0.5.0\"", "{\"tag_name\": 5}", "not json",
            "{\"tag_name\": \"v0.5.0\", \"assets\": {}}", release() + "x")) assertNull(UpdatePolicy.latestRelease(bad), bad)
        assertNull(UpdatePolicy.latestRelease(" ".repeat(UpdatePolicy.MAX_RESPONSE_CHARS + 1)))
    }
}
