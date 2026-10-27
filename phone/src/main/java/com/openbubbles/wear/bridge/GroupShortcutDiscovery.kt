package com.openbubbles.wear.bridge

import android.content.Context
import com.openbubbles.wear.protocol.Protocol
import com.openbubbles.wear.protocol.WireThread

/** Reads only OpenBubbles' Android-published shortcuts. Never reads its private files. */
object GroupShortcutDiscovery {
    const val NOTE = "Only groups published as Android shortcuts are available here, not the full private group database. No notification is required for these shortcuts."

    data class Result(val groups: List<WireThread> = emptyList(), val error: String? = null)

    fun discover(context: Context): Result {
        if (!ShizukuSetup.hasPermission()) return Result(error = "Start Shizuku using wireless debugging and grant the phone bridge access to find published groups without notifications.")
        val pkg = BridgeInfo.openBubblesPackage(context) ?: return Result(error = "OpenBubbles is not installed.")
        val user = android.os.Process.myUid() / 100_000
        val output = ShizukuSetup.shell(command(pkg, user))
        if (!output.ok) return Result(error = "Android did not allow reading OpenBubbles shortcuts. ${output.output.take(300)}")
        if (output.output.contains("Unknown command", true) || output.output.contains("Permission Denial", true)) {
            return Result(error = "This Android build does not expose the shortcut query through Shizuku.")
        }
        return Result(parse(output.output, pkg))
    }

    internal fun command(packageName: String, user: Int): String {
        require(BridgeInfo.isOpenBubblesPackage(packageName))
        require(user >= 0)
        // Dynamic (1), pinned (2), and cached conversation shortcuts (16).
        return "cmd shortcut get-shortcuts --user $user --flags 19 '$packageName'"
    }

    internal fun parse(raw: String, packageName: String): List<WireThread> {
        if (!BridgeInfo.isOpenBubblesPackage(packageName) || raw.length > 1_000_000) return emptyList()
        return raw.split("ShortcutInfo {").drop(1).mapNotNull { block ->
            val id = Regex("^id=([^\\r\\n]+?), flags=0x([a-fA-F0-9]+)\\s*\\[([^\\]]*)]").find(block) ?: return@mapNotNull null
            val guid = id.groupValues[1].trim()
            val flags = id.groupValues[2].toLongOrNull(16) ?: return@mapNotNull null
            if (!Protocol.isGroupGuid(guid) || flags and 0x40L != 0L || id.groupValues[3].contains("Dis")) return@mapNotNull null
            val pkg = Regex("(?m)^\\s*packageName=([^\\s,]+)").find(block)?.groupValues?.get(1)
            if (pkg != packageName) return@mapNotNull null
            val title = Regex("(?m)^\\s*shortLabel=([^\\r\\n]+?), resId=").find(block)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotBlank() && it != "null" && it != "***" } ?: return@mapNotNull null
            WireThread(id = guid, title = title.take(160), isGroup = true, unread = false)
        }.distinctBy { it.id }.take(40)
    }
}
