package com.openbubbles.wear.patch;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import com.localob.messaging.app.service.BackendForegroundService;
import com.localob.messaging.app.service.BackendState;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Certificate-authenticated bridge for the OpenBubbles Wear companion.
 *
 * <p>Only the release-signed companion package may call it. The sending UID
 * comes from {@link Message#sendingUid}, which the Messenger framework captures
 * on the Binder thread, so deferring work to our worker thread cannot forge it.
 */
public final class CompanionService extends Service {
    static final String ACTION = "com.openbubbles.wear.PATCH_BRIDGE";
    static final String EXTRA_JSON = "json";
    static final int MSG_REQUEST = 1;
    static final int MSG_REPLY = 2;

    private static final String TAG = "OBWearPatch";
    private static final int MAX_REQUEST_CHARS = 16_384;
    private static final int MAX_RESPONSE_BYTES = 500_000;

    private HandlerThread worker;
    private Messenger messenger;

    @Override
    public void onCreate() {
        super.onCreate();
        BridgeOps.initializeNames(getApplicationContext());
        worker = new HandlerThread("OpenBubblesWearPatch");
        worker.start();
        messenger = new Messenger(new Handler(worker.getLooper(), this::handleRequest));
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return null;
        return messenger == null ? null : messenger.getBinder();
    }

    @Override
    public void onDestroy() {
        if (worker != null) worker.quitSafely();
        super.onDestroy();
    }

    private boolean handleRequest(Message message) {
        if (message.what != MSG_REQUEST) return false;
        int callingUid = message.sendingUid;
        String response;
        if (!CallerTrust.isTrusted(this, callingUid)) {
            Log.w(TAG, "Rejected bridge request from uid " + callingUid);
            response = error(null, "The bridge caller is not trusted.");
        } else {
            try {
                Bundle data = message.getData();
                String json = data == null ? null : data.getString(EXTRA_JSON);
                response = json == null || json.length() > MAX_REQUEST_CHARS
                        ? error(null, "Invalid bridge request.") : dispatch(json);
            } catch (RuntimeException t) {
                response = error(null, "Invalid bridge request.");
            }
        }
        reply(message, response);
        return true;
    }

    String dispatch(String json) {
        JSONObject request = null;
        try {
            request = new JSONObject(json);
            if (request.optInt("apiVersion", -1) != BridgeOps.API_VERSION) {
                return error(request.optString("requestId", null), "Unsupported Wear bridge API version.");
            }
            String op = request.optString("op", "");
            if (!op.equals("status") && !PatchSettings.isEnabled(this)) {
                return new JSONObject(error(request.optString("requestId", null),
                        "Wear companion bridge is disabled in OpenBubbles Settings → Morphe patches."))
                        .put("enabled", false).toString();
            }
            if (op.equals("status") || op.equals("chats") || op.equals("thread") ||
                    op.equals("send") || op.equals("markRead")) {
                prepareBackend(!op.equals("status"));
            }
            switch (op) {
                case "status":
                    return BridgeOps.status(this).put("enabled", PatchSettings.isEnabled(this)).toString();
                case "chats":
                    return BridgeOps.chats(request).toString();
                case "thread":
                    return BridgeOps.thread(request).toString();
                case "send":
                    JSONObject sendRequest = request;
                    return SendJournal.run(getSharedPreferences("wear_send_journal", MODE_PRIVATE),
                            request, () -> BridgeOps.send(sendRequest)).toString();
                case "markRead":
                    return BridgeOps.markRead(request).toString();
                default:
                    return error(request.optString("requestId", null), "Unknown bridge operation.");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Bridge request failed", t);
            String requestId = request == null ? null : request.optString("requestId", null);
            return error(requestId, friendly(t));
        }
    }

    private void prepareBackend(boolean wait) {
        try {
            if (BackendForegroundService.Companion.getInstance() == null) {
                BackendForegroundService.Companion.start(this);
            }
            long deadline = SystemClock.elapsedRealtime() + 8_000;
            while (wait && SystemClock.elapsedRealtime() < deadline) {
                BackendState state = BackendForegroundService.Companion.getState().getValue();
                if (!(state instanceof BackendState.Starting)) break;
                Thread.sleep(100);
            }
        } catch (Throwable t) {
            // Android/OEM background-start restrictions can still require opening OpenBubbles once.
            Log.w(TAG, "Could not start the OpenBubbles backend", t);
        }
    }

    private void reply(Message request, String json) {
        Messenger target = request.replyTo;
        if (target == null) return;
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
            json = error(null, "The conversation is too large to transfer. Load a smaller recent history.");
        }
        Bundle bundle = new Bundle();
        bundle.putString(EXTRA_JSON, json);
        Message message = Message.obtain(null, MSG_REPLY);
        message.setData(bundle);
        try {
            target.send(message);
        } catch (RemoteException t) {
            Log.d(TAG, "Companion disconnected before the reply", t);
        }
    }

    private static String error(String requestId, String message) {
        try {
            JSONObject out = new JSONObject();
            out.put("apiVersion", BridgeOps.API_VERSION);
            if (requestId != null) out.put("requestId", requestId);
            out.put("ok", false);
            out.put("error", message);
            return out.toString();
        } catch (Throwable t) {
            return "{\"apiVersion\":1,\"ok\":false,\"error\":\"Bridge error\"}";
        }
    }

    private static String friendly(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "OpenBubbles could not complete the request.";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}
