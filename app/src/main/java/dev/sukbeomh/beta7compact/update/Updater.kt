package dev.sukbeomh.beta7compact.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import dev.sukbeomh.beta7compact.App
import dev.sukbeomh.beta7compact.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    /** [message] explains a manual check that found nothing newer. */
    data class UpToDate(val message: String) : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val done: Long) : UpdateState
    /** The system installer has the APK. [awaitingUser] means its confirmation screen must be opened or answered. */
    data class Installing(val release: Release, val awaitingUser: Boolean = false) : UpdateState
    data class Failed(val message: String, val release: Release?) : UpdateState
}

/** Checks this repository's GitHub releases and hands a newer APK to the system installer. */
class Updater(private val app: App) {
    val state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    /** Outlives any one screen so leaving Settings does not cancel a download. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val autoCheck get() = app.settings.getBoolean(AUTO_KEY, true)
    fun setAutoCheck(enabled: Boolean) = app.settings.edit().putBoolean(AUTO_KEY, enabled).apply()
    fun checkNow() { scope.launch { check(manual = true) } }
    fun startInstall(release: Release) { scope.launch { install(release) } }

    /** The installer's confirmation screen. A background start of it is dropped silently, so Settings can reopen it. */
    @Volatile var confirmIntent: Intent? = null
    @Volatile private var sessionId: Int? = null

    /** Drops the pending system install so the update can be retried; the release stays on offer. */
    fun cancelInstall() {
        val release = (state.value as? UpdateState.Installing)?.release
        sessionId?.let { id -> runCatching { app.packageManager.packageInstaller.abandonSession(id) } }
        sessionId = null; confirmIntent = null
        state.value = release?.let { UpdateState.Available(it) } ?: UpdateState.Idle
    }
    internal fun installFinished() { sessionId = null; confirmIntent = null }

    /** Runs the once-a-day automatic check when it is enabled and due. Never reports errors. */
    suspend fun checkIfDue() {
        if (UpdatePolicy.shouldCheck(autoCheck, System.currentTimeMillis(), app.settings.getLong(LAST_KEY, 0L))) check(manual = false)
    }

    suspend fun check(manual: Boolean) {
        val busy = state.value
        if (busy is UpdateState.Checking || busy is UpdateState.Downloading || busy is UpdateState.Installing) return
        val previous = state.value
        state.value = UpdateState.Checking
        state.value = try {
            val body = withContext(Dispatchers.IO) { fetchLatest() }
            val release = body?.let(UpdatePolicy::latestRelease)
            val newer = release != null && UpdatePolicy.isNewer(BuildConfig.VERSION_NAME, release.version)
            // A found update lives only in memory, so it is not stamped: the next launch looks again and offers it again.
            if (!newer) app.settings.edit().putLong(LAST_KEY, System.currentTimeMillis()).apply()
            when {
                release != null && newer -> UpdateState.Available(release)
                body == null -> UpdateState.UpToDate("아직 게시된 릴리즈가 없습니다")
                release == null -> UpdateState.UpToDate("설치할 수 있는 릴리즈가 없습니다")
                else -> UpdateState.UpToDate("최신 버전입니다 · ${BuildConfig.VERSION_NAME}")
            }
        } catch (e: CancellationException) { state.value = previous; throw e }
        catch (e: Exception) {
            // An automatic check stays silent and keeps whatever the user was already shown.
            if (manual) UpdateState.Failed(e.message?.takeIf { it.any { c -> c in '가'..'힣' } } ?: "업데이트를 확인할 수 없습니다. 네트워크 연결을 확인하세요", null)
            else previous.takeUnless { it is UpdateState.Checking } ?: UpdateState.Idle
        }
    }

    /** Downloads the release APK, checks it against the release and the installed app, then starts the system install. */
    suspend fun install(release: Release) {
        if (state.value is UpdateState.Downloading || state.value is UpdateState.Installing) return
        state.value = UpdateState.Downloading(release, 0L)
        try {
            val file = withContext(Dispatchers.IO) { download(release).also { verifyPackage(it, release) } }
            state.value = UpdateState.Installing(release)
            // The session keeps its own copy, so the cached download (up to 200 MB) goes either way.
            withContext(Dispatchers.IO) { try { commit(file) } finally { file.delete() } }
        } catch (e: CancellationException) { state.value = UpdateState.Available(release); throw e }
        catch (e: Exception) {
            state.value = UpdateState.Failed(e.message?.takeIf { it.any { c -> c in '가'..'힣' } } ?: "업데이트를 내려받을 수 없습니다. 네트워크 연결을 확인하세요", release)
        }
    }

    /** Null means the repository has no published release yet (HTTP 404). */
    private fun fetchLatest(): String? {
        val connection = open(UpdatePolicy.LATEST_URL)
        try {
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_NOT_FOUND -> return null
                HttpURLConnection.HTTP_FORBIDDEN, 429 -> error("GitHub 요청 한도를 넘었습니다. 잠시 후 다시 확인하세요")
                else -> error("GitHub 응답 오류 ($code)")
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(8192); val out = StringBuilder()
                while (true) {
                    val n = reader.read(buffer); if (n < 0) break
                    out.append(buffer, 0, n)
                    if (out.length > UpdatePolicy.MAX_RESPONSE_CHARS) error("GitHub 응답이 너무 큽니다")
                }
                out.toString()
            }
        } finally { connection.disconnect() }
    }

    private suspend fun download(release: Release): File {
        val directory = File(app.cacheDir, "updates").apply { mkdirs() }
        directory.listFiles().orEmpty().forEach { it.delete() }
        val file = File(directory, "update.apk")
        val connection = open(release.apkUrl)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) error("릴리즈 파일을 내려받을 수 없습니다 (${connection.responseCode})")
            // GitHub redirects downloads to its own storage host; whatever host answers must still be HTTPS.
            if (connection.url.protocol != "https") error("안전하지 않은 내려받기 주소입니다")
            val digest = MessageDigest.getInstance("SHA-256"); var done = 0L
            connection.inputStream.use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    done += n
                    if (done > release.apkSize) error("릴리즈 파일 크기가 맞지 않습니다")
                    output.write(buffer, 0, n); digest.update(buffer, 0, n)
                    state.value = UpdateState.Downloading(release, done)
                }
                output.fd.sync()
            } }
            if (done != release.apkSize) error("릴리즈 파일 크기가 맞지 않습니다")
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (release.sha256 != null && hash != release.sha256) error("릴리즈 파일 무결성 검사가 실패했습니다")
            return file
        } catch (t: Throwable) { file.delete(); throw t }
        finally { connection.disconnect() }
    }

    /** Stops a mis-tagged release from prompting forever; the system still enforces the signing key at install. */
    private fun verifyPackage(file: File, release: Release) {
        val archive = app.packageManager.getPackageArchiveInfo(file.path, 0) ?: error("릴리즈 파일이 올바른 APK가 아닙니다")
        if (archive.packageName != app.packageName) error("다른 앱의 APK라 설치할 수 없습니다")
        // If the APK's own version lagged its tag, the same release would be offered again after every install.
        if (UpdatePolicy.parseVersion(archive.versionName.orEmpty())?.joinToString(".") != release.version)
            error("릴리즈 태그와 APK 버전이 다릅니다")
        val installed = PackageInfoCompat.getLongVersionCode(app.packageManager.getPackageInfo(app.packageName, 0))
        if (PackageInfoCompat.getLongVersionCode(archive) <= installed) error("릴리즈 APK가 설치된 버전보다 새롭지 않습니다")
    }

    private fun commit(file: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName); setSize(file.length())
        }
        val id = installer.createSession(params)
        sessionId = id; confirmIntent = null
        try {
            installer.openSession(id).use { session ->
                session.openWrite("update.apk", 0, file.length()).use { output ->
                    file.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                // The system fills in the result extras, so the intent must be mutable and, for that reason, explicit.
                val intent = Intent(app, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION)
                val pending = PendingIntent.getBroadcast(app, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pending.intentSender)
            }
        } catch (t: Throwable) { runCatching { installer.abandonSession(id) }; sessionId = null; throw t }
    }

    private fun open(url: String): HttpsURLConnection = (URL(url).openConnection() as HttpsURLConnection).apply {
        connectTimeout = 10_000; readTimeout = 20_000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "beta7compact-updater")
    }

    companion object {
        const val AUTO_KEY = "autoUpdateCheck"
        const val LAST_KEY = "lastUpdateCheck"
    }
}

/** Receives the installer's result. Confirmation needs an activity start, which is allowed while the app is in front. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val updater = (context.applicationContext as App).updater
        val current = updater.state.value
        val release = (current as? UpdateState.Installing)?.release ?: (current as? UpdateState.Failed)?.release
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) updater.installFinished()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null || release == null) { updater.cancelInstall(); return }
                // Kept so Settings can reopen it: started from the background, the system drops it without an error.
                updater.confirmIntent = confirm
                updater.state.value = UpdateState.Installing(release, awaitingUser = true)
                runCatching { context.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> updater.state.value = UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                updater.state.value = release?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                updater.state.value = UpdateState.Failed("설치된 앱과 서명 또는 버전이 맞지 않아 업데이트할 수 없습니다", release)
            PackageInstaller.STATUS_FAILURE_STORAGE ->
                updater.state.value = UpdateState.Failed("저장 공간이 부족해 업데이트할 수 없습니다", release)
            else -> updater.state.value = UpdateState.Failed("업데이트 설치에 실패했습니다", release)
        }
    }
    companion object { const val ACTION = "dev.sukbeomh.beta7compact.update.INSTALL_RESULT" }
}
