package com.github.kr328.clash.xboard

import android.net.Uri
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.remote.IProfileManager
import com.github.kr328.clash.service.util.sendProfileChanged
import com.github.kr328.clash.util.withProfile
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.io.File

class XboardSubscriptionBridge(
    private val sessionStore: XboardSessionStore,
) {
    suspend fun saveAndActivateSubscription(
        subscribeUrl: String,
        remark: String,
        updateIntervalMinutes: Long,
    ): XboardImportResult {
        val trimmedUrl = subscribeUrl.trim()
        require(isValidHttpUrl(trimmedUrl)) { "Invalid subscription url" }

        val profileName = remark.ifBlank { "XBoard" }
        val intervalMinutes = updateIntervalMinutes.coerceAtLeast(15L)
        val intervalMs = TimeUnit.MINUTES.toMillis(intervalMinutes)

        val uuid = withProfile {
            val existingUuid = findExistingProfileUuid(trimmedUrl)
            val targetUuid = existingUuid ?: create(Profile.Type.Url, profileName)
            val ageSecretKey = queryByUUID(targetUuid)?.ageSecretKey

            patch(targetUuid, profileName, trimmedUrl, intervalMs, ageSecretKey)
            commit(targetUuid, null)

            val imported = requireNotNull(queryByUUID(targetUuid)) {
                "Imported profile not found"
            }

            setActive(imported)
            targetUuid
        }

        sessionStore.updateProfileUuid(uuid.toString())
        sessionStore.setUpdateIntervalMinutes(intervalMinutes)

        return XboardImportResult(
            success = true,
            profileUuid = uuid.toString(),
        )
    }

    /** Repairs profiles imported by older builds without forcing a download. */
    fun ensurePanelReachability() {
        val uuid = sessionStore.getSession()?.profileUuid
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return

        val changed = ensureDirectRule(
            sessionStore.appContext.filesDir
                .resolve("imported")
                .resolve(uuid.toString())
                .resolve("config.yaml")
        )
        if (changed) {
            sessionStore.appContext.sendProfileChanged(uuid)
        }
    }

    private fun ensureDirectRule(configFile: File): Boolean {
        if (!configFile.isFile) {
            return false
        }

        val source = configFile.readText()
        val directRule = "DOMAIN,$XBOARD_PANEL_HOST,DIRECT"
        val newline = if (source.contains("\r\n")) "\r\n" else "\n"
        val lines = source.split('\n').toMutableList()
        val rulesLine = lines.indexOfFirst { line ->
            line.trimStart(' ', '\t').startsWith("rules:")
        }

        if (rulesLine < 0) {
            val updated = source.trimEnd() + newline + "rules:" + newline + "  - $directRule" + newline
            configFile.writeText(updated)
            return true
        }

        val originalLine = lines[rulesLine]
        val keyIndent = originalLine.takeWhile { it == ' ' || it == '\t' }
        val keyIndentLength = keyIndent.length
        val lineWithoutCr = originalLine.trimEnd('\r')
        val openBracket = lineWithoutCr.indexOf('[')
        val closeBracket = lineWithoutCr.lastIndexOf(']')

        // Flow-style rules (rules: [...]) must keep each comma-containing
        // rule quoted. Appending a second rules key would create duplicate
        // YAML keys and can turn the entire list into one scalar.
        if (openBracket >= 0 && closeBracket > openBracket) {
            val body = lineWithoutCr.substring(openBracket + 1, closeBracket)
            if (!body.contains(directRule, ignoreCase = true)) {
                val cr = if (originalLine.endsWith('\r')) "\r" else ""
                val separator = if (body.trim().isEmpty()) "" else ", "
                lines[rulesLine] =
                    lineWithoutCr.substring(0, closeBracket) +
                        separator + "'$directRule'" +
                        lineWithoutCr.substring(closeBracket) + cr
                configFile.writeText(lines.joinToString("\n"))
                return true
            }
            return false
        }

        // Find the end of the rules block and the indentation used by its
        // sequence items. Older builds inserted the first item at the same
        // indentation as `rules:`; when existing items used four spaces,
        // YAML interpreted the whole list as one string joined by " - ".
        var blockEnd = rulesLine + 1
        val listLineIndexes = mutableListOf<Int>()
        while (blockEnd < lines.size) {
            val raw = lines[blockEnd].trimEnd('\r')
            if (raw.isBlank()) {
                blockEnd++
                continue
            }
            val indentLength = raw.takeWhile { it == ' ' || it == '\t' }.length
            val content = raw.trimStart(' ', '\t')
            if (indentLength <= keyIndentLength && !content.startsWith("-")) {
                break
            }
            if (content.startsWith("-")) {
                listLineIndexes += blockEnd
            }
            blockEnd++
        }

        val directRuleLineIndexes = listLineIndexes.filter { index ->
            ruleLineValue(lines[index]).equals(directRule, ignoreCase = true)
        }
        val existingItemIndent = listLineIndexes
            .firstOrNull { it !in directRuleLineIndexes }
            ?.let { lines[it].takeWhile { char -> char == ' ' || char == '\t' } }
            ?: listLineIndexes.firstOrNull()
                ?.let { lines[it].takeWhile { char -> char == ' ' || char == '\t' } }
            ?: "$keyIndent  "

        var changed = false
        directRuleLineIndexes.forEach { index ->
            val cr = if (lines[index].endsWith('\r')) "\r" else ""
            val normalized = "$existingItemIndent- $directRule$cr"
            if (lines[index] != normalized) {
                lines[index] = normalized
                changed = true
            }
        }

        if (directRuleLineIndexes.isEmpty()) {
            lines.add(rulesLine + 1, "$existingItemIndent- $directRule")
            changed = true
        }

        if (!changed) {
            return false
        }

        configFile.writeText(lines.joinToString("\n"))
        return true
    }

    private fun ruleLineValue(line: String): String {
        return line.trimEnd('\r')
            .trim()
            .removePrefix("-")
            .trim()
            .trim('"', '\'')
    }

    private suspend fun IProfileManager.findExistingProfileUuid(subscribeUrl: String): UUID? {
        val saved = sessionStore.getSession()?.profileUuid
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

        if (saved != null && queryByUUID(saved) != null) {
            return saved
        }

        return queryAll().firstOrNull {
            it.imported &&
                it.type == Profile.Type.Url &&
                it.source.trim() == subscribeUrl
        }?.uuid
    }

    private fun isValidHttpUrl(value: String): Boolean {
        val scheme = Uri.parse(value).scheme?.lowercase(Locale.getDefault())
        return scheme == "http" || scheme == "https"
    }

    companion object {
        private const val XBOARD_PANEL_HOST = "xb.linvk.com"
    }
}
