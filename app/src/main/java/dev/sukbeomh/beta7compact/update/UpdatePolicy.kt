package dev.sukbeomh.beta7compact.update

/** A published build the app can update to. [sha256] is lower-case hex when GitHub reports an asset digest. */
data class Release(val version: String, val tag: String, val notes: String, val page: String,
    val apkName: String, val apkUrl: String, val apkSize: Long, val sha256: String?)

/**
 * Platform-free update decisions.
 *
 * Release contract: a non-draft, non-prerelease GitHub release of [REPO] tagged `vX.Y.Z`, carrying one `.apk`
 * asset built with a higher versionCode and signed with the same key as the installed app.
 */
object UpdatePolicy {
    const val REPO = "SukbeomH/beta7compact"
    const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"
    const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"
    const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    const val MAX_RESPONSE_CHARS = 1_000_000
    const val MAX_APK_BYTES = 200L * 1024 * 1024
    private const val MAX_NOTES_CHARS = 2000

    /** `v0.10.0` -> [0, 10, 0]. Anything that is not one to four dot-separated numbers is not a version. */
    fun parseVersion(text: String): List<Int>? {
        val match = Regex("[vV]?([0-9]{1,6}(?:\\.[0-9]{1,6}){0,3})").matchEntire(text.trim()) ?: return null
        return match.groupValues[1].split('.').map { it.toInt() }
    }

    /** Numeric comparison, so 0.10.0 is newer than 0.9.0 and 1.0 equals 1.0.0. Unreadable versions never update. */
    fun isNewer(current: String, candidate: String): Boolean {
        val a = parseVersion(current) ?: return false
        val b = parseVersion(candidate) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return y > x
        }
        return false
    }

    /** Automatic checks run at most once per interval; a clock set backwards does not block them forever. */
    fun shouldCheck(enabled: Boolean, now: Long, lastCheck: Long): Boolean =
        enabled && (lastCheck <= 0L || now < lastCheck || now - lastCheck >= CHECK_INTERVAL_MS)

    /** Reads the "latest release" response. Null when it is not an installable release under the contract. */
    fun latestRelease(json: String): Release? {
        if (json.length > MAX_RESPONSE_CHARS) return null
        val root = runCatching { MiniJson(json).read() }.getOrNull() as? Map<*, *> ?: return null
        if (root["draft"] == true || root["prerelease"] == true) return null
        val tag = root["tag_name"] as? String ?: return null
        val version = parseVersion(tag)?.joinToString(".") ?: return null
        val asset = (root["assets"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().firstOrNull { apk(it) != null } ?: return null
        val (name, url, size) = apk(asset)!!
        val digest = (asset["digest"] as? String)?.lowercase()?.removePrefix("sha256:")?.takeIf { Regex("[0-9a-f]{64}").matches(it) }
        val page = (root["html_url"] as? String)?.takeIf { it.startsWith("$RELEASES_PAGE/") } ?: RELEASES_PAGE
        return Release(version, tag, (root["body"] as? String).orEmpty().trim().take(MAX_NOTES_CHARS), page, name, url, size, digest)
    }

    /** Only APKs served from this repository's own release downloads are accepted. */
    private fun apk(asset: Map<*, *>): Triple<String, String, Long>? {
        val name = asset["name"] as? String ?: return null
        val url = asset["browser_download_url"] as? String ?: return null
        val size = (asset["size"] as? Double)?.takeIf { it % 1.0 == 0.0 && it >= 1.0 && it <= MAX_APK_BYTES.toDouble() }?.toLong() ?: return null
        if (!name.endsWith(".apk", ignoreCase = true) || !url.startsWith(DOWNLOAD_PREFIX) || url.any { it.isWhitespace() }) return null
        return Triple(name, url, size)
    }
}

/** Minimal strict JSON reader for the release response: objects, arrays, strings, numbers, booleans and null. */
internal class MiniJson(private val s: String) {
    private var i = 0
    fun read(): Any? = value(0).also { ws(); require(i == s.length) { "Trailing JSON" } }
    private fun ws() { while (i < s.length && s[i] in " \n\r\t") i++ }
    private fun take(c: Char) { require(i < s.length && s[i] == c) { "Expected $c" }; i++ }
    private fun literal(word: String, result: Any?): Any? { require(s.startsWith(word, i)) { "Bad JSON literal" }; i += word.length; return result }
    private fun value(depth: Int): Any? {
        require(depth < 16) { "JSON too deep" }; ws(); require(i < s.length) { "Unexpected end" }
        return when (s[i]) {
            '{' -> obj(depth); '[' -> array(depth); '"' -> string()
            't' -> literal("true", true); 'f' -> literal("false", false); 'n' -> literal("null", null)
            else -> number()
        }
    }
    private fun obj(depth: Int): Map<String, Any?> {
        take('{'); val map = linkedMapOf<String, Any?>(); ws()
        if (i < s.length && s[i] == '}') { i++; return map }
        while (true) {
            ws(); val key = string(); ws(); take(':'); map[key] = value(depth + 1); ws()
            require(i < s.length) { "Unexpected end" }
            if (s[i] == '}') { i++; return map }; take(',')
        }
    }
    private fun array(depth: Int): List<Any?> {
        take('['); val list = ArrayList<Any?>(); ws()
        if (i < s.length && s[i] == ']') { i++; return list }
        while (true) {
            list += value(depth + 1); ws(); require(i < s.length) { "Unexpected end" }
            if (s[i] == ']') { i++; return list }; take(',')
        }
    }
    private fun number(): Double {
        val start = i
        while (i < s.length && s[i] in "0123456789eE+.-") i++
        val token = s.substring(start, i)
        require(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").matches(token)) { "Bad JSON number" }
        return token.toDouble()
    }
    private fun string(): String {
        take('"'); val out = StringBuilder()
        while (i < s.length) {
            val c = s[i++]
            when {
                c == '"' -> return out.toString()
                c == '\\' -> {
                    require(i < s.length) { "Bad escape" }
                    when (val e = s[i++]) {
                        '"', '\\', '/' -> out.append(e)
                        'b' -> out.append('\b'); 'f' -> out.append('\u000C'); 'n' -> out.append('\n')
                        'r' -> out.append('\r'); 't' -> out.append('\t')
                        'u' -> { require(i + 4 <= s.length) { "Bad escape" }; out.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> error("Bad escape")
                    }
                }
                else -> { require(c >= ' ') { "Control character in JSON string" }; out.append(c) }
            }
        }
        error("Unterminated JSON string")
    }
}
