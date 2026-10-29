package com.openbubbles.wear.patch;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import com.localob.messaging.app.service.BackendForegroundService;
import com.localob.messaging.app.service.BackendState;
import com.localob.messaging.backend.db.Chat_;
import com.localob.messaging.backend.db.Database;
import com.localob.messaging.backend.db.Message_;
import com.localob.messaging.backend.domain.BackendService;
import com.localob.messaging.backend.domain.Chat;
import com.localob.messaging.backend.domain.ContentRun;
import com.localob.messaging.backend.domain.DeliveryState;
import com.localob.messaging.backend.domain.Message;
import com.localob.messaging.backend.domain.MessageContent;
import com.localob.messaging.backend.domain.RunFormat;

import io.objectbox.query.Query;
import io.objectbox.query.QueryBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import kotlin.ResultKt;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;

/**
 * Backend operations behind the Wear IPC service. Every method runs on the
 * service's worker thread, so waiting for a coroutine continuation is safe.
 */
final class BridgeOps {
    static final String PATCH_VERSION = "1.2.2";
    static final int API_VERSION = 1;

    private static final String TAG = "OBWearPatch";
    private static final long OP_TIMEOUT_MS = 20_000L;
    private static final int DEFAULT_CHAT_LIMIT = 80;
    private static final int MAX_CHAT_LIMIT = 200;
    private static final int MAX_CHAT_OFFSET = 5000;
    private static final int DEFAULT_MESSAGE_LIMIT = 80;
    private static final int MAX_MESSAGE_LIMIT = 240;
    static final int MAX_TEXT_LENGTH = 800;
    static final int MAX_COLLECTION_CHARS = 120_000;
    private static final int MAX_PARTICIPANTS = 32;

    private static final Pattern CHAT_GUID = Pattern.compile("^(iMessage|SMS|RCS);[+-];[^;\\s\\p{Cntrl}]{1,512}$");
    private static final Pattern NATIVE_CHAT_GUID = Pattern.compile(
            "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern NATIVE_GROUP_GUID = Pattern.compile("(?i)^[0-9a-f]{72}$");
    private static final Pattern PHONE = Pattern.compile("\\+?[0-9 ().-]+");
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Z0-9.!#$%&'*+/=^_`{|}~-]{1,64}@[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?(?:\\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+",
            Pattern.CASE_INSENSITIVE);

    private BridgeOps() {
    }

    static JSONObject status(Context context) throws Exception {
        JSONObject out = baseResponse(null);
        BackendState state = null;
        try {
            state = BackendForegroundService.Companion.getState().getValue();
        } catch (Throwable t) {
            Log.w(TAG, "Backend state unavailable", t);
        }
        out.put("ok", true);
        out.put("ready", state instanceof BackendState.Ready);
        out.put("backendState", state == null ? "Unavailable" : bounded(state.getClass().getSimpleName(), 80));
        out.put("appVersion", bounded(appVersion(context), 80));
        out.put("patchVersion", PATCH_VERSION);
        return out;
    }

    static JSONObject chats(JSONObject request) throws Exception {
        JSONObject out = baseResponse(request.optString("requestId", null));
        BackendService backend = backend();
        if (backend == null) return notReady(out);
        int limit = clamp(request.optInt("limit", DEFAULT_CHAT_LIMIT), 1, MAX_CHAT_LIMIT);
        int offset = clamp(request.optInt("offset", 0), 0, MAX_CHAT_OFFSET);
        List<com.localob.messaging.backend.db.Chat> entities = Database.INSTANCE.getChats().getAll();
        List<JSONObject> chats = new ArrayList<>();
        int deleted = 0;
        int routingStubs = 0;
        int invalidGuids = 0;
        int unreadableRows = 0;
        for (com.localob.messaging.backend.db.Chat chat : entities) {
            try {
                if (chat.getDateDeleted() != null) {
                    deleted++;
                    continue;
                }
                if (chat.isRoutingStub()) {
                    routingStubs++;
                    continue;
                }
                if (!validChatGuid(chat.getGuid())) {
                    invalidGuids++;
                    continue;
                }
                chats.add(chatJson(chat));
            } catch (Throwable t) {
                unreadableRows++;
            }
        }
        if (deleted + routingStubs + invalidGuids + unreadableRows > 0) {
            Log.w(TAG, "Skipped chat rows: deleted=" + deleted + ", routing-stub=" + routingStubs +
                    ", invalid-guid=" + invalidGuids + ", unreadable=" + unreadableRows);
        }
        Collections.sort(chats, (left, right) -> {
            int byActivity = Long.compare(right.optLong("lastTimestamp", 0L), left.optLong("lastTimestamp", 0L));
            return byActivity != 0 ? byActivity : left.optString("id").compareTo(right.optString("id"));
        });
        JSONArray array = new JSONArray();
        int chars = 0;
        int end = Math.min(chats.size(), offset + limit);
        int index = Math.min(offset, chats.size());
        for (; index < end; index++) {
            JSONObject chat = chats.get(index);
            chars += chat.toString().length() + 1;
            if (chars > MAX_COLLECTION_CHARS) break;
            array.put(chat);
        }
        out.put("ok", true);
        out.put("offset", offset);
        if (index < chats.size()) out.put("nextOffset", offset + array.length());
        out.put("chats", array);
        return out;
    }

    static JSONObject thread(JSONObject request) throws Exception {
        String requestId = request.optString("requestId", null);
        JSONObject out = baseResponse(requestId);
        BackendService backend = backend();
        if (backend == null) return notReady(out);
        int limit = clamp(request.optInt("limit", DEFAULT_MESSAGE_LIMIT), 1, MAX_MESSAGE_LIMIT);
        try {
            Chat chat = resolve(backend,
                    request.optString("threadId", null),
                    request.optString("address", null), false);
            if (chat == null) {
                out.put("ok", false);
                out.put("error", "Conversation not found.");
                return out;
            }
            out.put("ok", true);
            out.put("thread", threadJson(chat, limit));
        } catch (Throwable t) {
            return failure(out, t);
        }
        return out;
    }

    static JSONObject send(JSONObject request) throws Exception {
        return send(backend(), request);
    }

    static JSONObject send(BackendService backend, JSONObject request) throws Exception {
        JSONObject out = baseResponse(request.optString("requestId", null));
        if (backend == null) return notReady(out);
        String text = request.optString("text", "").trim();
        if (text.isEmpty()) {
            out.put("ok", false);
            out.put("error", "The message is empty.");
            return out;
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            out.put("ok", false);
            out.put("error", "The message is too long (maximum 800 characters).");
            return out;
        }
        Chat chat;
        Message message;
        String guid;
        try {
            chat = resolve(backend,
                    request.optString("threadId", null),
                    request.optString("address", null), true);
            if (chat == null) {
                out.put("ok", false);
                out.put("error", guidFromThreadId(request.optString("threadId", null)) != null
                        ? "Conversation not found. Refresh your conversations."
                        : "Enter a phone number or email address, like +15551234567 or name@example.com.");
                return out;
            }
            MessageContent content = new MessageContent(
                    Collections.singletonList(new ContentRun.Text(text, RunFormat.Companion.getPlain())),
                    Collections.emptyList(),
                    null, null, null, null, false);
            guid = chat.getGuid();
            if (!validChatGuid(guid)) throw new IllegalStateException("Invalid backend conversation ID.");
            message = (Message) Await.await(
                    continuation -> chat.send(content, null, continuation), OP_TIMEOUT_MS);
        } catch (Throwable t) {
            return failure(out, t);
        }
        // Once send returns, refresh errors must never invite a duplicate send.
        out.put("ok", true);
        out.put("threadId", guid);
        try {
            out.put("message", messageJson(message));
        } catch (Throwable t) {
            Log.w(TAG, "Sent message could not be serialized", t);
            out.put("historyUnavailable", true);
        }
        try {
            out.put("thread", threadJson(chat, DEFAULT_MESSAGE_LIMIT));
        } catch (Throwable t) {
            Log.w(TAG, "Message sent; history refresh unavailable", t);
            out.put("historyUnavailable", true);
        }
        return out;
    }

    static JSONObject markRead(JSONObject request) throws Exception {
        JSONObject out = baseResponse(request.optString("requestId", null));
        BackendService backend = backend();
        if (backend == null) return notReady(out);
        try {
            Chat chat = resolve(backend,
                    request.optString("threadId", null),
                    request.optString("address", null), false);
            if (chat == null) {
                out.put("ok", false);
                out.put("error", "Conversation not found.");
                return out;
            }
            boolean read = request.optBoolean("read", true);
            Await.await(continuation -> chat.markRead(read, continuation), OP_TIMEOUT_MS);
            out.put("ok", true);
            out.put("threadId", chat.getGuid());
        } catch (Throwable t) {
            return failure(out, t);
        }
        return out;
    }

    private static JSONObject baseResponse(String requestId) throws Exception {
        JSONObject out = new JSONObject();
        out.put("apiVersion", API_VERSION);
        if (requestId != null) out.put("requestId", bounded(requestId, 128));
        out.put("patchVersion", PATCH_VERSION);
        return out;
    }

    private static JSONObject notReady(JSONObject out) throws Exception {
        out.put("ok", false);
        out.put("ready", false);
        out.put("error", "OpenBubbles is still starting. Open it on the phone once, then retry.");
        return out;
    }

    private static JSONObject failure(JSONObject out, Throwable t) throws Exception {
        Log.w(TAG, "Bridge operation failed", t);
        out.put("ok", false);
        out.put("error", friendlyError(t));
        return out;
    }

    private static String friendlyError(Throwable t) {
        String message = t.getMessage();
        if (t instanceof TimeoutException) {
            return "OpenBubbles did not finish the request in time. Wait a moment, then try again.";
        }
        if (message == null || message.trim().isEmpty()) {
            return "OpenBubbles could not complete the request.";
        }
        // Kotlin exceptions sometimes carry class names or full stack traces.
        int newline = message.indexOf('\n');
        if (newline >= 0) message = message.substring(0, newline);
        message = message.trim();
        return message.isEmpty() ? "OpenBubbles could not complete the request." : bounded(message, 300);
    }

    private static BackendService backend() {
        try {
            BackendState state = BackendForegroundService.Companion.getState().getValue();
            if (state instanceof BackendState.Ready) return ((BackendState.Ready) state).getBackend();
        } catch (Throwable t) {
            Log.w(TAG, "Backend not ready", t);
        }
        return null;
    }

    static Chat resolve(BackendService backend, String threadId, String address, boolean create)
            throws Exception {
        String guid = guidFromThreadId(threadId);
        if (guid != null) {
            Chat found = (Chat) Await.await(
                    continuation -> backend.chatById(guid, continuation), OP_TIMEOUT_MS);
            if (found != null && !guid.equals(found.getGuid())) {
                throw new IllegalStateException("The backend returned a different conversation.");
            }
            return found;
        }
        if (!blank(threadId) && !isAddressThreadId(threadId)) {
            throw new IllegalArgumentException("Invalid conversation ID. Refresh your conversations.");
        }
        String normalized = normalizeAddress(address);
        if (normalized == null) return null;
        @SuppressWarnings("unchecked")
        List<Chat> existing = (List<Chat>) Await.await(
                continuation -> backend.findChats(Collections.singletonList(normalized), null, continuation),
                OP_TIMEOUT_MS);
        if (existing != null) {
            for (Chat chat : existing) if (isSingleRecipient(chat, normalized)) return chat;
        }
        if (!create) return null;
        Chat created = (Chat) Await.await(
                continuation -> backend.chat(Collections.singletonList(normalized), null, true, continuation),
                OP_TIMEOUT_MS);
        if (created != null && !isSingleRecipient(created, normalized)) {
            throw new IllegalStateException("The backend returned a different recipient.");
        }
        return created;
    }

    private static boolean isSingleRecipient(Chat chat, String address) {
        if (chat == null || chat.isGroup() || !validChatGuid(chat.getGuid()) || chat.getGuid().contains(";+;")) return false;
        String recipient = normalizeAddress(chat.getGuid());
        if (recipient != null) return address.equals(recipient);
        List<String> handles = chat.getHandlesFlow().getValue();
        return handles != null && handles.size() == 1 && address.equals(normalizeAddress(handles.get(0)));
    }

    static boolean validChatGuid(String value) {
        return value != null && (CHAT_GUID.matcher(value).matches() || NATIVE_CHAT_GUID.matcher(value).matches()
                || NATIVE_GROUP_GUID.matcher(value).matches());
    }

    static String guidFromThreadId(String threadId) {
        if (threadId == null) return null;
        String value = threadId.trim();
        if (value.startsWith("chat_")) value = value.substring("chat_".length());
        return validChatGuid(value) ? value : null;
    }

    private static boolean isAddressThreadId(String value) {
        value = value.trim();
        if (value.startsWith("chat_")) value = value.substring("chat_".length());
        return value.startsWith("addr:") || value.startsWith("contact:") ||
                value.startsWith("new:") || value.startsWith("new-");
    }

    static String normalizeAddress(String address) {
        if (address == null) return null;
        if (address.length() > 600) return null;
        for (int i = 0; i < address.length(); i++) if (Character.isISOControl(address.charAt(i))) return null;
        String value = address.trim();
        if (value.isEmpty()) return null;
        String lower = value.toLowerCase(Locale.ROOT);
        for (String prefix : new String[]{"tel:", "sms:", "smsto:", "mailto:"}) {
            if (lower.startsWith(prefix)) {
                value = value.substring(prefix.length());
                break;
            }
        }
        if (validChatGuid(value) && value.charAt(value.indexOf(';') + 1) == '-') {
            value = value.substring(value.indexOf(";-;") + 3);
        }
        value = value.trim();
        if (value.length() > 254) return null;
        if (value.contains("@")) {
            String local = value.substring(0, value.indexOf('@'));
            return EMAIL.matcher(value).matches() && !local.startsWith(".") && !local.endsWith(".") &&
                    !local.contains("..") ? value.toLowerCase(Locale.ROOT) : null;
        }
        if (!PHONE.matcher(value).matches()) return null;
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= '0' && c <= '9') || c == '+') digits.append(c);
        }
        int count = digits.length() - (digits.indexOf("+") == 0 ? 1 : 0);
        return count >= 5 && count <= 15 ? digits.toString() : null;
    }

    private static JSONObject chatJson(com.localob.messaging.backend.db.Chat chat) throws Exception {
        JSONObject out = new JSONObject();
        out.put("id", chat.getGuid());
        String address = null;
        JSONArray participants = new JSONArray();
        try {
            List<com.localob.messaging.backend.db.Handle> handles = chat.getHandles();
            for (com.localob.messaging.backend.db.Handle handle : handles) {
                String value = handle.getAddress();
                if (blank(value)) continue;
                if (participants.length() < MAX_PARTICIPANTS) participants.put(bounded(value, 254));
                if (address == null) address = normalizeAddress(value);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to read participants for " + chat.getGuid(), t);
        }
        boolean group = participants.length() > 1 || chat.isGroup();
        String title = firstNonBlank(chat.getDisplayName(), chat.getTitle(),
                group ? null : address, chat.getChatIdentifier(), chat.getGuid());
        out.put("title", bounded(title, 160));
        out.put("isGroup", group);
        if (!group && address != null) out.put("address", address);
        out.put("participants", participants);
        out.put("unread", Boolean.TRUE.equals(chat.getHasUnreadMessage()));
        Date fallbackDate = null;
        try {
            fallbackDate = chat.getDbOnlyLatestMessageDate();
        } catch (Throwable ignored) {
        }
        Message last = null;
        try {
            io.objectbox.relation.ToOne<com.localob.messaging.backend.db.Message> latest = chat.getDbLatestMessage();
            if (latest != null) last = latest.getTarget();
        } catch (Throwable ignored) {
        }
        putChatActivity(out, last, fallbackDate);
        return out;
    }

    static void putChatActivity(JSONObject out, Message last, Date fallbackDate) throws Exception {
        long timestamp = fallbackDate == null ? 0L : fallbackDate.getTime();
        String lastPreview = "";
        if (last != null) {
            try {
                kotlinx.datetime.Instant messageTimestamp = last.getTimestamp();
                if (messageTimestamp != null) timestamp = messageTimestamp.toEpochMilliseconds();
            } catch (Throwable ignored) {
            }
            try {
                lastPreview = preview(last);
            } catch (Throwable ignored) {
            }
        }
        out.put("lastTimestamp", timestamp);
        out.put("lastPreview", bounded(lastPreview, MAX_TEXT_LENGTH));
    }

    private static JSONObject threadJson(Chat chat, int limit) throws Exception {
        JSONObject out = new JSONObject();
        String guid = chat.getGuid();
        out.put("id", guid);
        com.localob.messaging.backend.db.Chat entity;
        try (Query<com.localob.messaging.backend.db.Chat> query = Database.INSTANCE.getChats()
                .query().equal(Chat_.guid, guid, QueryBuilder.StringOrder.CASE_SENSITIVE).build()) {
            entity = query.findUnique();
        }
        if (entity != null) {
            out = chatJson(entity);
        } else {
            out = threadMetadata(chat);
        }
        JSONArray messages = new JSONArray();
        if (entity != null) {
            List<com.localob.messaging.backend.db.Message> found;
            try (Query<com.localob.messaging.backend.db.Message> query = Database.INSTANCE.getMessages()
                    .query()
                    .equal(Message_.chatId, entity.getId())
                    .orderDesc(Message_.dateCreated)
                    .build()) {
                found = query.find(0, limit);
            }
            List<JSONObject> serialized = new ArrayList<>();
            int chars = 0;
            for (com.localob.messaging.backend.db.Message message : found) {
                if (message.getDateDeleted() != null) continue;
                if (message.getAssociatedMessageType() != null) continue;
                JSONObject json = messageJson(message);
                if (json == null) continue;
                chars += json.toString().length() + 1;
                if (chars > MAX_COLLECTION_CHARS) break;
                serialized.add(json);
            }
            Collections.reverse(serialized);
            for (JSONObject json : serialized) messages.put(json);
        }
        out.put("messages", messages);
        return out;
    }

    static JSONObject threadMetadata(Chat chat) throws Exception {
        JSONObject out = new JSONObject();
        out.put("id", chat.getGuid());
        out.put("title", bounded(firstNonBlank(chat.getDisplayNameFlow().getValue(), chat.getGuid()), 160));
        out.put("isGroup", chat.isGroup());
        out.put("unread", Boolean.TRUE.equals(chat.getHasUnreadFlow().getValue()));
        JSONArray participants = new JSONArray();
        List<String> handles = chat.getHandlesFlow().getValue();
        if (handles != null) {
            for (String handle : handles) {
                if (!blank(handle) && participants.length() < MAX_PARTICIPANTS) participants.put(bounded(handle, 254));
            }
            if (!chat.isGroup() && handles.size() == 1) out.put("address", normalizeAddress(handles.get(0)));
        }
        out.put("participants", participants);
        kotlinx.datetime.Instant activity = chat.getLastActivityFlow().getValue();
        out.put("lastTimestamp", activity == null ? 0L : activity.toEpochMilliseconds());
        Message last = chat.getLastMessageFlow().getValue();
        out.put("lastPreview", last == null ? "" : preview(last));
        return out;
    }

    static JSONObject messageJson(Message message) throws Exception {
        if (message == null || blank(message.getGuid()) || message.getGuid().length() > 512) return null;
        String text = messageText(message);
        boolean attachment = false;
        long timestamp;
        boolean fromMe;
        String sender = null;
        if (message instanceof com.localob.messaging.backend.db.Message) {
            com.localob.messaging.backend.db.Message entity = (com.localob.messaging.backend.db.Message) message;
            attachment = Boolean.TRUE.equals(entity.getHasAttachments());
            timestamp = entity.getDateCreated() == null ? 0L : entity.getDateCreated().getTime();
            fromMe = Boolean.TRUE.equals(entity.isFromMe());
            sender = entity.getSender();
        } else {
            timestamp = message.getTimestamp().toEpochMilliseconds();
            fromMe = Boolean.TRUE.equals(message.isFromMe());
            sender = message.getSender();
        }
        if (blank(text) && !attachment) return null;
        JSONObject out = new JSONObject();
        out.put("id", message.getGuid());
        out.put("text", bounded(text, MAX_TEXT_LENGTH));
        out.put("timestamp", timestamp);
        out.put("fromMe", fromMe);
        if (sender != null) out.put("sender", bounded(sender, 254));
        if (attachment) out.put("attachment", true);
        if (fromMe) {
            try {
                String status;
                if (message instanceof com.localob.messaging.backend.db.Message) {
                    // ObjectBox hydrates fields before OB initializes the entity's delivery flow.
                    com.localob.messaging.backend.db.Message entity = (com.localob.messaging.backend.db.Message) message;
                    status = entity.getDateRead() != null ? "read" : entity.getDateDelivered() != null ? "delivered" :
                            entity.getErrorMessage() != null ? "failed" : entity.getSendingServiceId() != null ? "sending" : "sent";
                } else {
                    DeliveryState state = message.getDeliveryStateFlow().getValue();
                    status = state instanceof DeliveryState.Failed ? "failed" :
                            state instanceof DeliveryState.Read ? "read" :
                            state instanceof DeliveryState.Delivered || state instanceof DeliveryState.DeliveredQuietly ? "delivered" :
                            state instanceof DeliveryState.Sending ? "sending" : state instanceof DeliveryState.Sent ? "sent" : null;
                }
                if (status != null) out.put("status", status);
            } catch (Throwable t) {
                Log.d(TAG, "Message delivery status unavailable", t);
            }
        }
        if (attachment && message instanceof com.localob.messaging.backend.db.Message) {
            CompanionMediaProvider.putMedia(out, (com.localob.messaging.backend.db.Message) message);
        }
        return out;
    }

    private static String preview(Message message) {
        String text = messageText(message);
        if (!blank(text)) return bounded(text.trim(), MAX_TEXT_LENGTH);
        if (message instanceof com.localob.messaging.backend.db.Message &&
                Boolean.TRUE.equals(((com.localob.messaging.backend.db.Message) message).getHasAttachments())) {
            return "Photo";
        }
        return "";
    }

    /** Domain messages expose text through content runs; entities store it directly. */
    private static String messageText(Message message) {
        if (message instanceof com.localob.messaging.backend.db.Message) {
            return bounded(((com.localob.messaging.backend.db.Message) message).getText(), MAX_TEXT_LENGTH);
        }
        try {
            MessageContent content = message.getContentFlow().getValue();
            if (content == null) return null;
            StringBuilder text = new StringBuilder();
            for (ContentRun run : content.getRuns()) {
                if (run instanceof ContentRun.Text) {
                    String value = ((ContentRun.Text) run).getText();
                    if (value != null) {
                        int remaining = MAX_TEXT_LENGTH - text.length();
                        text.append(bounded(value, remaining));
                        if (value.length() >= remaining) break;
                    }
                }
            }
            return text.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String appVersion(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            return "";
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static String bounded(String value, int max) {
        if (value == null) return "";
        if (value.length() <= max) return value;
        int end = max;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (!blank(value)) return value.trim();
        return "";
    }

    /**
     * Runs a Kotlin suspend call outside a coroutine and waits for its result.
     * Only safe on the service worker thread.
     */
    private interface SuspendCall<T> {
        Object invoke(Continuation<Object> continuation) throws Exception;
    }

    private static final class Await<T> implements Continuation<Object> {
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile Object value;

        @Override
        public CoroutineContext getContext() {
            return EmptyCoroutineContext.INSTANCE;
        }

        @Override
        public void resumeWith(Object result) {
            value = result;
            latch.countDown();
        }

        static <T> T await(SuspendCall<T> call, long timeoutMs) throws Exception {
            Await<T> await = new Await<>();
            Object result = call.invoke(await);
            if (result != IntrinsicsKt.getCOROUTINE_SUSPENDED()) {
                ResultKt.throwOnFailure(result);
                @SuppressWarnings("unchecked") T value = (T) result;
                return value;
            }
            if (!await.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new TimeoutException("OpenBubbles timed out");
            }
            ResultKt.throwOnFailure(await.value);
            @SuppressWarnings("unchecked") T value = (T) await.value;
            return value;
        }
    }
}
