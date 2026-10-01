package com.github.kr328.clash.xboard

import android.os.Bundle
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.github.kr328.clash.R
import com.github.kr328.clash.databinding.ActivityXboardBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class XboardActivity : AppCompatActivity() {
    private lateinit var binding: ActivityXboardBinding

    private val sessionStore by lazy { XboardSessionStore(applicationContext) }
    private val onboardingMode by lazy { intent.getBooleanExtra(EXTRA_ONBOARDING_MODE, false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityXboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(!onboardingMode)
        supportActionBar?.setDisplayShowHomeEnabled(!onboardingMode)
        supportActionBar?.title = getString(R.string.xboard_title)

        applyInsets()

        if (savedInstanceState == null) {
            if (sessionStore.getSession() == null) {
                showLogin()
            } else {
                showSubscription()
            }
        }

        if (intent.getBooleanExtra(EXTRA_SHOW_TICKETS, false)) {
            binding.root.post { showTickets(ticketIdFromIntent(intent)) }
        }

        // The subscription reminder is owned by the application-level
        // monitor, so opening XBoard while another activity is already in
        // the foreground cannot bypass the global reminder.
        binding.root.post { XboardSubscriptionMonitor.showIfExpired(this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        if (intent.getBooleanExtra(EXTRA_SHOW_RENEWAL_PROMPT, false)) {
            binding.root.post { XboardSubscriptionMonitor.showIfExpired(this) }
        }
        if (intent.getBooleanExtra(EXTRA_SHOW_TICKETS, false)) {
            binding.root.post { showTickets(ticketIdFromIntent(intent)) }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    fun isOnboardingMode(): Boolean = onboardingMode

    fun completeOnboarding() {
        setResult(RESULT_OK)
        finish()
    }

    fun showLogin() {
        replace(LoginFragment())
    }

    fun showRegister() {
        replace(RegisterFragment())
    }

    fun showResetPassword() {
        replace(ResetPasswordFragment())
    }

    fun showSubscription() {
        replace(SubscriptionFragment())
    }

    fun showTickets(ticketId: Int? = null) {
        val fragment = TicketFragment()
        if (ticketId != null && ticketId > 0) {
            fragment.arguments = Bundle().apply {
                putInt(TicketFragment.ARG_TICKET_ID, ticketId)
            }
        }
        replace(fragment)
    }

    private fun ticketIdFromIntent(intent: Intent): Int? {
        return intent.getIntExtra(EXTRA_TICKET_ID, 0).takeIf { it > 0 }
    }

    fun setLoading(loading: Boolean) {
        binding.loadingIndicator.isVisible = loading
    }

    fun showMessage(message: CharSequence) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    fun showError(message: CharSequence) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.xboard_error_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun replace(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.xboard_container, fragment)
            .commit()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            binding.appBarContainer.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
            )
            binding.xboardContainer.updatePadding(
                left = bars.left,
                right = bars.right,
                bottom = bars.bottom,
            )

            insets
        }
    }

    companion object {
        const val EXTRA_ONBOARDING_MODE = "extra_xboard_onboarding_mode"
        const val EXTRA_SHOW_RENEWAL_PROMPT = "extra_xboard_show_renewal_prompt"
        const val EXTRA_SHOW_TICKETS = "extra_xboard_show_tickets"
        const val EXTRA_TICKET_ID = "extra_xboard_ticket_id"
    }
}
