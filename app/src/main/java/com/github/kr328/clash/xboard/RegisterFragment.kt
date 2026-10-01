package com.github.kr328.clash.xboard

import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.github.kr328.clash.R
import com.github.kr328.clash.databinding.FragmentXboardRegisterBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class RegisterFragment : Fragment() {
    private var _binding: FragmentXboardRegisterBinding? = null
    private val binding get() = _binding!!

    private val sessionStore by lazy { XboardSessionStore(requireContext().applicationContext) }
    private val repository by lazy { XboardRepository(sessionStore) }
    private var emailVerifyRequired = false
    private var inviteCodeRequired = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentXboardRegisterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.etEmail.setText(sessionStore.getSession()?.email.orEmpty())
        applyGuestConfig(emailVerify = false, inviteCode = false)

        binding.btnRegister.setOnClickListener { register() }
        binding.btnSendEmailCode.setOnClickListener { sendEmailCode() }
        binding.btnGoLogin.setOnClickListener { hostActivity()?.showLogin() }

        viewLifecycleOwner.lifecycleScope.launch {
            refreshGuestConfig()
        }
    }

    private fun register() {
        val email = binding.etEmail.text?.toString()?.trim().orEmpty()
        val password = binding.etPassword.text?.toString().orEmpty()
        val inviteCode = binding.etInviteCode.text?.toString()?.trim().orEmpty()
        val emailCode = binding.etEmailCode.text?.toString()?.trim().orEmpty()

        if (!validateAuth(email, password)) {
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            setLoading(true)
            try {
                refreshGuestConfig()
                if (!validateDynamic(inviteCode, emailCode)) {
                    return@launch
                }

                repository.register(
                    email = email,
                    password = password,
                    inviteCode = inviteCode.takeIf { inviteCodeRequired },
                    emailCode = emailCode.takeIf { emailVerifyRequired },
                )
                hostActivity()?.showMessage(getString(R.string.xboard_register_success))
                hostActivity()?.showSubscription()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hostActivity()?.showError(e.message ?: getString(R.string.xboard_register_failed))
            } finally {
                setLoading(false)
            }
        }
    }

    private fun sendEmailCode() {
        val email = binding.etEmail.text?.toString()?.trim().orEmpty()

        if (!validateEmail(email)) {
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            setLoading(true)
            try {
                refreshGuestConfig()
                if (!emailVerifyRequired) {
                    hostActivity()?.showMessage(getString(R.string.xboard_email_verify_not_required))
                    return@launch
                }

                repository.sendEmailVerify(email)
                hostActivity()?.showMessage(getString(R.string.xboard_email_code_sent))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hostActivity()?.showError(e.message ?: getString(R.string.xboard_email_code_send_failed))
            } finally {
                setLoading(false)
            }
        }
    }

    private fun validateAuth(email: String, password: String): Boolean {
        binding.inputEmail.error = null
        binding.inputPassword.error = null
        binding.inputInviteCode.error = null
        binding.inputEmailCode.error = null

        when {
            email.isBlank() || password.isBlank() -> {
                hostActivity()?.showError(getString(R.string.xboard_need_email_password))
                return false
            }
            !validateEmail(email) -> return false
            password.length < 8 -> {
                binding.inputPassword.error = getString(R.string.xboard_invalid_password)
                return false
            }
        }

        return true
    }

    private fun validateEmail(email: String): Boolean {
        binding.inputEmail.error = null

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.inputEmail.error = getString(R.string.xboard_invalid_email)
            return false
        }

        return true
    }

    private fun validateDynamic(inviteCode: String, emailCode: String): Boolean {
        binding.inputInviteCode.error = null
        binding.inputEmailCode.error = null

        if (inviteCodeRequired && inviteCode.isBlank()) {
            binding.inputInviteCode.error = getString(R.string.xboard_invite_code_required)
            return false
        }

        if (emailVerifyRequired && emailCode.isBlank()) {
            binding.inputEmailCode.error = getString(R.string.xboard_email_code_required)
            return false
        }

        return true
    }

    private suspend fun refreshGuestConfig() {
        runCatching { repository.getGuestConfig() }
            .onSuccess { config ->
                applyGuestConfig(
                    emailVerify = config.requiresEmailVerify(),
                    inviteCode = config.requiresInviteCode(),
                )
            }
    }

    private fun applyGuestConfig(emailVerify: Boolean, inviteCode: Boolean) {
        emailVerifyRequired = emailVerify
        inviteCodeRequired = inviteCode

        binding.inputInviteCode.isVisible = inviteCode
        binding.layoutEmailVerify.isVisible = emailVerify

        if (!inviteCode) {
            binding.etInviteCode.text?.clear()
            binding.inputInviteCode.error = null
        }

        if (!emailVerify) {
            binding.etEmailCode.text?.clear()
            binding.inputEmailCode.error = null
        }
    }

    private fun setLoading(loading: Boolean) {
        hostActivity()?.setLoading(loading)
        _binding?.apply {
            btnRegister.isEnabled = !loading
            btnSendEmailCode.isEnabled = !loading
            btnGoLogin.isEnabled = !loading
        }
    }

    private fun hostActivity(): XboardActivity? = activity as? XboardActivity

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
