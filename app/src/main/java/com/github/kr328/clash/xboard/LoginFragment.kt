package com.github.kr328.clash.xboard

import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.github.kr328.clash.R
import com.github.kr328.clash.databinding.FragmentXboardLoginBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class LoginFragment : Fragment() {
    private var _binding: FragmentXboardLoginBinding? = null
    private val binding get() = _binding!!

    private val sessionStore by lazy { XboardSessionStore(requireContext().applicationContext) }
    private val repository by lazy { XboardRepository(sessionStore) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentXboardLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.etEmail.setText(sessionStore.getSession()?.email.orEmpty())

        binding.btnLogin.setOnClickListener { login() }
        binding.btnForgotPassword.setOnClickListener { hostActivity()?.showResetPassword() }
        binding.btnGoRegister.setOnClickListener { hostActivity()?.showRegister() }
    }

    private fun login() {
        val email = binding.etEmail.text?.toString()?.trim().orEmpty()
        val password = binding.etPassword.text?.toString().orEmpty()

        if (!validate(email, password)) {
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            setLoading(true)
            try {
                repository.login(email, password)
                hostActivity()?.showMessage(getString(R.string.xboard_login_success))
                hostActivity()?.showSubscription()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hostActivity()?.showError(e.message ?: getString(R.string.xboard_login_failed))
            } finally {
                setLoading(false)
            }
        }
    }

    private fun validate(email: String, password: String): Boolean {
        binding.inputEmail.error = null
        binding.inputPassword.error = null

        when {
            email.isBlank() || password.isBlank() -> {
                hostActivity()?.showError(getString(R.string.xboard_need_email_password))
                return false
            }
            !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> {
                binding.inputEmail.error = getString(R.string.xboard_invalid_email)
                return false
            }
            password.length < 8 -> {
                binding.inputPassword.error = getString(R.string.xboard_invalid_password)
                return false
            }
        }

        return true
    }

    private fun setLoading(loading: Boolean) {
        hostActivity()?.setLoading(loading)
        _binding?.apply {
            btnLogin.isEnabled = !loading
            btnForgotPassword.isEnabled = !loading
            btnGoRegister.isEnabled = !loading
        }
    }

    private fun hostActivity(): XboardActivity? = activity as? XboardActivity

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
