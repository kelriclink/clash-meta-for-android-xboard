package com.github.kr328.clash.xboard

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.text.HtmlCompat
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.github.kr328.clash.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class TicketFragment : Fragment() {
    private lateinit var list: LinearLayout
    private val sessionStore by lazy { XboardSessionStore(requireContext().applicationContext) }
    private val repository by lazy { XboardRepository(sessionStore) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val scroll = NestedScrollView(requireContext()).apply {
            setBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurface))
            isFillViewport = true
        }
        list = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
        }
        scroll.addView(list)
        return scroll
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        loadTickets()
    }

    override fun onResume() {
        super.onResume()
        // The application observer normally performs this check too, but
        // invoking it here covers returning directly to the ticket screen
        // from a system notification or another activity.
        XboardTicketMonitor.onApplicationVisible(requireContext())
    }

    private fun loadTickets() {
        viewLifecycleOwner.lifecycleScope.launch {
            val host = activity as? XboardActivity
            host?.setLoading(true)
            try {
                val session = sessionStore.getSession() ?: return@launch
                val tickets = repository.fetchTickets()
                XboardTicketMonitor.recordFetchedTickets(requireContext(), session, tickets)
                renderTickets(tickets)
                arguments?.getInt(ARG_TICKET_ID, 0)?.takeIf { it > 0 }?.let { ticketId ->
                    showDetailDialog(ticketId)
                    arguments?.remove(ARG_TICKET_ID)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                (activity as? XboardActivity)?.showError(e.message ?: getString(R.string.xboard_error_title))
            } finally {
                host?.setLoading(false)
            }
        }
    }

    private fun renderTickets(tickets: List<XboardTicket>) {
        list.removeAllViews()
        list.addView(TextView(requireContext()).apply {
            text = getString(R.string.xboard_ticket_title)
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        })
        list.addView(TextView(requireContext()).apply {
            text = getString(R.string.xboard_ticket_summary)
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            setPadding(0, dp(4), 0, 0)
        })

        val primaryAction = MaterialButton(requireContext()).apply {
            text = getString(R.string.xboard_ticket_new)
            layoutParams = matchParams(top = dp(18))
            isEnabled = tickets.none(XboardTicket::isOpen)
            setOnClickListener { showCreateDialog() }
        }
        list.addView(primaryAction)

        val actions = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = matchParams(top = dp(2))
        }
        actions.addView(MaterialButton(requireContext()).apply {
            text = getString(R.string.xboard_refresh)
            layoutParams = weightParams()
            setOnClickListener { loadTickets() }
        })
        actions.addView(MaterialButton(requireContext()).apply {
            text = getString(R.string.xboard_open_web)
            layoutParams = weightParams(start = dp(10))
            setOnClickListener { openWebTickets() }
        })
        list.addView(actions)

        if (tickets.isEmpty()) {
            list.addView(MaterialCardView(requireContext()).apply {
                radius = dp(16).toFloat()
                layoutParams = matchParams(top = dp(18))
                setContentPadding(dp(16), dp(18), dp(16), dp(18))
                addView(TextView(context).apply {
                    text = getString(R.string.xboard_ticket_empty)
                    setTextAppearance(android.R.style.TextAppearance_Material_Body1)
                })
            })
            return
        }

        tickets.forEach { ticket ->
            list.addView(MaterialCardView(requireContext()).apply {
                radius = dp(14).toFloat()
                setContentPadding(dp(16), dp(12), dp(16), dp(12))
                layoutParams = matchParams(top = dp(12))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        text = ticket.subject.ifBlank { getString(R.string.xboard_ticket_title) }
                        setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
                        maxLines = 2
                    })
                    addView(TextView(context).apply {
                        text = "${levelLabel(ticket.level)}  ·  ${ticketStatus(ticket)}  ·  ${ticketDate(ticket.updatedAt ?: ticket.createdAt)}"
                        setTextAppearance(android.R.style.TextAppearance_Material_Body2)
                        setPadding(0, dp(7), 0, 0)
                    })
                })
                setOnClickListener { showDetailDialog(ticket.id) }
            })
        }
    }

    private fun showCreateDialog() {
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(4), 0)
        }
        val subject = TextInputEditText(requireContext()).apply {
            setSingleLine(true)
        }
        var selectedLevel = 1
        val level = MaterialAutoCompleteTextView(requireContext()).apply {
            setAdapter(
                ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_dropdown_item_1line,
                    arrayOf(
                        getString(R.string.xboard_ticket_level_low),
                        getString(R.string.xboard_ticket_level_medium),
                        getString(R.string.xboard_ticket_level_high),
                    ),
                )
            )
            setText(getString(R.string.xboard_ticket_level_medium), false)
            setOnItemClickListener { _, _, position, _ -> selectedLevel = position }
            inputType = android.text.InputType.TYPE_NULL
            keyListener = null
        }
        val message = TextInputEditText(requireContext()).apply {
            minLines = 5
            gravity = android.view.Gravity.TOP
        }
        content.addView(TextInputLayout(requireContext()).apply {
            hint = getString(R.string.xboard_ticket_subject)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            addView(subject)
        })
        content.addView(TextInputLayout(requireContext()).apply {
            hint = getString(R.string.xboard_ticket_level)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            layoutParams = matchParams(top = dp(10))
            addView(level)
        })
        content.addView(TextInputLayout(requireContext()).apply {
            hint = getString(R.string.xboard_ticket_message)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            layoutParams = matchParams(top = dp(10))
            addView(message)
        })

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.xboard_ticket_new)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.xboard_ticket_submit, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val subjectText = subject.text?.toString()?.trim().orEmpty()
                val messageText = message.text?.toString()?.trim().orEmpty()
                if (subjectText.isEmpty() || messageText.isEmpty()) {
                    (activity as? XboardActivity)?.showMessage(getString(R.string.xboard_ticket_message_required))
                    return@setOnClickListener
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        repository.createTicket(subjectText, selectedLevel, messageText)
                        dialog.dismiss()
                        loadTickets()
                        (activity as? XboardActivity)?.showMessage(getString(R.string.xboard_ticket_created))
                    } catch (e: Exception) {
                        (activity as? XboardActivity)?.showError(e.message ?: getString(R.string.xboard_error_title))
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showDetailDialog(ticketId: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val ticket = repository.fetchTicket(ticketId)
                sessionStore.getSession()?.let { session ->
                    XboardTicketMonitor.acknowledgeTicket(requireContext(), session, ticket)
                }
                val content = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(4))
                }
                renderMessages(content, ticket.message.orEmpty())
                val reply = TextInputEditText(requireContext()).apply {
                    minLines = 3
                    gravity = android.view.Gravity.TOP
                }
                val dialogContent = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(4), 0, dp(4), 0)
                }
                val messageScroll = ScrollView(requireContext()).apply {
                    // Keep only the conversation in the scrollable area. The
                    // reply field below remains visible for long threads.
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(300),
                    )
                    addView(content)
                }
                dialogContent.addView(messageScroll)
                if (ticket.isOpen()) {
                    dialogContent.addView(TextInputLayout(requireContext()).apply {
                        hint = getString(R.string.xboard_ticket_reply)
                        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                        layoutParams = matchParams(top = dp(12))
                        addView(reply)
                    })
                }
                val builder = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(ticket.subject)
                    .setView(dialogContent)
                    .setPositiveButton(
                        if (ticket.isOpen()) R.string.xboard_ticket_reply else android.R.string.ok,
                        null,
                    )
                if (ticket.isOpen()) {
                    builder.setNegativeButton(R.string.xboard_ticket_close) { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            runCatching { repository.closeTicket(ticket.id) }
                                .onSuccess { loadTickets() }
                                .onFailure { error -> (activity as? XboardActivity)?.showError(error.message ?: getString(R.string.xboard_error_title)) }
                        }
                    }
                }
                val dialog = builder.create()
                dialog.setOnShowListener {
                    if (ticket.isOpen()) {
                        val replyButton = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                        replyButton.setOnClickListener {
                            val value = reply.text?.toString()?.trim().orEmpty()
                            if (value.isEmpty()) {
                                (activity as? XboardActivity)?.showMessage(getString(R.string.xboard_ticket_message_required))
                                return@setOnClickListener
                            }
                            replyButton.isEnabled = false
                            viewLifecycleOwner.lifecycleScope.launch {
                                try {
                                    repository.replyTicket(ticket.id, value)
                                    val updated = repository.fetchTicket(ticket.id)
                                    sessionStore.getSession()?.let { session ->
                                        XboardTicketMonitor.acknowledgeTicket(requireContext(), session, updated)
                                    }
                                    renderMessages(content, updated.message.orEmpty())
                                    reply.text?.clear()
                                    messageScroll.post { messageScroll.fullScroll(View.FOCUS_DOWN) }
                                } catch (e: Exception) {
                                    (activity as? XboardActivity)?.showError(e.message ?: getString(R.string.xboard_error_title))
                                } finally {
                                    replyButton.isEnabled = true
                                }
                            }
                        }
                    }
                }
                dialog.show()
                messageScroll.post { messageScroll.fullScroll(View.FOCUS_DOWN) }
            } catch (e: Exception) {
                (activity as? XboardActivity)?.showError(e.message ?: getString(R.string.xboard_error_title))
            }
        }
    }

    private fun openWebTickets() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { repository.getQuickLoginUrl("/ticket") }
                .onSuccess { url ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
                .onFailure { error ->
                    (activity as? XboardActivity)?.showError(
                        error.message ?: getString(R.string.xboard_error_title),
                    )
                }
        }
    }

    private fun messageView(message: XboardTicketMessage): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (message.isMe == true) android.view.Gravity.END else android.view.Gravity.START
            layoutParams = matchParams(top = dp(8))
        }
        val column = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (message.isMe == true) android.view.Gravity.END else android.view.Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { width = (resources.displayMetrics.widthPixels * 0.78f).toInt() }
        }
        val bubbleText = TextView(requireContext()).apply {
            text = HtmlCompat.fromHtml(
                XboardMarkdown.toHtml(message.message),
                HtmlCompat.FROM_HTML_MODE_COMPACT,
            )
            movementMethod = LinkMovementMethod.getInstance()
            linksClickable = true
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setTextColor(if (message.isMe == true) Color.WHITE else resolveColor(com.google.android.material.R.attr.colorOnSurface))
            setLinkTextColor(if (message.isMe == true) Color.WHITE else resolveColor(com.google.android.material.R.attr.colorPrimary))
        }
        column.addView(MaterialCardView(requireContext()).apply {
            radius = dp(16).toFloat()
            setCardBackgroundColor(
                if (message.isMe == true) {
                    resolveColor(com.google.android.material.R.attr.colorPrimary)
                } else {
                    resolveColor(com.google.android.material.R.attr.colorSurface)
                }
            )
            addView(bubbleText)
        })
        column.addView(TextView(requireContext()).apply {
            text = ticketDate(message.createdAt ?: message.updatedAt)
            textSize = 11f
            setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface))
            alpha = 0.6f
            gravity = if (message.isMe == true) android.view.Gravity.END else android.view.Gravity.START
            setPadding(dp(4), dp(3), dp(4), 0)
        })
        row.addView(column)
        return row
    }

    private fun renderMessages(container: LinearLayout, messages: List<XboardTicketMessage>) {
        container.removeAllViews()
        messages.forEach { message -> container.addView(messageView(message)) }
        if (messages.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                text = getString(R.string.xboard_ticket_empty)
                setPadding(dp(12))
            })
        }
    }

    private fun ticketStatus(ticket: XboardTicket): String {
        if (!ticket.isOpen()) return getString(R.string.xboard_ticket_status_closed)
        return if (ticket.replyStatus == 1) {
            getString(R.string.xboard_ticket_status_replied)
        } else {
            getString(R.string.xboard_ticket_status_waiting)
        }
    }

    private fun levelLabel(level: Int): String {
        return when (level) {
            0 -> getString(R.string.xboard_ticket_level_low)
            2 -> getString(R.string.xboard_ticket_level_high)
            else -> getString(R.string.xboard_ticket_level_medium)
        }
    }

    private fun ticketDate(timestamp: Long?): String {
        if (timestamp == null || timestamp <= 0) return ""
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp * 1000))
    }

    private fun matchParams(top: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = top }
    }

    private fun weightParams(start: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = start
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun resolveColor(attribute: Int): Int {
        val value = TypedValue()
        requireContext().theme.resolveAttribute(attribute, value, true)
        return if (value.resourceId != 0) {
            ContextCompat.getColor(requireContext(), value.resourceId)
        } else {
            value.data
        }
    }

    companion object {
        const val ARG_TICKET_ID = "ticket_id"
    }
}
