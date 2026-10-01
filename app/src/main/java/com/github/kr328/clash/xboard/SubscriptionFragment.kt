package com.github.kr328.clash.xboard

import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.github.kr328.clash.R
import com.github.kr328.clash.databinding.FragmentXboardSubscriptionBinding
import com.github.kr328.clash.store.AppStore
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class SubscriptionFragment : Fragment() {
    private var _binding: FragmentXboardSubscriptionBinding? = null
    private val binding get() = _binding!!

    private val sessionStore by lazy { XboardSessionStore(requireContext().applicationContext) }
    private val repository by lazy { XboardRepository(sessionStore) }
    private val bridge by lazy { XboardSubscriptionBridge(sessionStore) }
    private val appStore by lazy { AppStore(requireContext().applicationContext) }

    private var subscribeInfo: XboardSubscribeInfo? = null
    private var updateIntervalMinutes: Long = XboardSessionStore.DEFAULT_UPDATE_INTERVAL_MINUTES

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentXboardSubscriptionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        updateIntervalMinutes = sessionStore.getUpdateIntervalMinutes()
        renderUpdateInterval()

        binding.btnRefresh.setOnClickListener { loadAccount() }
        binding.btnCheckUpdate.setOnClickListener { checkAppUpdate() }
        binding.btnTickets.setOnClickListener { hostActivity()?.showTickets() }
        binding.btnOpenWeb.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                openPanelPage("/dashboard")
            }
        }
        binding.btnLogout.setOnClickListener {
            repository.logout()
            hostActivity()?.showLogin()
        }
        binding.btnShowPlans.setOnClickListener { loadPlans(scrollToPlans = true) }
        binding.btnChangeSubscription.setOnClickListener { loadPlans(scrollToPlans = true) }
        binding.btnUpdateInterval.setOnClickListener { promptUpdateInterval() }
        binding.btnImportSubscription.setOnClickListener { importSubscription() }

        renderLoadingState()
        loadAccount()
    }

    private fun checkAppUpdate() {
        viewLifecycleOwner.lifecycleScope.launch {
            setLoading(true)
            try {
                when (XboardAppUpdateMonitor.checkNow(requireContext())) {
                    XboardAppUpdateMonitor.CheckResult.UPDATE_AVAILABLE -> Unit
                    XboardAppUpdateMonitor.CheckResult.UP_TO_DATE -> {
                        hostActivity()?.showMessage(getString(R.string.xboard_update_latest))
                    }
                    XboardAppUpdateMonitor.CheckResult.UNAVAILABLE -> {
                        hostActivity()?.showMessage(getString(R.string.xboard_update_unavailable))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hostActivity()?.showError(e.message ?: getString(R.string.xboard_error_title))
            } finally {
                setLoading(false)
            }
        }
    }

    private fun loadAccount() {
        viewLifecycleOwner.lifecycleScope.launch {
            setLoading(true)
            try {
                // Repair profiles created by older versions before opening
                // external panel links. This is local-only and does not force
                // a subscription download.
                runCatching {
                    withContext(Dispatchers.IO) {
                        bridge.ensurePanelReachability()
                    }
                }
                val user = repository.getUserInfo()
                val active = user.hasActiveSubscription()
                // Accounts without a plan have never subscribed; only cache
                // an expired state for users who previously had a plan.
                appStore.xboardSubscriptionExpired =
                    user.planId?.let { it > 0 && !active } == true
                subscribeInfo = if (active) repository.getSubscribe() else null
                renderAccount(user, subscribeInfo)
                if (active) {
                    binding.layoutPlans.removeAllViews()
                } else {
                    renderPlans(repository.fetchPlans().filter { it.sell })
                }

                // Let the application-level monitor present the reminder on
                // this foreground activity when this refresh discovers a new
                // expiration. The dialog itself is never owned by this page.
                if (appStore.xboardSubscriptionExpired) {
                    hostActivity()?.let { XboardSubscriptionMonitor.showIfExpired(it) }
                }
                hostActivity()?.let { XboardAnnouncementMonitor.onApplicationVisible(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                subscribeInfo = null
                _binding?.let { binding ->
                    binding.layoutSubscription.visibility = View.GONE
                    binding.layoutNoSubscription.visibility = View.GONE
                }
                hostActivity()?.showError(e.message ?: getString(R.string.xboard_account_load_failed))
            } finally {
                setLoading(false)
            }
        }
    }

    private fun renderLoadingState() {
        binding.tvEmail.text = sessionStore.getSession()?.email.orEmpty()
        binding.tvStatus.text = ""
        binding.layoutSubscription.visibility = View.GONE
        binding.layoutNoSubscription.visibility = View.GONE
    }

    private fun renderAccount(user: XboardUserInfo, subscribe: XboardSubscribeInfo?) {
        binding.tvEmail.text = user.email ?: sessionStore.getSession()?.email.orEmpty()
        if (subscribe?.subscribeUrl.isNullOrBlank()) {
            binding.tvStatus.text = getString(R.string.xboard_status_no_subscription)
            binding.layoutSubscription.visibility = View.GONE
            binding.layoutNoSubscription.visibility = View.VISIBLE
            return
        }

        binding.tvStatus.text = getString(R.string.xboard_status_has_subscription)
        binding.layoutSubscription.visibility = View.VISIBLE
        binding.layoutNoSubscription.visibility = View.GONE
        binding.tvPlanName.text = subscribe.plan?.name ?: getString(R.string.xboard_current_subscription)
        binding.tvUsage.text = formatUsage(subscribe)
        binding.tvSubscriptionUrl.text = subscribe.subscribeUrl.orEmpty()
        binding.btnImportSubscription.text = if (hostActivity()?.isOnboardingMode() == true) {
            getString(R.string.xboard_import_and_finish)
        } else {
            getString(R.string.xboard_import_subscription)
        }
    }

    private fun renderUpdateInterval() {
        binding.tvUpdateInterval.text = getString(
            R.string.xboard_auto_update_interval_value,
            updateIntervalMinutes
        )
    }

    private fun promptUpdateInterval() {
        val inputLayout = TextInputLayout(requireContext()).apply {
            hint = getString(R.string.xboard_auto_update_interval)
        }
        val input = TextInputEditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(updateIntervalMinutes.toString())
            setSelection(text?.length ?: 0)
        }
        inputLayout.addView(input)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.xboard_auto_update_interval)
            .setMessage(R.string.xboard_auto_update_hint)
            .setView(inputLayout)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
                val minutes = input.text?.toString()?.trim()?.toLongOrNull()
                if (minutes == null || minutes < 15L) {
                    inputLayout.error = getString(R.string.xboard_update_interval_invalid)
                    return@setOnClickListener
                }

                inputLayout.error = null
                updateIntervalMinutes = minutes
                sessionStore.setUpdateIntervalMinutes(minutes)
                renderUpdateInterval()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun importSubscription() {
        val info = subscribeInfo
        val url = info?.subscribeUrl.orEmpty()
        if (url.isBlank()) {
            hostActivity()?.showError(getString(R.string.xboard_no_subscription_url))
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            setLoading(true)
            try {
                withContext(Dispatchers.IO) {
                    bridge.saveAndActivateSubscription(
                        subscribeUrl = url,
                        remark = "XBoard - ${info?.plan?.name ?: info?.email ?: "Subscription"}",
                        updateIntervalMinutes = updateIntervalMinutes,
                    )
                }

                if (hostActivity()?.isOnboardingMode() == true) {
                    hostActivity()?.completeOnboarding()
                } else {
                    hostActivity()?.showMessage(getString(R.string.xboard_import_success))
                    loadAccount()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hostActivity()?.showError(e.message ?: getString(R.string.xboard_import_failed))
            } finally {
                setLoading(false)
            }
        }
    }

    private fun loadPlans(scrollToPlans: Boolean = false) {
        viewLifecycleOwner.lifecycleScope.launch {
            setLoading(true)
            try {
                val plans = repository.fetchPlans().filter { it.sell }
                renderPlans(plans)
                if (scrollToPlans) {
                    scrollToPlanList()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hostActivity()?.showError(e.message ?: getString(R.string.xboard_plan_load_failed))
            } finally {
                setLoading(false)
            }
        }
    }

    private fun renderPlans(plans: List<XboardPlan>) {
        binding.layoutPlans.removeAllViews()
        if (plans.isEmpty()) {
            binding.layoutPlans.addView(simpleText(getString(R.string.xboard_no_plans)))
            return
        }

        binding.layoutPlans.addView(simpleText(getString(R.string.xboard_plans_title)).apply {
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
        })
        plans.forEach { plan ->
            binding.layoutPlans.addView(planCard(plan))
        }
    }

    private fun scrollToPlanList() {
        binding.layoutPlans.post {
            binding.scrollRoot.smoothScrollTo(0, binding.layoutPlans.top)
        }
    }

    private fun planCard(plan: XboardPlan): View {
        val context = requireContext()
        val card = MaterialCardView(context).apply {
            radius = dp(16).toFloat()
            strokeWidth = dp(1)
            setStrokeColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            setContentPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(12)
            }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        content.addView(simpleText(plan.name.orEmpty()).apply {
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
        })
        if (!plan.content.isNullOrBlank()) {
            content.addView(simpleText(HtmlCompat.fromHtml(XboardMarkdown.toHtml(plan.content), HtmlCompat.FROM_HTML_MODE_COMPACT)).apply {
                setPadding(0, dp(8), 0, 0)
                movementMethod = LinkMovementMethod.getInstance()
                linksClickable = true
            })
        }
        content.addView(simpleText(formatPlanMeta(plan)).apply {
            setPadding(0, dp(8), 0, 0)
        })

        val periods = plan.availablePeriods()
        if (periods.isEmpty()) {
            content.addView(simpleText(getString(R.string.xboard_plan_no_period)).apply {
                setPadding(0, dp(8), 0, 0)
            })
        } else {
            periods.forEach { period ->
                content.addView(periodButton(plan, period))
            }
        }

        card.addView(content)
        return card
    }

    private fun periodButton(plan: XboardPlan, period: XboardPlanPeriod): View {
        return MaterialButton(requireContext()).apply {
            text = getString(R.string.xboard_buy_period, period.label, formatPrice(period.price))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(10)
            }
            setOnClickListener { createOrder(plan, period) }
        }
    }

    private fun createOrder(plan: XboardPlan, period: XboardPlanPeriod) {
        viewLifecycleOwner.lifecycleScope.launch {
            setLoading(true)
            try {
                val tradeNo = repository.createOrder(plan.id, period.key)
                val checkedOut = checkoutFreeOrder(tradeNo)
                if (checkedOut) {
                    hostActivity()?.showMessage(getString(R.string.xboard_order_completed))
                    loadAccount()
                    return@launch
                }

                openPanelPage("/order/$tradeNo")
                hostActivity()?.showMessage(getString(R.string.xboard_order_created))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isUnfinishedOrderError(e.message)) {
                    val opened = try {
                        openUnfinishedOrders()
                    } catch (_: Exception) {
                        false
                    }
                    if (opened) {
                        return@launch
                    }
                }
                hostActivity()?.showError(e.message ?: getString(R.string.xboard_order_failed))
            } finally {
                setLoading(false)
            }
        }
    }

    private suspend fun openUnfinishedOrders(): Boolean {
        val orders = repository.fetchUnfinishedOrders()
        val latestTradeNo = orders.firstOrNull()?.tradeNo
        if (!latestTradeNo.isNullOrBlank()) {
            openPanelPage("/order/$latestTradeNo")
        } else {
            openPanelPage("/order")
        }
        hostActivity()?.showMessage(getString(R.string.xboard_existing_order_list_opened))
        return true
    }

    private fun isUnfinishedOrderError(message: String?): Boolean {
        val value = message.orEmpty().lowercase(Locale.ROOT)
        return value.contains("unpaid") ||
            value.contains("pending order") ||
            value.contains("未付款") ||
            value.contains("未支付") ||
            value.contains("开通中")
    }

    private suspend fun checkoutFreeOrder(tradeNo: String): Boolean {
        return try {
            repository.checkoutOrder(tradeNo)
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun openPanelPage(path: String) {
        val loginUrl = repository.getQuickLoginUrl(path)
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(loginUrl)))
    }

    private fun formatUsage(info: XboardSubscribeInfo): String {
        val used = (info.u ?: 0L) + (info.d ?: 0L)
        val total = info.transferEnable ?: 0L

        return if (total > 0) {
            getString(
                R.string.xboard_usage,
                Formatter.formatFileSize(requireContext(), used),
                Formatter.formatFileSize(requireContext(), total),
            )
        } else {
            getString(
                R.string.xboard_usage_unlimited,
                Formatter.formatFileSize(requireContext(), used),
            )
        }
    }

    private fun formatPlanMeta(plan: XboardPlan): String {
        val traffic = plan.transferEnable?.takeIf { it > 0 }?.let { formatPlanTraffic(it) }
            ?: getString(R.string.xboard_unlimited)
        val speed = plan.speedLimit?.takeIf { it > 0 }?.let { "${it} Mbps" }
            ?: getString(R.string.xboard_unlimited)
        val devices = plan.deviceLimit?.takeIf { it > 0 }?.toString()
            ?: getString(R.string.xboard_unlimited)

        return getString(R.string.xboard_plan_meta, traffic, speed, devices)
    }

    private fun formatPlanTraffic(value: Long): String {
        return if (value < 1024L * 1024L) {
            "$value GB"
        } else {
            Formatter.formatFileSize(requireContext(), value)
        }
    }

    private fun formatPrice(price: Double?): String {
        val value = (price ?: 0.0) / 100.0
        return String.format(Locale.getDefault(), "%.2f", value)
    }

    private fun simpleText(text: CharSequence): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun setLoading(loading: Boolean) {
        hostActivity()?.setLoading(loading)
        _binding?.apply {
            btnRefresh.isEnabled = !loading
            btnCheckUpdate.isEnabled = !loading
            btnTickets.isEnabled = !loading
            btnOpenWeb.isEnabled = !loading
            btnLogout.isEnabled = !loading
            btnShowPlans.isEnabled = !loading
            btnChangeSubscription.isEnabled = !loading
            btnUpdateInterval.isEnabled = !loading
            btnImportSubscription.isEnabled = !loading
        }
    }

    private fun hostActivity(): XboardActivity? = activity as? XboardActivity

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
