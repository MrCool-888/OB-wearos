package com.openbubbles.messaging;

import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;

/**
 * Java shim for the fake notification. The Kotlin declaration of
 * MessagingStyle.Message treats the person as non-null, but OpenBubbles passes
 * null for outgoing messages (the real app is written against the platform
 * type). Java lets us reproduce that exactly.
 */
final class FakeMessage {
    private FakeMessage() {
    }

    static NotificationCompat.MessagingStyle.Message create(
            CharSequence text,
            long timestamp,
            Person person
    ) {
        return new NotificationCompat.MessagingStyle.Message(text, timestamp, person);
    }
}
