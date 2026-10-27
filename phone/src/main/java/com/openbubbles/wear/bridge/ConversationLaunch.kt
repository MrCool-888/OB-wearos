package com.openbubbles.wear.bridge

import android.content.Intent
import com.openbubbles.wear.protocol.Protocol

internal object ConversationLaunch {
    fun matchesHeader(label: String, title: String): Boolean {
        fun normalized(value: String) = value.trim().lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ")
        val wanted = normalized(title)
        return wanted.isNotBlank() && label.lines().any { normalized(it) == wanted }
    }

    fun existingGuid(launcher: Intent, guid: String): Intent {
        require(Protocol.isChatGuid(guid))
        require(BridgeInfo.isOpenBubblesPackage(launcher.component?.packageName ?: launcher.`package`.orEmpty()))
        return Intent(launcher).apply {
            action = Intent.ACTION_VIEW
            putExtra("chatGuid", guid)
            putExtra("android.intent.extra.shortcut.ID", guid)
            putExtra("bubble", false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }
}
