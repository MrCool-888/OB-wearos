package com.openbubbles.wear.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.openbubbles.wear.protocol.HistoryPayload
import com.openbubbles.wear.protocol.Protocol
import com.openbubbles.wear.protocol.WireMessage
import com.openbubbles.wear.protocol.WireThread
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Drives OpenBubbles' own Compose UI with an AccessibilityService. This is the
 * fallback for actions that a notification cannot provide:
 *
 * - sending to a chat whose notification has been dismissed
 * - starting a brand-new conversation
 * - scrolling back through message bubbles to recover older history
 *
 * The official app is never modified. All sends still go through OpenBubbles'
 * composer and its normal encryption/transport. The service is optional; the
 * notification-only path keeps working when it is disabled.
 */
object OpenBubblesAutomation {
    private const val TAG = "OBWearAutomation"
    private const val MAX_PAGES = 30
    private const val PAGE_DELAY_MS = 360L
    private const val MAX_MESSAGE_LENGTH = 800

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()

    @Volatile
    private var service: OpenBubblesAutomationService? = null

    data class HistoryOutcome(
        val messages: List<WireMessage> = emptyList(),
        val hasMore: Boolean = false,
        val error: String? = null
    )

    private class AutomationException(message: String) : Exception(message)

    private data class ScrapedLink(
        val text: String,
        val fromMe: Boolean,
        val top: Int,
        val left: Int
    )

    fun attach(service: OpenBubblesAutomationService) {
        this.service = service
        Log.d(TAG, "Accessibility service attached")
    }

    fun detach(service: OpenBubblesAutomationService) {
        if (this.service !== service) return
        this.service = null
        Log.d(TAG, "Accessibility service detached")
    }

    fun isReady(): Boolean = service != null

    fun interrupt() {
        // Jobs have short polling steps and fail cleanly if the service goes away.
    }

    fun send(
        threadId: String?,
        title: String?,
        address: String?,
        text: String,
        contentIntent: PendingIntent?,
        callback: (String?) -> Unit
    ) {
        scope.launch {
            val error = try {
                withTimeout(90_000L) {
                    mutex.withLock {
                        performSend(threadId, title, address, text, contentIntent)
                    }
                }
                null
            } catch (t: Throwable) {
                failure(t)
            }
            callback(error)
        }
    }

    fun loadHistory(
        thread: WireThread,
        limit: Int,
        contentIntent: PendingIntent?,
        callback: (HistoryOutcome) -> Unit
    ) {
        scope.launch {
            val outcome = try {
                withTimeout(120_000L) {
                    mutex.withLock {
                        performHistory(thread, limit.coerceIn(10, 200), contentIntent)
                    }
                }
            } catch (t: Throwable) {
                HistoryOutcome(error = failure(t))
            }
            callback(outcome)
        }
    }

    private suspend fun performSend(
        threadId: String?,
        title: String?,
        address: String?,
        text: String,
        contentIntent: PendingIntent?
    ) {
        if (text.isBlank()) throw AutomationException("The message is empty.")
        openConversation(threadId, title, address, contentIntent)

        val composer = waitForComposer(8_000)
            ?: throw AutomationException("OpenBubbles did not show a message box for this chat.")
        if (!setText(composer, text.trim())) {
            throw AutomationException("Could not type into OpenBubbles' message box.")
        }
        val sendResult = pressSend(text.trim())
        if (!sendResult.confirmed && !sendResult.clickedSend) {
            throw AutomationException(
                "Could not find the Send button. Open the chat in OpenBubbles and try again."
            )
        }
        Log.i(TAG, "Automation send finished for thread=$threadId address=$address")
    }

    private suspend fun performHistory(
        thread: WireThread,
        limit: Int,
        contentIntent: PendingIntent?
    ): HistoryOutcome {
        openConversation(thread.id, thread.title, thread.address, contentIntent)
        ensureComposer()

        val pages = mutableListOf<List<ScrapedLink>>()
        var reachedTop = false
        var stalePages = 0
        var previousSignature = ""

        scrollToBottom()
        for (pageIndex in 0 until MAX_PAGES) {
            val root = appRootOrNull() ?: break
            val scrollable = findScrollable(root) ?: break
            val page = scrapePage(root, scrollable, thread)
            if (page.isNotEmpty()) pages += page
            val signature = pageSignature(page)
            if (page.isNotEmpty()) {
                val chronological = buildChronological(pages)
                val older = olderThanKnown(chronological, thread.messages)
                if (older.size >= limit) break
            }

            val moved = scrollBackward(scrollable)
            delay(PAGE_DELAY_MS)
            val afterRoot = appRootOrNull() ?: break
            val afterScrollable = findScrollable(afterRoot) ?: break
            val afterPage = scrapePage(afterRoot, afterScrollable, thread)
            val afterSignature = pageSignature(afterPage)
            if (!moved) {
                reachedTop = true
                break
            }
            if (afterSignature == signature || afterSignature == previousSignature) {
                stalePages++
                if (stalePages >= 2) {
                    reachedTop = true
                    break
                }
            } else {
                stalePages = 0
            }
            previousSignature = signature
            if (pageIndex == MAX_PAGES - 1) reachedTop = false
        }

        val chronological = buildChronological(pages)
        val older = olderThanKnown(chronological, thread.messages)
        if (older.isEmpty()) {
            return HistoryOutcome(
                messages = emptyList(),
                hasMore = false,
                error = null
            )
        }
        val hasMore = older.size > limit || !reachedTop
        val selected = older.takeLast(limit)
        val anchor = thread.messages
            .asSequence()
            .filterNot { it.id.startsWith("local-") }
            .minByOrNull { it.timestamp }
            ?.timestamp
            ?.takeIf { it > 0L }
            ?: System.currentTimeMillis()

        val messages = selected.mapIndexed { index, link ->
            val distance = (selected.size - index).toLong()
            val timestamp = (anchor - distance * 60_000L).coerceAtLeast(1L)
            WireMessage(
                id = Protocol.messageId(
                    threadId = thread.id,
                    timestamp = timestamp,
                    sender = if (link.fromMe) null else thread.title,
                    text = link.text
                ),
                threadId = thread.id,
                text = link.text,
                timestamp = timestamp,
                // Accessibility exposes bubbles, not reliable sender names. Avoid
                // labelling a group's history with the group title; one-to-one
                // history can safely use the contact name.
                sender = if (link.fromMe || thread.isGroup) null else thread.title,
                fromMe = link.fromMe
            )
        }
        Log.i(
            TAG,
            "History scrape for ${thread.id}: ${messages.size} older messages, hasMore=$hasMore"
        )
        return HistoryOutcome(messages = messages, hasMore = hasMore, error = null)
    }

    /**
     * Opens the exact chat when OpenBubbles still has a usable content intent.
     * Otherwise it navigates the UI by title, or uses the new-chat extras for a
     * one-to-one address.
     */
    private suspend fun openConversation(
        threadId: String?,
        title: String?,
        address: String?,
        contentIntent: PendingIntent?
    ) {
        val service = requireService()
        if (contentIntent != null) {
            val sent = runCatching { contentIntent.send() }.isSuccess
            if (sent && waitForComposer(8_000) != null) return
        }

        if (threadId == null || threadId.startsWith("new:")) {
            val recipient = address?.takeIf { it.isNotBlank() }
                ?: title?.takeIf { it.isNotBlank() }
                ?: throw AutomationException("Enter a phone number or email address first.")
            launchNewChat(recipient, title)
            return
        }

        launchMain()
        waitForApp(8_000)
        if (waitForComposer(2_000) != null) {
            if (title.isNullOrBlank() || headerMatches(title)) return
            pressBack()
            delay(700)
        }

        val query = title?.takeIf { it.isNotBlank() }
            ?: address?.takeIf { it.isNotBlank() }
            ?: throw AutomationException("This chat has no title to search for.")
        repeat(3) {
            if (searchAndOpen(query)) return
            if (clickConversation(query)) return
            if (scrollAndOpen(query)) return
            pressBack()
            delay(600)
        }

        if (!address.isNullOrBlank()) {
            runCatching { launchNewChat(address, title) }
                .onSuccess { return }
                .onFailure { Log.w(TAG, "New-chat fallback failed", it) }
        }
        throw AutomationException("Could not find \"$query\" in OpenBubbles.")
    }

    private suspend fun launchMain() {
        val service = requireService()
        val pkg = BridgeInfo.openBubblesPackage(service)
            ?: throw AutomationException("OpenBubbles is not installed.")
        val launch = service.packageManager.getLaunchIntentForPackage(pkg)
            ?: throw AutomationException("OpenBubbles cannot be opened.")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { service.startActivity(launch) }
            .onFailure { throw AutomationException("Android blocked opening OpenBubbles: ${it.message}") }
        delay(600)
    }

    private suspend fun launchNewChat(address: String, title: String?) {
        val service = requireService()
        val pkg = BridgeInfo.openBubblesPackage(service)
            ?: throw AutomationException("OpenBubbles is not installed.")
        val launch = service.packageManager.getLaunchIntentForPackage(pkg)
            ?: throw AutomationException("OpenBubbles cannot be opened.")
        launch.action = Intent.ACTION_VIEW
        launch.putExtra("new_chat_address", address)
        launch.putExtra("new_chat_name", title?.takeIf { it.isNotBlank() } ?: address)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { service.startActivity(launch) }
            .onFailure { throw AutomationException("Android blocked opening OpenBubbles: ${it.message}") }
        if (waitForComposer(12_000) == null) {
            throw AutomationException(
                "OpenBubbles did not open a composer for $address. Check the address."
            )
        }
    }

    private suspend fun searchAndOpen(query: String): Boolean {
        var root = appRootOrNull() ?: return false
        var search = findSearchField(root)
        if (search == null) {
            val entry = findSearchEntry(root) ?: return false
            click(entry)
            delay(600)
            root = appRootOrNull() ?: return false
            search = findSearchField(root) ?: return false
        }
        click(search)
        delay(120)
        if (!setText(search, query)) return false
        delay(1_000)
        root = appRootOrNull() ?: return false
        val result = findResult(root, query, search) ?: return false
        click(result)
        delay(700)
        return waitForComposer(8_000) != null
    }

    private suspend fun clickConversation(title: String): Boolean {
        val root = appRootOrNull() ?: return false
        val target = findResult(root, title, null) ?: return false
        click(target)
        delay(700)
        return waitForComposer(8_000) != null
    }

    private suspend fun scrollAndOpen(title: String): Boolean {
        repeat(12) {
            val root = appRootOrNull() ?: return false
            val target = findResult(root, title, null)
            if (target != null) {
                click(target)
                delay(700)
                if (waitForComposer(8_000) != null) return true
            }
            val scrollable = findScrollable(root) ?: return false
            if (!scrollForward(scrollable)) return false
            delay(350)
        }
        return false
    }

    private suspend fun scrollToBottom() {
        repeat(16) {
            val root = appRootOrNull() ?: return
            val scrollable = findScrollable(root) ?: return
            val before = pageSignature(scrapePage(root, scrollable, null))
            if (!scrollForward(scrollable)) return
            delay(260)
            val afterRoot = appRootOrNull() ?: return
            val afterScrollable = findScrollable(afterRoot) ?: return
            val after = pageSignature(scrapePage(afterRoot, afterScrollable, null))
            if (after == before) return
        }
    }

    private suspend fun ensureComposer() {
        if (waitForComposer(8_000) == null) {
            throw AutomationException("OpenBubbles did not show a message box for this chat.")
        }
    }

    private suspend fun pressBack() {
        val service = service ?: return
        runCatching { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
        delay(300)
    }

    private suspend fun waitForApp(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (appRootOrNull() != null) return true
            delay(150)
        }
        return false
    }

    private suspend fun waitForComposer(timeoutMs: Long): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = appRootOrNull()
            if (root != null) {
                val composer = findComposer(root)
                if (composer != null) return composer
            }
            delay(150)
        }
        return null
    }

    private fun headerMatches(title: String): Boolean {
        val root = appRootOrNull() ?: return false
        val rootBounds = Rect().also { root.getBoundsInScreen(it) }
        val maxY = rootBounds.top + rootBounds.height() * 0.28f
        val wanted = normalize(title)
        return findNodes(root).any { node ->
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            val label = node.label()
            bounds.top <= maxY &&
                label.isNotBlank() &&
                normalize(label).contains(wanted)
        }
    }

    private data class SendResult(val confirmed: Boolean, val clickedSend: Boolean)

    private suspend fun pressSend(text: String): SendResult {
        var clickedSend = false
        val root = appRootOrNull()
        val composer = root?.let(::findComposer)
        val button = root?.let { findSendButton(it, composer) }
        if (button != null && button.isEnabled) {
            clickedSend = click(button)
        }
        if (clickedSend) {
            if (waitForSend(text, 4_500)) return SendResult(true, true)
        }

        val focused = appRootOrNull()?.let(::findComposer)
        if (focused != null) {
            focused.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            runCatching {
                focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
        }
        if (waitForSend(text, 3_500)) return SendResult(true, clickedSend)
        return SendResult(false, clickedSend)
    }

    private suspend fun waitForSend(text: String, timeoutMs: Long): Boolean {
        val marker = normalize(text).take(32)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = appRootOrNull()
            if (root != null) {
                val composer = findComposer(root)
                if (composer == null) return true
                val current = normalize(nodeText(composer))
                if (current.isEmpty()) return true
                if (marker.isNotEmpty() && containsMessageText(root, marker, composer)) return true
            }
            delay(180)
        }
        return false
    }

    private fun containsMessageText(
        root: AccessibilityNodeInfo,
        marker: String,
        composer: AccessibilityNodeInfo
    ): Boolean {
        val composerBounds = Rect().also { composer.getBoundsInScreen(it) }
        return findNodes(root).any { node ->
            if (node.isEditable) return@any false
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            if (Rect.intersects(bounds, composerBounds)) return@any false
            normalize(nodeText(node)).contains(marker)
        }
    }

    private fun findComposer(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val rootBounds = Rect().also { root.getBoundsInScreen(it) }
        val bottomLimit = rootBounds.top + rootBounds.height() * 0.42f
        val candidates = findNodes(root).filter { node ->
            if (!isEditable(node)) return@filter false
            val hint = normalize(node.label())
            if (hint.contains("search")) return@filter false
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            val looksLikeMessage = hint.contains("message") ||
                hint.contains("imessage") ||
                hint.contains("text") ||
                hint.contains("reply")
            looksLikeMessage || bounds.bottom >= bottomLimit
        }
        return candidates.maxByOrNull { node ->
            Rect().also { node.getBoundsInScreen(it) }.bottom
        }
    }

    private fun findSearchField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findNodes(root).firstOrNull { node ->
            if (!isEditable(node)) return@firstOrNull false
            val hint = normalize(node.label())
            if (hint.contains("search")) return@firstOrNull true
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            val rootBounds = Rect().also { root.getBoundsInScreen(it) }
            bounds.bottom < rootBounds.top + rootBounds.height() * 0.35f
        }
    }

    private fun findSearchEntry(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val exact = findNodes(root).firstOrNull { node ->
            val label = normalize(node.label())
            label == "search" || label.contains("search messages") || label.contains("search chats")
        }
        if (exact != null) return exact
        return findNodes(root).firstOrNull { node ->
            val id = node.viewIdResourceName.orEmpty()
            id.contains("search", ignoreCase = true)
        }
    }

    private fun findResult(
        root: AccessibilityNodeInfo,
        query: String,
        search: AccessibilityNodeInfo?
    ): AccessibilityNodeInfo? {
        val wanted = normalize(query)
        if (wanted.isBlank()) return null
        val searchBounds = search?.let { node ->
            Rect().also { rect -> node.getBoundsInScreen(rect) }
        }
        val candidates = findNodes(root).filter { node ->
            if (node.isEditable) return@filter false
            if (search != null && node === search) return@filter false
            val label = normalize(node.label())
            if (label.isBlank()) return@filter false
            if (!label.contains(wanted)) return@filter false
            if (label == "search" || label == wanted && node.isClickable.not()) {
                // A non-clickable exact title can still be a row label; the
                // ancestor lookup below handles it.
            }
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            if (searchBounds != null && Rect.intersects(bounds, searchBounds)) return@filter false
            val id = node.viewIdResourceName.orEmpty()
            if (id.contains("search", ignoreCase = true) && !node.isClickable) return@filter false
            true
        }.sortedBy { normalize(it.label()).length }
        for (candidate in candidates) {
            val clickable = clickableAncestor(candidate)
            if (clickable != null) return clickable
        }
        return null
    }

    private fun findSendButton(
        root: AccessibilityNodeInfo,
        composer: AccessibilityNodeInfo?
    ): AccessibilityNodeInfo? {
        val labelled = findNodes(root)
            .filter { node ->
                val label = normalize(node.label())
                val id = node.viewIdResourceName.orEmpty()
                val looksLikeSend = label == "send" ||
                    label == "send message" ||
                    label == "send imessage" ||
                    id.contains("send", ignoreCase = true)
                looksLikeSend && !label.contains("send later") && !label.contains("send to")
            }
            .mapNotNull { node -> clickableAncestor(node) ?: node.takeIf { it.isClickable } }
        if (labelled.isNotEmpty()) {
            return labelled.firstOrNull { it.isEnabled } ?: labelled.first()
        }

        val composerBounds = composer?.let { node ->
            Rect().also { rect -> node.getBoundsInScreen(rect) }
        } ?: return null
        val rootBounds = Rect().also { root.getBoundsInScreen(it) }
        val minSize = dp(24)
        val maxSize = dp(96)
        return findNodes(root)
            .filter { node ->
                if (!node.isClickable && clickableAncestor(node) == null) return@filter false
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                val label = normalize(node.label())
                val nearComposer = bounds.centerY() in
                    (composerBounds.top - dp(40))..(composerBounds.bottom + dp(40))
                val rightSide = bounds.centerX() > rootBounds.left + rootBounds.width() * 0.62f
                val sizeOk = bounds.width() in minSize..maxSize && bounds.height() in minSize..maxSize
                val bad = listOf("attach", "photo", "camera", "mic", "emoji", "plus", "back")
                    .any { label.contains(it) }
                nearComposer && rightSide && sizeOk && !bad
            }
            .maxByOrNull { node -> Rect().also { node.getBoundsInScreen(it) }.right }
    }

    private fun scrollBackward(scrollable: AccessibilityNodeInfo): Boolean =
        scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)

    private fun scrollForward(scrollable: AccessibilityNodeInfo): Boolean =
        scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)

    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val rootBounds = Rect().also { root.getBoundsInScreen(it) }
        return findNodes(root)
            .filter { node ->
                val actions = node.actions
                val canScroll = node.isScrollable ||
                    actions and AccessibilityNodeInfo.ACTION_SCROLL_FORWARD != 0 ||
                    actions and AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD != 0
                if (!canScroll) return@filter false
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                val tall = bounds.height() > bounds.width()
                val bigEnough = bounds.height() > rootBounds.height() * 0.22f
                tall && bigEnough
            }
            .maxByOrNull { node ->
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                bounds.width().toLong() * bounds.height()
            }
    }

    private fun scrapePage(
        root: AccessibilityNodeInfo,
        scrollable: AccessibilityNodeInfo,
        thread: WireThread?
    ): List<ScrapedLink> {
        val scrollBounds = Rect().also { scrollable.getBoundsInScreen(it) }
        val rootBounds = Rect().also { root.getBoundsInScreen(it) }
        val participantNames = thread?.participants
            .orEmpty()
            .map(::normalize)
            .filter { it.isNotBlank() }
            .toSet()
        val results = mutableListOf<ScrapedLink>()
        val seen = mutableSetOf<String>()

        findNodes(root).forEach { node ->
            if (node.isEditable) return@forEach
            val raw = nodeText(node)
            if (raw.isBlank()) return@forEach
            if (hasTextChild(node)) return@forEach
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            if (bounds.height() <= 0 || bounds.width() <= 0) return@forEach
            if (bounds.top < scrollBounds.top - dp(2) ||
                bounds.bottom > scrollBounds.bottom + dp(2)
            ) {
                return@forEach
            }
            if (bounds.height() > scrollBounds.height() * 0.65f) return@forEach

            val text = cleanMessageText(raw) ?: return@forEach
            val normalized = normalize(text)
            if (normalized.isBlank()) return@forEach
            if (isUiLabel(normalized)) return@forEach
            if (thread != null && normalized == normalize(thread.title)) return@forEach
            if (normalized in participantNames) return@forEach

            val fromMe = isFromMe(bounds, rootBounds)
            val key = "$normalized|$fromMe|${bounds.top / 4}|${bounds.left / 4}"
            if (!seen.add(key)) return@forEach
            results += ScrapedLink(
                text = text,
                fromMe = fromMe,
                top = bounds.top,
                left = bounds.left
            )
        }

        // Screen bounds are captured above because nodes are recycled while the
        // conversation scrolls. Sorting on those coordinates preserves the
        // visual top-to-bottom bubble order instead of an arbitrary text order.
        return results.sortedWith(compareBy({ it.top }, { it.left }))
    }

    private fun hasTextChild(node: AccessibilityNodeInfo): Boolean {
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            if (nodeText(child).isNotBlank()) return true
        }
        return false
    }

    private fun isFromMe(bounds: Rect, rootBounds: Rect): Boolean {
        val leftMargin = bounds.left - rootBounds.left
        val rightMargin = rootBounds.right - bounds.right
        if (rightMargin + dp(18) < leftMargin) return true
        if (leftMargin + dp(18) < rightMargin) return false
        return bounds.centerX() > rootBounds.centerX()
    }

    private fun cleanMessageText(raw: String): String? {
        val lines = raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot(::isUiLabel)
        if (lines.isEmpty()) return null
        val text = lines.joinToString(" ").trim()
        if (text.isBlank()) return null
        return if (text.length > MAX_MESSAGE_LENGTH) {
            text.take(MAX_MESSAGE_LENGTH - 1) + "…"
        } else {
            text
        }
    }

    private fun isUiLabel(text: String): Boolean {
        val normalized = normalize(text)
        if (normalized.isBlank()) return true
        if (normalized in UI_LABELS) return true
        if (normalized.matches(TIME_PATTERN)) return true
        if (normalized.matches(DATE_PATTERN)) return true
        if (normalized.startsWith("delivered to ") ||
            normalized.startsWith("read by ") ||
            normalized.startsWith("sent to ")
        ) {
            return true
        }
        if (normalized.startsWith("loved an image") ||
            normalized.startsWith("liked an image") ||
            normalized.startsWith("laughed at")
        ) {
            return true
        }
        return false
    }

    private fun pageSignature(page: List<ScrapedLink>): String =
        page.joinToString("|") { "${normalize(it.text)}:${it.fromMe}" }

    private fun buildChronological(pages: List<List<ScrapedLink>>): List<ScrapedLink> {
        if (pages.isEmpty()) return emptyList()
        var chronological = pages.first()
        for (olderPage in pages.drop(1)) {
            chronological = mergeOlderPage(olderPage, chronological)
        }
        return chronological
    }

    private fun mergeOlderPage(
        olderPage: List<ScrapedLink>,
        newerChronological: List<ScrapedLink>
    ): List<ScrapedLink> {
        val max = minOf(olderPage.size, newerChronological.size)
        for (overlap in max downTo 1) {
            val olderTail = olderPage.takeLast(overlap).map(::linkKey)
            val newerHead = newerChronological.take(overlap).map(::linkKey)
            if (olderTail == newerHead) {
                return olderPage + newerChronological.drop(overlap)
            }
        }
        return olderPage + newerChronological
    }

    /**
     * Returns the messages that precede the oldest message the phone already
     * knows. The match is by bubble text/alignment, because OpenBubbles does not
     * expose message GUIDs or exact timestamps through accessibility.
     */
    private fun olderThanKnown(
        scraped: List<ScrapedLink>,
        knownMessages: List<WireMessage>
    ): List<ScrapedLink> {
        if (scraped.isEmpty()) return emptyList()
        val known = knownMessages
            .filterNot { it.id.startsWith("local-") }
            .filter { it.text.isNotBlank() }
        if (known.isEmpty()) return scraped
        val knownKeys = known.map(::messageKey).toSet()

        var bestRun = 0
        var bestStart = -1
        for (startKnown in known.indices) {
            val key = messageKey(known[startKnown])
            for (startScraped in scraped.indices) {
                if (linkKey(scraped[startScraped]) != key) continue
                var run = 0
                while (startKnown + run < known.size &&
                    startScraped + run < scraped.size &&
                    messageKey(known[startKnown + run]) ==
                    linkKey(scraped[startScraped + run])
                ) {
                    run++
                }
                if (run > bestRun || run == bestRun && startScraped > bestStart) {
                    bestRun = run
                    bestStart = startScraped
                }
            }
        }

        if (bestRun > 0 && bestStart >= 0) {
            return scraped
                .take(bestStart)
                .filterNot { linkKey(it) in knownKeys }
        }

        // The exact text of the last known messages may have been rewritten by
        // reaction summaries or truncation. Drop anything that definitely
        // duplicates a cached message rather than returning it twice.
        return scraped.filterNot { linkKey(it) in knownKeys }
    }

    private fun linkKey(link: ScrapedLink): String =
        normalize(link.text) + "|" + if (link.fromMe) "1" else "0"

    private fun messageKey(message: WireMessage): String =
        normalize(message.text) + "|" + if (message.fromMe) "1" else "0"

    private fun click(node: AccessibilityNodeInfo): Boolean {
        val target = if (node.isClickable || node.actionList.any {
                it.id == AccessibilityNodeInfo.ACTION_CLICK
            }
        ) {
            node
        } else {
            clickableAncestor(node)
        } ?: return gestureClick(node)
        if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        return gestureClick(target)
    }

    private fun gestureClick(node: AccessibilityNodeInfo): Boolean {
        val service = service ?: return false
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val path = Path().apply {
            moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        return runCatching { service.dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 7) {
            if (current.isClickable || current.actionList.any {
                    it.id == AccessibilityNodeInfo.ACTION_CLICK
                }
            ) {
                return current
            }
            current = runCatching { current?.parent }.getOrNull()
            depth++
        }
        return null
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val set = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (set) {
            val current = appRootOrNull()?.let(::findComposer)
            if (current == null || normalize(nodeText(current)).isNotEmpty()) return true
        }
        return pasteText(text)
    }

    private fun pasteText(text: String): Boolean {
        val service = service ?: return false
        return runCatching {
            val clipboard = service.getSystemService(ClipboardManager::class.java) ?: return false
            clipboard.setPrimaryClip(ClipData.newPlainText("OpenBubbles message", text))
            val composer = appRootOrNull()?.let(::findComposer) ?: return false
            composer.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            composer.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            composer.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }.getOrDefault(false)
    }

    private fun isEditable(node: AccessibilityNodeInfo): Boolean =
        node.isEditable ||
            node.className?.toString()?.contains("EditText", ignoreCase = true) == true ||
            node.actions and AccessibilityNodeInfo.ACTION_SET_TEXT != 0

    private fun nodeText(node: AccessibilityNodeInfo): String =
        node.text?.toString()?.trim().orEmpty()

    private fun AccessibilityNodeInfo.label(): String {
        val values = listOfNotNull(
            text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        )
        return values.distinct().joinToString(" ")
    }

    private fun findNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val results = ArrayList<AccessibilityNodeInfo>(128)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 5000) {
            val node = queue.removeFirst()
            visited++
            results += node
            for (index in 0 until node.childCount) {
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                queue.add(child)
            }
        }
        return results
    }

    private fun appRootOrNull(): AccessibilityNodeInfo? {
        val service = service ?: return null
        val pkg = runCatching { BridgeInfo.openBubblesPackage(service) }.getOrNull() ?: return null
        var fallback: AccessibilityNodeInfo? = null
        val windows = runCatching { service.windows }.getOrNull().orEmpty()
        for (window in windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = runCatching { window.root }.getOrNull() ?: continue
            if (root.packageName?.toString() != pkg) continue
            if (window.isActive) return root
            if (fallback == null) fallback = root
        }
        val active = runCatching { service.rootInActiveWindow }.getOrNull()
        if (active?.packageName?.toString() == pkg) return active
        return fallback
    }

    private fun requireService(): OpenBubblesAutomationService =
        service ?: throw AutomationException(
            "Accessibility automation is off. Enable it in the OpenBubbles Wear bridge app."
        )

    private fun failure(t: Throwable): String {
        val message = when (t) {
            is AutomationException -> t.message
            is TimeoutCancellationException -> "The phone took too long to finish."
            else -> t.message
        }
        if (t !is AutomationException) {
            Log.w(TAG, "Automation failed", t)
        }
        return message?.takeIf { it.isNotBlank() } ?: "The phone could not complete that action."
    }

    private fun dp(value: Int): Int {
        val density = service?.resources?.displayMetrics?.density ?: 1f
        return (value * density).toInt()
    }

    private fun normalize(text: String): String =
        text.lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim()

    private val UI_LABELS = setOf(
        "delivered", "read", "sent", "sending", "not delivered", "edited",
        "imessage", "sms", "mms", "tap to download", "downloading",
        "photo", "image", "attachment", "you", "new messages", "messages",
        "search", "cancel", "done", "today", "yesterday", "now"
    )

    private val TIME_PATTERN = Regex(
        """^(\d{1,2}:\d{2}(:\d{2})?\s*(am|pm)?|\d{1,2}\s*(am|pm))$""",
        RegexOption.IGNORE_CASE
    )

    private val DATE_PATTERN = Regex(
        """^((mon|tue|wed|thu|fri|sat|sun)(day|sday|nesday|rsday|urday)?|""" +
            """(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)(uary|ruary|ch|il|e|y|ust|tember|ober|ember)?|""" +
            """\d{1,2}/\d{1,2}(/\d{2,4})?|\d{1,2}\s+[a-z]{3,9})$""",
        RegexOption.IGNORE_CASE
    )
}
