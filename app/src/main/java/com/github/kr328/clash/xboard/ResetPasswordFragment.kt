package com.github.kr328.clash.xboard

import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.github.kr328.clash.R
import com.github.kr328.clash.databinding.FragmentXboardResetPasswordBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class ResetPasswordFragment : Fragment() {
    private var _binding: FragmentXboardResetPasswordBinding? = null
    private val binding get() = _binding!!

    private val sessionStore by lazy { XboardSessionStore(requireContext().applicationContext) }
    private val repository by lazy { XboardRepository(sessionStore) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentXboardResetPasswordBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.etEmail.setText(sessionStore.getSession()?.email.orEmpty())

        binding.btnResetPassword.setOnClickListener { resetPassword() }
        binding.btnSendEmailCode.setOnClickListener { sendEmailCode() }
        binding.btnGoLogin.setOnClickListener { hostActivity()?.showLogin() }
    }

    private fun resetPassword() {
        val email = binding.etEmail.text?.toString()?.trim().orEmpty()
        val emailCode = binding.etEmailCode.text?.toString()?.trim().orEmpty()
        val password = binding.etPassword.text?.toString().orEmpty()
        val confirmPassword = binding.etConfirmPassword.text?.toString().orEmpty()

        if (!validate(email, emailCode, password, confirmPassword)) {
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            setLoading(true)
            try {
                repository.resetPassword(email, emailCode, password)
                hostActivity()?.showMessage(getString(R.string.xboard_reset_password_success))
                hostActivity()?.showLogin()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hostActivity()?.showError(e.message ?: getString(R.string.xboard_reset_password_failed))
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

    private fun validate(
        email: String,
        emailCode: String,
        password: String,
        confirmPassword: String,
    ): Boolean {
        binding.inputEmail.error = null
        binding.inputEmailCode.error = null
        binding.inputPassword.error = null
        binding.inputConfirmPassword.error = null

        when {
            email.isBlank() || emailCode.isBlank() || password.isBlank() || confirmPassword.isBlank() -> {
                hostActivity()?.showError(getString(R.string.xboard_need_reset_password_fields))
                return false
            }
            !validateEmail(email) -> return false
            password.length < 8 -> {
                binding.inputPassword.error = getString(R.string.xboard_invalid_password)
                return false
            }
            password != confirmPassword -> {
                binding.inputConfirmPassword.error = getString(R.string.xboard_password_not_match)
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

    private fun setLoading(loading: Boolean) {
        hostActivity()?.setLoading(loading)
        _binding?.apply {
            btnResetPassword.isEnabled = !loading
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
