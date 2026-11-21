package com.qobuzdl.remote

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class Release(val version: String, val apkUrl: String, val size: Long, val sha256: String?)

sealed interface UpdateState {
    data object Hidden : UpdateState
    data class Available(val release: Release) : UpdateState
    data class NeedsPermission(val release: Release) : UpdateState
    data class Downloading(val release: Release, val percent: Int) : UpdateState
    data class Confirming(val release: Release, val intent: Intent) : UpdateState
    data class Installing(val release: Release) : UpdateState
    data class Failed(val release: Release, @StringRes val message: Int) : UpdateState
}

/**
 * Mise à jour intégrée depuis la dernière Release GitHub. Le tag « v<versionName> » et le nom
 * d'asset « QBDL.Remote-v<versionName>.apk » produits par release.yml en sont le contrat.
 */
object AppUpdater {

    private const val TAG = "QobuzDL"
    private const val LATEST_URL =
        "https://api.github.com/repos/Corrosive35/QBDL.Remote/releases/latest"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    private const val TIMEOUT_MS = 10_000
    private const val UPDATES_DIR = "updates"
    private val USER_AGENT = "QBDL.Remote/${BuildConfig.VERSION_NAME}"

    // À l'échelle du processus : UpdateStatusReceiver y dépose le statut de l'installation.
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Hidden)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun dismiss() {
        _state.value = UpdateState.Hidden
    }

    fun needsPermission(release: Release) {
        _state.value = UpdateState.NeedsPermission(release)
    }

    fun confirmationShown(release: Release) {
        _state.value = UpdateState.Installing(release)
    }

    fun confirmationFailed(release: Release) {
        _state.value = UpdateState.Failed(release, R.string.update_failed_install)
    }

    /**
     * Au plus une recherche toutes les 24 h, comptées depuis la dernière réponse de GitHub : une
     * coupure réseau ne fait pas perdre la journée. Tout échec reste silencieux, sauf dans logcat.
     */
    suspend fun checkIfDue(context: Context, prefs: ServerPrefs) {
        if (_state.value != UpdateState.Hidden) return
        val elapsed = System.currentTimeMillis() - prefs.updateCheckedAt
        // Une horloge remise en arrière donne un écart négatif : on vérifie plutôt que d'attendre.
        val due = elapsed !in 0L until CHECK_INTERVAL_MS

        val release = withContext(Dispatchers.IO) {
            // APK d'une mise à jour déjà installée ou abandonnée : plus rien ne s'en sert.
            File(context.cacheDir, UPDATES_DIR).deleteRecursively()
            if (due) fetchLatest(prefs) else null
        } ?: return

        if (_state.value == UpdateState.Hidden) _state.value = UpdateState.Available(release)
    }

    suspend fun downloadAndInstall(context: Context, release: Release) {
        _state.value = UpdateState.Downloading(release, 0)
        try {
            val apk = download(context, release)
            _state.value = UpdateState.Installing(release)
            withContext(Dispatchers.IO) { install(context, apk) }
        } catch (e: CancellationException) {
            // Activité fermée en plein téléchargement : la proposition reste valable.
            _state.value = UpdateState.Available(release)
            throw e
        } catch (e: IntegrityException) {
            Log.d(TAG, "Mise à jour : fichier rejeté — ${e.message}")
            _state.value = UpdateState.Failed(release, R.string.update_failed_integrity)
        } catch (e: DownloadException) {
            Log.d(TAG, "Mise à jour : téléchargement interrompu — ${e.message}")
            _state.value = UpdateState.Failed(release, R.string.update_failed_download)
        } catch (e: Exception) {
            Log.d(TAG, "Mise à jour : session refusée — ${e.javaClass.simpleName} ${e.message}")
            _state.value = UpdateState.Failed(release, R.string.update_failed_install)
        }
    }

    internal fun onInstallStatus(intent: Intent) {
        val status =
            intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        Log.d(TAG, "Mise à jour : statut d'installation $status $detail")

        val release = when (val current = _state.value) {
            is UpdateState.Installing -> current.release
            is UpdateState.Confirming -> current.release
            else -> return
        }
        _state.value = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?.let { UpdateState.Confirming(release, it) }
                    ?: UpdateState.Failed(release, R.string.update_failed_install)
            // Rarement vu : la mise à jour arrête le processus avant que le statut n'arrive.
            PackageInstaller.STATUS_SUCCESS -> UpdateState.Hidden
            // Confirmation refusée par l'utilisateur : la proposition reste à l'écran.
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateState.Available(release)
            // Dont une APK signée par une autre clé : Android la refuse, et c'est voulu.
            else -> UpdateState.Failed(release, R.string.update_failed_install)
        }
    }

    /** Comparaison numérique, composante par composante (1.10 > 1.9) ; null si illisible. */
    internal fun compareVersions(a: String, b: String): Int? {
        val left = a.split('.').map { it.toIntOrNull() ?: return null }
        val right = b.split('.').map { it.toIntOrNull() ?: return null }
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    private fun fetchLatest(prefs: ServerPrefs): Release? {
        val connection = try {
            URL(LATEST_URL).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            Log.d(TAG, "Mise à jour : ${e.javaClass.simpleName}")
            return null
        }

        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", USER_AGENT)

            val status = connection.responseCode
            prefs.updateCheckedAt = System.currentTimeMillis()
            if (status != HttpURLConnection.HTTP_OK) {
                Log.d(TAG, "Mise à jour : GitHub répond HTTP $status")
                return null
            }
            parseRelease(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
        } catch (e: Exception) {
            Log.d(TAG, "Mise à jour : ${e.javaClass.simpleName} — ${e.message}")
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRelease(json: JSONObject): Release? {
        val tag = json.optString("tag_name")
        val version = tag.removePrefix("v")
        val order = compareVersions(version, BuildConfig.VERSION_NAME)
        if (order == null || order <= 0) {
            Log.d(TAG, "Mise à jour : « $tag » pas plus récente que ${BuildConfig.VERSION_NAME}")
            return null
        }

        val expected = "QBDL.Remote-$tag.apk"
        val assets = json.optJSONArray("assets")
        val asset = (0 until (assets?.length() ?: 0))
            .mapNotNull { assets?.optJSONObject(it) }
            .firstOrNull { it.optString("name") == expected }
        if (asset == null) {
            Log.d(TAG, "Mise à jour : $tag n'a pas de fichier $expected")
            return null
        }

        val url = asset.optString("browser_download_url")
        val size = asset.optLong("size", -1L)
        if (!url.startsWith("https://") || size <= 0) {
            Log.d(TAG, "Mise à jour : $expected inutilisable (adresse ou taille)")
            return null
        }
        // Champ récent de l'API GitHub : en son absence, seule la taille est contrôlée.
        val sha256 = asset.optString("digest")
            .takeIf { it.startsWith("sha256:") }
            ?.removePrefix("sha256:")
            ?.lowercase()
        return Release(version, url, size, sha256)
    }

    private suspend fun download(context: Context, release: Release): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, UPDATES_DIR).apply { deleteRecursively(); mkdirs() }
            val apk = File(dir, "QBDL.Remote-v${release.version}.apk")
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L

            try {
                val connection = URL(release.apkUrl).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = TIMEOUT_MS
                    connection.readTimeout = TIMEOUT_MS
                    connection.setRequestProperty("User-Agent", USER_AGENT)

                    val status = connection.responseCode
                    // GitHub redirige vers son stockage : l'adresse finale doit rester en HTTPS.
                    if (connection.url.protocol != "https") {
                        throw DownloadException("redirigé hors HTTPS")
                    }
                    if (status != HttpURLConnection.HTTP_OK) throw DownloadException("HTTP $status")

                    connection.inputStream.use { input ->
                        apk.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var percent = 0
                            while (true) {
                                ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                received += read
                                if (received > release.size) {
                                    throw IntegrityException("plus de ${release.size} octets")
                                }
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                val now = (received * 100 / release.size).toInt()
                                if (now != percent) {
                                    percent = now
                                    _state.value = UpdateState.Downloading(release, now)
                                }
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: IOException) {
                throw DownloadException("${e.javaClass.simpleName} — ${e.message}")
            }

            if (received != release.size) {
                throw IntegrityException("$received octets reçus sur ${release.size}")
            }
            release.sha256?.let { expected ->
                val actual = digest.digest().joinToString("") {
                    (it.toInt() and 0xff).toString(16).padStart(2, '0')
                }
                if (actual != expected) {
                    throw IntegrityException("SHA-256 $actual, attendu $expected")
                }
            }
            apk
        }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        params.setAppPackageName(context.packageName)
        params.setSize(apk.length())
        // Android 12+ : la confirmation peut disparaître quand l'application se met à jour
        // elle-même (voir UPDATE_PACKAGES_WITHOUT_USER_ACTION dans le manifeste).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                // Le système complète l'intention avec le statut : elle doit rester modifiable.
                val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
                val status = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(context, UpdateStatusReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or mutable
                )
                session.commit(status.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    private class DownloadException(message: String) : Exception(message)

    private class IntegrityException(message: String) : Exception(message)
}

/** Non exporté (manifeste) : seul PackageInstaller l'atteint, par notre PendingIntent. */
class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = AppUpdater.onInstallStatus(intent)
}
