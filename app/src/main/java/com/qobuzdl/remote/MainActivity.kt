package com.qobuzdl.remote

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.qobuzdl.remote.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Hôte du WebView. L'interface est celle de l'application web ; cette activité ne fait que lui
 * offrir un cadre qui survit aux rotations, aux passages en arrière-plan et aux coupures réseau.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /**
         * Action du raccourci du lanceur. Elle passe par cette activité, déjà exportée pour le
         * lanceur, ce qui laisse SetupActivity fermée à l'extérieur. Ce qu'une application tierce
         * gagnerait à l'émettre se limite à l'ouverture d'un formulaire vide : rien n'est
         * enregistré sans une saisie de l'utilisateur validée par /healthz.
         */
        const val ACTION_CHANGE_SERVER = "com.qobuzdl.remote.CHANGE_SERVER"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: ServerPrefs
    private var baseUrl: String = ""
    private var showingError = false
    private var updateCheckStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        prefs = ServerPrefs(this)
        val configured = prefs.baseUrl
        if (configured == null || intent.action == ACTION_CHANGE_SERVER) {
            openSetup()
            return
        }
        baseUrl = configured

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyInsets()
        configureWebView()
        configureBackNavigation()

        binding.errorRetry.setOnClickListener { load() }
        binding.errorChangeServer.setOnClickListener { openSetup() }
        configureUpdateBanner()

        if (savedInstanceState == null) load()
    }

    /** Raccourci « Changer de serveur » reçu alors que l'application tourne déjà (singleTask). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_CHANGE_SERVER) openSetup()
    }

    /**
     * L'activité se termine : le WebView pointe sur l'ancien serveur, et son historique n'a plus
     * de sens si l'adresse change. SetupActivity relancera une instance neuve.
     */
    private fun openSetup() {
        startActivity(Intent(this, SetupActivity::class.java))
        finish()
    }

    /**
     * Le WebView est encastré entre les barres système plutôt que dessous : la racine prend les
     * marges en padding, ce qui protège aussi le bandeau, le panneau d'erreur et les WebView
     * anciens, qui ignorent ces marges.
     */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            // Le clavier remplace la barre de navigation, il ne s'y ajoute pas.
            view.updatePadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            insets
        }
        // Les WebView récents passent ces marges à env(safe-area-inset-*), que la page ajoute
        // déjà : reçues en plus du padding de la racine, elles seraient comptées deux fois.
        ViewCompat.setOnApplyWindowInsetsListener(binding.webView) { _, _ ->
            WindowInsetsCompat.CONSUMED
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val web = binding.webView
        // Sans cela, éclair blanc avant le premier rendu, sur une interface entièrement sombre.
        web.setBackgroundColor(ContextCompat.getColor(this, R.color.t2_nuit))

        web.settings.apply {
            javaScriptEnabled = true
            // sessionStorage : le garde-fou anti-rafale du script de reprise s'en sert.
            domStorageEnabled = true
            userAgentString = "$userAgentString QobuzDLApp/1.0"
        }

        // Interface déjà sombre : l'assombrissement algorithmique ternirait le blanc et le verre.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(web.settings, false)
        }

        CookieManager.getInstance().setAcceptCookie(true)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        web.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                // Tout ce qui sort du serveur configuré part au navigateur : l'application n'a
                // aucune raison d'afficher une autre origine, et le cookie d'accès ne doit pas
                // être exposé à une page tierce. La comparaison porte sur l'origine complète et
                // non sur un préfixe de chaîne, qu'un hôte comme « serveur.evil.com » tromperait.
                val target = request.url
                val base = Uri.parse(baseUrl)
                if (target.scheme == base.scheme &&
                    target.host == base.host &&
                    target.port == base.port
                ) return false

                runCatching { startActivity(Intent(Intent.ACTION_VIEW, target)) }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                // Le cookie d'accès est persistant côté serveur, encore faut-il que le WebView
                // l'écrive sur disque avant qu'Android ne tue le processus.
                CookieManager.getInstance().flush()

                // Une recherche par lancement au plus, et seulement une fois la page affichée.
                if (!showingError && !updateCheckStarted) {
                    updateCheckStarted = true
                    lifecycleScope.launch { AppUpdater.checkIfDue(applicationContext, prefs) }
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                // Une image manquante ne doit pas masquer une page par ailleurs fonctionnelle.
                if (request.isForMainFrame) showError()
            }

            /**
             * Jamais proceed(). Le mot de passe transite sur cette connexion : un certificat qui
             * ne valide pas, c'est soit une interception, soit une configuration à corriger côté
             * serveur — dans les deux cas on s'arrête.
             */
            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError
            ) {
                handler.cancel()
                showError()
            }
        }
    }

    private fun configureBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val web = binding.webView
                // La condition sur l'index évite de remonter jusqu'à la page blanche initiale,
                // que canGoBack() considère pourtant comme une entrée d'historique.
                if (!showingError && web.canGoBack() && web.copyBackForwardList().currentIndex > 0) {
                    web.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    private fun load() {
        showingError = false
        binding.errorPanel.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
        binding.webView.loadUrl(baseUrl)
    }

    private fun showError() {
        showingError = true
        binding.errorMessage.text = getString(R.string.error_message, baseUrl)
        binding.errorPanel.visibility = View.VISIBLE
        binding.webView.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        // La Page Visibility API n'est pas fiable dans un WebView mis en pause selon la version
        // de Chromium embarquée : on déclenche la reprise depuis le cycle de vie Android.
        if (!showingError) {
            binding.webView.evaluateJavascript(
                "window.qbdlxResume && window.qbdlxResume();", null
            )
        }

        // Retour des réglages avec l'autorisation accordée : la mise à jour reprend d'elle-même.
        val update = AppUpdater.state.value
        if (update is UpdateState.NeedsPermission && packageManager.canRequestPackageInstalls()) {
            startUpdate(update.release)
        }
    }

    private fun configureUpdateBanner() {
        binding.updateLater.setOnClickListener { AppUpdater.dismiss() }
        binding.updateAction.setOnClickListener {
            when (val update = AppUpdater.state.value) {
                is UpdateState.Available -> startUpdate(update.release)
                is UpdateState.Failed -> startUpdate(update.release)
                is UpdateState.NeedsPermission -> openInstallPermission()
                else -> Unit
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppUpdater.state.collect { renderUpdate(it) }
            }
        }
    }

    private fun startUpdate(release: Release) {
        // Vérifiée avant de télécharger : sans elle, Android bloquerait l'installation à la fin.
        if (!packageManager.canRequestPackageInstalls()) {
            AppUpdater.needsPermission(release)
            return
        }
        lifecycleScope.launch { AppUpdater.downloadAndInstall(applicationContext, release) }
    }

    private fun openInstallPermission() {
        val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
        // Repli sur la liste générale si ce téléphone n'a pas de page propre à chaque application.
        runCatching { startActivity(Intent(settings).setData(Uri.parse("package:$packageName"))) }
            .onFailure { runCatching { startActivity(settings) } }
    }

    private fun renderUpdate(update: UpdateState) {
        if (update is UpdateState.Hidden) {
            binding.updateBanner.visibility = View.GONE
            return
        }

        binding.updateMessage.text = when (update) {
            is UpdateState.Available -> getString(R.string.update_available, update.release.version)
            is UpdateState.NeedsPermission ->
                getString(R.string.update_permission_message, getString(R.string.app_name))
            is UpdateState.Downloading -> getString(R.string.update_downloading, update.percent)
            is UpdateState.Confirming ->
                getString(R.string.update_installing, update.release.version)
            is UpdateState.Installing ->
                getString(R.string.update_installing, update.release.version)
            is UpdateState.Failed -> getString(update.message)
            UpdateState.Hidden -> ""
        }
        binding.updateAction.setText(
            when (update) {
                is UpdateState.NeedsPermission -> R.string.update_permission_open
                is UpdateState.Failed -> R.string.update_retry
                else -> R.string.update_install
            }
        )
        val actionable = update is UpdateState.Available ||
            update is UpdateState.NeedsPermission ||
            update is UpdateState.Failed
        binding.updateButtons.visibility = if (actionable) View.VISIBLE else View.GONE
        binding.updateProgress.visibility =
            if (update is UpdateState.Downloading) View.VISIBLE else View.GONE
        if (update is UpdateState.Downloading) {
            binding.updateProgress.setProgressCompat(update.percent, true)
        }
        binding.updateBanner.visibility = View.VISIBLE

        // Lancée d'ici, activité au premier plan : le récepteur de statut, en arrière-plan,
        // n'aurait pas toujours le droit d'ouvrir l'écran de confirmation d'Android.
        if (update is UpdateState.Confirming) {
            if (runCatching { startActivity(update.intent) }.isSuccess) {
                AppUpdater.confirmationShown(update.release)
            } else {
                AppUpdater.confirmationFailed(update.release)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Dernière occasion d'écrire les cookies : après onStop, Android peut tuer le processus
        // sans autre préavis.
        CookieManager.getInstance().flush()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        if (binding.webView.restoreState(savedInstanceState) == null) load()
    }

    override fun onDestroy() {
        // Redirection vers SetupActivity : onCreate a fini() avant d'inflater quoi que ce soit.
        if (::binding.isInitialized) {
            // Le WebView est détaché avant destruction : laissé dans la hiérarchie, il fuite
            // l'activité par son contexte.
            binding.root.removeView(binding.webView)
            binding.webView.destroy()
        }
        super.onDestroy()
    }
}
