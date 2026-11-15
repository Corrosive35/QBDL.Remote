package com.qobuzdl.remote

import android.content.Context

/**
 * Adresse du serveur : la seule donnée que l'application persiste elle-même.
 *
 * Le mot de passe d'accès n'apparaît pas ici. Il est saisi dans la page web, échangé contre un
 * cookie que le WebView stocke de son côté, et l'application ne le voit jamais passer.
 */
class ServerPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("qbdlx", Context.MODE_PRIVATE)

    var baseUrl: String?
        get() = prefs.getString(KEY_BASE_URL, null)
        set(value) = prefs.edit().putString(KEY_BASE_URL, value).apply()

    private companion object {
        const val KEY_BASE_URL = "baseUrl"
    }
}
