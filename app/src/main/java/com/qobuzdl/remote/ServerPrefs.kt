package com.qobuzdl.remote

import android.content.Context

/**
 * Adresse du serveur et date de la dernière recherche de mise à jour. Le mot de passe, saisi dans
 * la page web, est échangé contre un cookie que le WebView conserve de son côté ; l'application ne
 * le voit jamais passer.
 */
class ServerPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("qbdlx", Context.MODE_PRIVATE)

    var baseUrl: String?
        get() = prefs.getString(KEY_BASE_URL, null)
        set(value) = prefs.edit().putString(KEY_BASE_URL, value).apply()

    /** Millisecondes depuis l'époque Unix de la dernière réponse de GitHub (AppUpdater). */
    var updateCheckedAt: Long
        get() = prefs.getLong(KEY_UPDATE_CHECKED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_UPDATE_CHECKED_AT, value).apply()

    private companion object {
        const val KEY_BASE_URL = "baseUrl"
        const val KEY_UPDATE_CHECKED_AT = "updateCheckedAt"
    }
}
