package com.qobuzdl.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

/** URL retenue et version renvoyée par la sonde. */
data class ProbeResult(val baseUrl: String, val version: String)

/**
 * Résolution de l'adresse du serveur saisie par l'utilisateur.
 *
 * L'application ne charge jamais qu'une seule origine, celle que l'utilisateur a validée ici.
 * C'est ce qui permet de garder [isPrivate] comme unique garde-fou contre l'envoi du mot de
 * passe en clair, plutôt qu'une liste blanche statique dans network_security_config.xml — qui
 * ne saurait de toute façon pas exprimer un plan d'adressage inconnu à la compilation.
 */
object ServerUrl {

    private const val TAG = "QobuzDLX"
    private const val TIMEOUT_MS = 4_000
    private const val EXPECTED_APP = "QobuzDLX.Web"
    private const val FALLBACK_PORT = 8080

    /** La sonde renvoie deux lignes de JSON ; au-delà on cesse de lire. */
    private const val MAX_BODY_CHARS = 4_096

    private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    private data class Parts(val scheme: String?, val host: String, val port: Int, val path: String)

    /**
     * Adresses à sonder, par ordre de préférence. Sans schéma explicite on tente HTTPS avant
     * HTTP ; sans port explicite on retente sur 8080, le port exposé par défaut par l'image
     * Docker — un port d'hôte différent dans le `docker run` ne peut pas être deviné et doit
     * être saisi.
     */
    fun candidates(input: String): List<String> {
        val parts = parse(input) ?: return emptyList()
        val schemes = parts.scheme?.let { listOf(it) } ?: listOf("https", "http")
        val ports = if (parts.port > 0) listOf(parts.port) else listOf(-1, FALLBACK_PORT)

        return ports
            .flatMap { port -> schemes.map { scheme -> build(scheme, parts.host, port, parts.path) } }
            .distinct()
    }

    /**
     * Sonde tous les candidats de front et retient le premier de la liste qui répond.
     *
     * En cascade, une adresse dont les paquets sont filtrés plutôt que refusés immobilise la
     * suivante pendant tout le délai d'attente ; quatre candidats font quinze secondes d'attente
     * muette avant que le bon — le dernier — soit seulement tenté. De front, l'attente totale
     * plafonne à un seul délai, et l'ordre de préférence est préservé puisque la liste des
     * résultats est relue dans son ordre d'origine.
     */
    suspend fun resolve(urls: List<String>): ProbeResult? = coroutineScope {
        urls.map { url -> async(Dispatchers.IO) { probe(url) } }
            .awaitAll()
            .firstNotNullOfOrNull { it }
    }

    /**
     * GET /healthz. Renvoie null pour tout ce qui n'est pas une instance QobuzDLX joignable :
     * l'appelant n'a pas à distinguer un DNS qui ne résout pas d'un serveur qui répond autre
     * chose. La raison exacte part dans logcat sous l'étiquette « QobuzDLX » — c'est le seul
     * moyen de départager une mauvaise adresse d'un serveur trop ancien pour exposer /healthz.
     *
     * Bloquant : n'est appelé que depuis [resolve], sur Dispatchers.IO.
     */
    private fun probe(baseUrl: String): ProbeResult? {
        val connection = try {
            URL("$baseUrl/healthz").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            Log.d(TAG, "$baseUrl : adresse inutilisable (${e.javaClass.simpleName})")
            return null
        }

        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")

            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                Log.d(TAG, "$baseUrl : HTTP $status")
                return null
            }

            val json = JSONObject(connection.inputStream.bufferedReader().use(::readCapped))
            val app = json.optString("app")
            if (app != EXPECTED_APP) {
                Log.d(TAG, "$baseUrl : répond mais annonce « $app »")
                return null
            }

            Log.d(TAG, "$baseUrl : instance trouvée")
            ProbeResult(baseUrl, json.optString("version", "?"))
        } catch (e: Exception) {
            Log.d(TAG, "$baseUrl : ${e.javaClass.simpleName} — ${e.message}")
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Vrai si l'hôte est manifestement sur un réseau privé — donc si parler en clair ne fait
     * sortir le mot de passe ni du domicile ni du tunnel VPN.
     *
     * Volontairement sans résolution DNS : la méthode est appelée depuis le thread UI, et un nom
     * qui résout vers une adresse privée reste joignable par n'importe qui sur le chemin.
     * Un doute se solde par un faux, donc par le dialogue d'avertissement.
     */
    fun isPrivate(baseUrl: String): Boolean {
        val host = parse(baseUrl)?.host?.trim('[', ']')?.lowercase(Locale.ROOT) ?: return false

        if (host == "localhost" ||
            host.endsWith(".local") ||      // mDNS / Bonjour
            host.endsWith(".lan") ||
            host.endsWith(".home.arpa") ||
            host.endsWith(".internal") ||
            host.endsWith(".ts.net")        // MagicDNS Tailscale
        ) return true

        ipv4(host)?.let { (a, b) ->
            return a == 10 ||                       // RFC 1918
                a == 127 ||                         // bouclage
                (a == 172 && b in 16..31) ||        // RFC 1918
                (a == 192 && b == 168) ||           // RFC 1918
                (a == 169 && b == 254) ||           // lien-local
                (a == 100 && b in 64..127)          // CGNAT — plage utilisée par Tailscale
        }

        // Sans deux-points ce n'est pas un littéral IPv6 mais un nom d'hôte quelconque : sans ce
        // garde, « fcbayern.example.com » passerait pour une adresse locale unique.
        if (!host.contains(':')) return false

        return host == "::1" ||
            host.startsWith("fd") || host.startsWith("fc") ||   // ULA
            host.startsWith("fe80:")                            // lien-local
    }

    // ─── Interne ───

    /**
     * Une saisie sans schéma n'est pas une URI valide : « 192.168.1.42:8080 » se lirait comme le
     * schéma « 192.168.1.42 ». Le préfixe « // » force l'analyse en composante d'autorité.
     */
    private fun parse(input: String): Parts? {
        val raw = input.trim()
        if (raw.isEmpty()) return null

        val hasScheme = raw.contains("://")
        val uri = try {
            URI(if (hasScheme) raw else "//$raw")
        } catch (_: Exception) {
            return null
        }

        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (hasScheme && scheme != "http" && scheme != "https") return null

        // Le slash final est retiré : les URL sont concaténées avec « /healthz ».
        return Parts(if (hasScheme) scheme else null, host, uri.port, uri.path.orEmpty().trimEnd('/'))
    }

    private fun build(scheme: String, host: String, port: Int, path: String) =
        if (port > 0) "$scheme://$host:$port$path" else "$scheme://$host$path"

    private fun ipv4(host: String): Pair<Int, Int>? {
        val match = IPV4.matchEntire(host) ?: return null
        val octets = (1..4).map { match.groupValues[it].toIntOrNull() ?: return null }
        if (octets.any { it > 255 }) return null
        return octets[0] to octets[1]
    }

    private fun readCapped(reader: Reader): String {
        val out = StringBuilder()
        val buffer = CharArray(1_024)
        while (out.length < MAX_BODY_CHARS) {
            val read = reader.read(buffer)
            if (read < 0) break
            out.append(buffer, 0, read)
        }
        return out.toString()
    }
}
