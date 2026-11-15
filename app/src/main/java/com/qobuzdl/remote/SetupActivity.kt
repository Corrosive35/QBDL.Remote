package com.qobuzdl.remote

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qobuzdl.remote.databinding.ActivitySetupBinding
import kotlinx.coroutines.launch

/**
 * Saisie et validation de l'adresse du serveur. Rien n'est enregistré tant que /healthz n'a pas
 * confirmé qu'il s'agit bien d'une instance QobuzDLX : une faute de frappe se solde par un
 * message, pas par une application qui démarre sur une page blanche.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var prefs: ServerPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = ServerPrefs(this)

        // targetSdk 35 impose le bord-à-bord sur Android 15 : sans ces marges le titre passerait
        // sous la barre d'état et le bouton sous la barre de navigation.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.serverUrl.setText(prefs.baseUrl.orEmpty())
        binding.connect.setOnClickListener { connect() }
        // La touche « OK » du clavier vaut le bouton : sans cela elle se contente de fermer le
        // clavier, ce qui laisse croire que la saisie n'a pas été prise en compte.
        binding.serverUrl.setOnEditorActionListener { _, _, _ -> connect(); true }
    }

    private fun connect() {
        val candidates = ServerUrl.candidates(binding.serverUrl.text.toString())
        if (candidates.isEmpty()) {
            showError(getString(R.string.setup_error_invalid))
            return
        }

        setBusy(true)
        lifecycleScope.launch {
            val found = ServerUrl.resolve(candidates)

            setBusy(false)
            if (found == null) {
                // Lister les adresses réellement tentées est la seule façon de rendre l'échec
                // exploitable : c'est ce qui distingue « mon serveur est éteint » de « mon
                // serveur n'écoute pas sur le port que l'application a supposé ».
                showError(
                    getString(
                        R.string.setup_error_unreachable,
                        candidates.joinToString("\n") { "• $it" }
                    )
                )
            } else {
                accept(found)
            }
        }
    }

    /**
     * Dernier filet avant d'enregistrer : en HTTP vers un hôte qui n'est ni sur le LAN ni dans un
     * tunnel, le mot de passe d'accès partirait en clair sur le chemin. On le dit explicitement
     * plutôt que de refuser — l'utilisateur peut avoir une raison, mais il doit la connaître.
     */
    private fun accept(result: ProbeResult) {
        if (result.baseUrl.startsWith("http://") && !ServerUrl.isPrivate(result.baseUrl)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setup_cleartext_title)
                .setMessage(getString(R.string.setup_cleartext_message, result.baseUrl))
                .setPositiveButton(R.string.setup_cleartext_continue) { _, _ -> save(result) }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        } else {
            save(result)
        }
    }

    private fun save(result: ProbeResult) {
        prefs.baseUrl = result.baseUrl
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.connect.isEnabled = !busy
        binding.serverUrl.isEnabled = !busy
        if (busy) binding.status.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.status.text = message
        binding.status.visibility = View.VISIBLE
    }
}
