package com.openbubbles.messaging

import androidx.core.app.Person
import java.util.concurrent.ConcurrentHashMap

/**
 * Debug-only stand-in for OpenBubbles' notification data. It keeps a tiny
 * in-memory chat history so replies and updates behave like the real app.
 */
object FakeChatStore {
    data class Chat(
        val id: String,
        val title: String,
        val isGroup: Boolean,
        val participants: List<String>
    )

    data class Message(
        val text: String,
        val timestamp: Long,
        val sender: String?,
        val attachment: Boolean = false
    )

    private val chats = linkedMapOf(
        "fake-maya" to Chat("fake-maya", "Maya Chen", false, listOf("Maya Chen")),
        "fake-family" to Chat("fake-family", "Family", true, listOf("Mom", "Dad", "Sam", "Me"))
    )

    private val messages = ConcurrentHashMap<String, MutableList<Message>>()
    private val unread = ConcurrentHashMap<String, Boolean>()

    @Synchronized
    fun initialMessages(chatId: String): List<Message> {
        messages[chatId]?.let { return it.toList() }
        val now = System.currentTimeMillis()
        val initial = when (chatId) {
            "fake-maya" -> listOf(
                Message("Are we still on for dinner?", now - 32 * 60_000L, "Maya Chen"),
                Message("Yes! 7pm at the usual place?", now - 29 * 60_000L, null),
                Message("Perfect. I'll book a table.", now - 26 * 60_000L, "Maya Chen"),
                Message("Can you bring the book you borrowed?", now - 4 * 60_000L, "Maya Chen")
            )

            "fake-family" -> listOf(
                Message("Sunday lunch at ours?", now - 3 * 3_600_000L, "Mom"),
                Message("Works for me", now - 2 * 3_600_000L, "Dad"),
                Message("I can drive", now - 100 * 60_000L, "Sam"),
                Message("I'll bring dessert", now - 90 * 60_000L, null),
                Message("Amazing, see you Sunday x", now - 62 * 60_000L, "Mom")
            )

            else -> emptyList()
        }
        messages[chatId] = initial.toMutableList()
        unread[chatId] = true
        return initial
    }

    fun chat(chatId: String): Chat? = chats[chatId]

    @Synchronized
    fun appendReply(chatId: String, text: String) {
        initialMessages(chatId)
        messages[chatId]?.add(Message(text, System.currentTimeMillis(), null))
    }

    @Synchronized
    fun markRead(chatId: String) {
        unread[chatId] = false
    }

    fun isUnread(chatId: String): Boolean = unread[chatId] != false

    @Synchronized
    fun reset(chatId: String? = null) {
        if (chatId == null) {
            messages.clear()
            unread.clear()
        } else {
            messages.remove(chatId)
            unread.remove(chatId)
        }
    }

    fun person(name: String): Person =
        Person.Builder().setName(name).build()

    fun personOrNull(name: String?): Person? = name?.let { person(it) }

    fun allChats(): List<Chat> = chats.values.toList()
}
