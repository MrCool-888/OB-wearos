package com.openbubbles.wear.bridge

import android.content.Context
import com.openbubbles.wear.protocol.Protocol
import com.openbubbles.wear.protocol.WireThread

/**
 * Finds chats that OpenBubbles has already published to Android, without
 * reading its private database and without waiting for a notification.
 *
 * Two public system sources are used through Shizuku:
 *  - Android dynamic/cached shortcuts: one per incoming notification plus the
 *    recent named chats OpenBubbles publishes at startup.
 *  - Android notification channels: OpenBubbles creates one channel per chat,
 *    named after the chat, and keeps it after the notification is dismissed.
 *
 * OpenBubbles 2.x names the shortcut chat_<key> and the channel
 * conv_chat_<key>. The key is either the chat GUID, addr:<address> or
 * contact:<Android contact id>. Only addr: keys carry an address, so a chat
 * whose key is a contact id can be listed by title but not matched from the
 * watch address book until it posts a notification.
 *
 * Both sources are partial directories. They never include a chat that
 * OpenBubbles has never shown to Android, and a title is never used to infer
 * that a chat is a group: group status comes only from the chat GUID.
 */
object PublishedChatDiscovery {
    const val CHAT_ID_PREFIX = "chat_"
    const val CONVERSATION_CHANNEL_PREFIX = "conv_"
    // Legacy BlueBubbles/OpenBubbles 1.x channel name.
    const val CHANNEL_PREFIX = "com.bluebubbles.new_messages."
    private const val ADDRESS_KEY_PREFIX = "addr:"
    private const val CONTACT_KEY_PREFIX = "contact:"
    private const val NOTIFY_ANYWAYS_SUFFIX = ".notify_anyways"
    private const val NOTIFY_ANYWAYS_TITLE = "Notify Anyways: "
    const val MAX_CHATS = 160

    const val NOTE = "Chats were read from OpenBubbles' own Android shortcuts and notification channels. No notification is required, but this is a partial directory, not its private database."

    data class Result(
        val chats: List<WireThread> = emptyList(),
        val groupCount: Int = 0,
        val directCount: Int = 0,
        val note: String? = null,
        val error: String? = null
    )

    fun discover(context: Context): Result {
        if (!ShizukuSetup.hasPermission()) {
            return Result(error = "Start Shizuku using wireless debugging and grant the phone bridge access to find chats without notifications.")
        }
        val pkg = BridgeInfo.openBubblesPackage(context)
            ?: return Result(error = "OpenBubbles is not installed.")
        val user = android.os.Process.myUid() / 100_000

        val shortcutResult = runCatching { ShizukuSetup.shell(shortcutCommand(pkg, user)) }
            .getOrElse { ShizukuSetup.ShellResult(false, it.message.orEmpty()) }
        val channelResult = runCatching { ShizukuSetup.shell(channelCommand(pkg)) }
            .getOrElse { ShizukuSetup.ShellResult(false, it.message.orEmpty()) }

        val shortcutOk = usable(shortcutResult)
        val channelOk = usable(channelResult)
        if (!shortcutOk && !channelOk) {
            val detail = (shortcutResult.output.ifBlank { channelResult.output }).take(300)
            return Result(error = "Android did not allow reading OpenBubbles' published chats. $detail")
        }

        val byId = LinkedHashMap<String, WireThread>()
        if (shortcutOk) {
            parseShortcuts(shortcutResult.output, pkg).forEach { add(byId, it) }
        }
        if (channelOk) {
            parseChannels(channelResult.output).forEach { add(byId, it) }
        }

        val chats = byId.values.take(MAX_CHATS)
        val groups = chats.count { it.isGroup }
        val direct = chats.size - groups
        if (chats.isEmpty()) {
            return Result(
                note = "No published chats found. Open OpenBubbles once so it can publish recent chats, then retry. $NOTE"
            )
        }
        val partial = when {
            !shortcutOk -> " Shortcut metadata was unavailable; channels were still read."
            !channelOk -> " Channel metadata was unavailable; shortcuts were still read."
            else -> ""
        }
        return Result(
            chats = chats,
            groupCount = groups,
            directCount = direct,
            note = "Found $groups ${if (groups == 1) "group" else "groups"} and $direct one-to-one ${if (direct == 1) "chat" else "chats"}.$partial $NOTE"
        )
    }

    internal fun shortcutCommand(packageName: String, user: Int): String {
        require(BridgeInfo.isOpenBubblesPackage(packageName))
        require(user >= 0)
        // No --flags: the platform default includes dynamic, pinned and cached
        // shortcuts, and OpenBubbles publishes dynamic ones.
        return "cmd shortcut get-shortcuts --user $user '$packageName'"
    }

    internal fun channelCommand(packageName: String): String {
        require(BridgeInfo.isOpenBubblesPackage(packageName))
        // --package limits the dump; --noredact keeps full chat names.
        return "dumpsys notification --noredact --package '$packageName'"
    }

    private fun usable(result: ShizukuSetup.ShellResult): Boolean {
        if (!result.ok) return false
        val output = result.output
        return !output.contains("Unknown command", ignoreCase = true) &&
            !output.contains("Permission Denial", ignoreCase = true) &&
            !output.contains("not found", ignoreCase = true) &&
            !output.contains("No shell command implementation", ignoreCase = true)
    }

    internal fun parseShortcuts(raw: String, packageName: String): List<WireThread> {
        if (!BridgeInfo.isOpenBubblesPackage(packageName) || raw.length > 1_000_000) return emptyList()
        return raw.split("ShortcutInfo {").drop(1).mapNotNull { block ->
            val id = Regex("^id=([^\\r\\n]+?), flags=0x([a-fA-F0-9]+)\\s*\\[([^\\]]*)]").find(block)
                ?: return@mapNotNull null
            val threadId = id.groupValues[1].trim()
            val flags = id.groupValues[2].toLongOrNull(16) ?: return@mapNotNull null
            if (flags and 0x40L != 0L || id.groupValues[3].contains("Dis")) {
                return@mapNotNull null
            }
            val pkg = Regex("(?m)^\\s*packageName=([^\\s,]+)").find(block)?.groupValues?.get(1)
            if (pkg != packageName) return@mapNotNull null
            val title = Regex("(?m)^\\s*shortLabel=([^\\r\\n]+?), resId=").find(block)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotBlank() && it != "null" && it != "***" } ?: return@mapNotNull null
            chatFromThreadId(threadId)?.copy(title = title.take(160))
        }.distinctBy { it.id }
    }

    internal fun parseChannels(raw: String): List<WireThread> {
        if (raw.length > 1_000_000) return emptyList()
        val chats = raw.lineSequence().mapNotNull { line ->
            val marker = line.indexOf("NotificationChannel{mId=")
            if (marker < 0) return@mapNotNull null
            val body = line.substring(marker)
            val channelId = Regex("mId='([^']+)'").find(body)?.groupValues?.get(1)
                ?: return@mapNotNull null
            val threadId = threadIdFromChannelId(channelId) ?: return@mapNotNull null
            val deleted = Regex("mDeleted=(true|false)").find(body)?.groupValues?.get(1) == "true"
            if (deleted) return@mapNotNull null
            val rawName = Regex("mName=(.*?), mDescription=").find(body)?.groupValues?.get(1)
                ?: Regex("mName=(.*?), mImportance=").find(body)?.groupValues?.get(1)
                ?: return@mapNotNull null
            val title = rawName.trim()
                .removeSurrounding("'")
                .removeSurrounding("\"")
                .removePrefix(NOTIFY_ANYWAYS_TITLE)
                .trim()
                .takeIf { it.isNotBlank() && it != "null" && it != "***" }
                ?: return@mapNotNull null
            chatFromThreadId(threadId)?.copy(title = title.take(160))
        }.distinctBy { it.id }.take(MAX_CHATS).toList()
        return chats
    }

    /**
     * Maps a published channel ID back to the same shortcut ID a notification
     * uses, so channel-only chats merge with their later notification.
     */
    internal fun threadIdFromChannelId(channelId: String): String? = when {
        channelId.startsWith(CONVERSATION_CHANNEL_PREFIX) ->
            channelId.removePrefix(CONVERSATION_CHANNEL_PREFIX)
                .takeIf { chatFromThreadId(it) != null }

        channelId.startsWith(CHANNEL_PREFIX) ->
            channelId.removePrefix(CHANNEL_PREFIX).removeSuffix(NOTIFY_ANYWAYS_SUFFIX)
                .takeIf { Protocol.isChatGuid(it) }

        else -> null
    }

    private fun chatFromThreadId(threadId: String): WireThread? {
        val key = threadId.removePrefix(CHAT_ID_PREFIX)
        val guid = key.takeIf { Protocol.isChatGuid(it) }
        val isGroup = guid != null && Protocol.isGroupGuid(guid)
        val address = when {
            guid != null -> if (isGroup) null else Protocol.normalizeAddress(guid.substringAfterLast(";-;"))
            key.startsWith(ADDRESS_KEY_PREFIX) ->
                Protocol.normalizeAddress(key.removePrefix(ADDRESS_KEY_PREFIX)) ?: return null
            key.startsWith(CONTACT_KEY_PREFIX) && key.length > CONTACT_KEY_PREFIX.length -> null
            else -> return null
        }
        return WireThread(
            id = threadId,
            title = address ?: "Conversation",
            isGroup = isGroup,
            address = address,
            unread = false,
            canSend = false
        )
    }

    private fun add(into: LinkedHashMap<String, WireThread>, chat: WireThread) {
        val existing = into[chat.id]
        into[chat.id] = if (existing == null) {
            chat
        } else {
            existing.copy(
                title = existing.title.ifBlank { chat.title },
                address = existing.address ?: chat.address,
                isGroup = existing.isGroup || chat.isGroup
            )
        }
    }
}
