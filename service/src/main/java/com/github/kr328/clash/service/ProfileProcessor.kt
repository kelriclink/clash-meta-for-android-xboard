package com.github.kr328.clash.service

import android.content.Context
import android.net.Uri
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.FetchStatus
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.Pending
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.remote.IFetchObserver
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import com.github.kr328.clash.service.util.processingDir
import com.github.kr328.clash.service.util.sendProfileChanged
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit

object ProfileProcessor {
    private val profileLock = Mutex()
    private val processLock = Mutex()

    suspend fun apply(context: Context, uuid: UUID, callback: IFetchObserver? = null) {
        withContext(NonCancellable) {
            processLock.withLock {
                val snapshot = profileLock.withLock {
                    val pending =
                        PendingDao().queryByUUID(uuid) ?: throw IllegalArgumentException("profile $uuid not found")

                    pending.enforceFieldValid()

                    context.processingDir.deleteRecursively()
                    context.processingDir.mkdirs()

                    context.pendingDir.resolve(pending.uuid.toString())
                        .copyRecursively(context.processingDir, overwrite = true)

                    pending
                }

                Clash.setAgeSecretKey(snapshot.ageSecretKey?.takeIf { it.isNotBlank() })

                val force = snapshot.type != Profile.Type.File
                val subscriptionInfo = fetchProfile(context, snapshot.source, force, callback)
                if (isXboardProfile(snapshot.name, snapshot.source)) {
                    ensureXboardDirectRule(context.processingDir)
                }

                profileLock.withLock {
                    if (PendingDao().queryByUUID(snapshot.uuid) == snapshot) {
                        context.importedDir.resolve(snapshot.uuid.toString()).deleteRecursively()
                        context.processingDir.copyRecursively(context.importedDir.resolve(snapshot.uuid.toString()))

                        val old = ImportedDao().queryByUUID(snapshot.uuid)
                        val updateInterval = subscriptionInfo?.subUpdateInterval
                            ?.takeIf { old == null && snapshot.interval == 0L }
                            ?: snapshot.interval
                        val new = Imported(
                            snapshot.uuid,
                            snapshot.name,
                            snapshot.type,
                            snapshot.source,
                            updateInterval,
                            subscriptionInfo?.subUpload ?: 0,
                            subscriptionInfo?.subDownload ?: 0,
                            subscriptionInfo?.subTotal ?: 0,
                            subscriptionInfo?.subExpire ?: 0,
                            old?.createdAt ?: System.currentTimeMillis(),
                            ageSecretKey = snapshot.ageSecretKey
                        )
                        if (old != null) {
                            ImportedDao().update(new)
                        } else {
                            ImportedDao().insert(new)
                        }

                        PendingDao().remove(snapshot.uuid)

                        context.pendingDir.resolve(snapshot.uuid.toString()).deleteRecursively()

                        context.sendProfileChanged(snapshot.uuid)
                    }
                }
            }
        }
    }

    suspend fun update(context: Context, uuid: UUID, callback: IFetchObserver?) {
        withContext(NonCancellable) {
            processLock.withLock {
                val snapshot = profileLock.withLock {
                    val imported =
                        ImportedDao().queryByUUID(uuid) ?: throw IllegalArgumentException("profile $uuid not found")

                    context.processingDir.deleteRecursively()
                    context.processingDir.mkdirs()

                    context.importedDir.resolve(imported.uuid.toString())
                        .copyRecursively(context.processingDir, overwrite = true)

                    imported
                }

                Clash.setAgeSecretKey(snapshot.ageSecretKey?.takeIf { it.isNotBlank() })

                val subscriptionInfo = fetchProfile(context, snapshot.source, true, callback)
                if (isXboardProfile(snapshot.name, snapshot.source)) {
                    ensureXboardDirectRule(context.processingDir)
                }

                profileLock.withLock {
                    val imported = ImportedDao().queryByUUID(snapshot.uuid)
                    if (imported != null) {
                        context.importedDir.resolve(snapshot.uuid.toString()).deleteRecursively()
                        context.processingDir.copyRecursively(context.importedDir.resolve(snapshot.uuid.toString()))

                        val upload = subscriptionInfo?.subUpload
                        if (upload != null) {
                            ImportedDao().update(
                                imported.copy(
                                    upload = upload,
                                    download = subscriptionInfo.subDownload ?: 0,
                                    total = subscriptionInfo.subTotal ?: 0,
                                    expire = subscriptionInfo.subExpire ?: 0,
                                )
                            )
                        }

                        context.sendProfileChanged(snapshot.uuid)
                    }
                }
            }
        }
    }

    private suspend fun fetchProfile(
        context: Context,
        source: String,
        force: Boolean,
        callback: IFetchObserver?,
    ): FetchStatus? {
        var subscriptionInfo: FetchStatus? = null
        var cb = callback

        Clash.fetchAndValid(context.processingDir, source, force) {
            if (it.action == FetchStatus.Action.SubscriptionInfo) {
                subscriptionInfo = it
                return@fetchAndValid
            }

            try {
                cb?.updateStatus(it)
            } catch (e: Exception) {
                cb = null

                Log.w("Report fetch status: $e", e)
            }
        }.await()

        return subscriptionInfo
    }

    /**
     * Keep the XBoard panel reachable when the imported subscription's proxy
     * nodes have expired. The rule is intentionally limited to XBoard profiles
     * and is re-applied after every subscription download.
     */
    private fun ensureXboardDirectRule(path: File) {
        val configFile = path.resolve("config.yaml")
        if (!configFile.isFile) {
            return
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
            return
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
            }
            return
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

        if (changed) {
            configFile.writeText(lines.joinToString("\n"))
        }
    }

    private fun ruleLineValue(line: String): String {
        return line.trimEnd('\r')
            .trim()
            .removePrefix("-")
            .trim()
            .trim('"', '\'')
    }

    private fun isXboardProfile(name: String, source: String): Boolean {
        if (name.contains("xboard", ignoreCase = true)) {
            return true
        }

        return Uri.parse(source).host?.equals(XBOARD_PANEL_HOST, ignoreCase = true) == true
    }

    suspend fun delete(context: Context, uuid: UUID) {
        withContext(NonCancellable) {
            profileLock.withLock {
                ImportedDao().remove(uuid)
                PendingDao().remove(uuid)

                val pending = context.pendingDir.resolve(uuid.toString())
                val imported = context.importedDir.resolve(uuid.toString())

                pending.deleteRecursively()
                imported.deleteRecursively()

                context.sendProfileChanged(uuid)
            }
        }
    }

    suspend fun release(context: Context, uuid: UUID): Boolean {
        return withContext(NonCancellable) {
            profileLock.withLock {
                PendingDao().remove(uuid)

                context.pendingDir.resolve(uuid.toString()).deleteRecursively()
            }
        }
    }

    suspend fun active(context: Context, uuid: UUID) {
        withContext(NonCancellable) {
            profileLock.withLock {
                if (ImportedDao().exists(uuid)) {
                    val store = ServiceStore(context)

                    store.activeProfile = uuid

                    context.sendProfileChanged(uuid)
                }
            }
        }
    }

    private fun Pending.enforceFieldValid() {
        val scheme = Uri.parse(source)?.scheme?.lowercase(Locale.getDefault())

        when {
            name.isBlank() -> throw IllegalArgumentException("Empty name")

            source.isEmpty() && type != Profile.Type.File -> throw IllegalArgumentException("Invalid url")

            source.isNotEmpty() && scheme != "https" && scheme != "http" && scheme != "content" -> throw IllegalArgumentException(
                "Unsupported url $source"
            )

            interval != 0L && TimeUnit.MILLISECONDS.toMinutes(interval) < 15 -> throw IllegalArgumentException("Invalid interval")
        }
    }

    private const val XBOARD_PANEL_HOST = "xb.linvk.com"

}
