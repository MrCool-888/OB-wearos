package com.openbubbles.wear.patch;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.util.Log;

import java.security.MessageDigest;
import java.util.Locale;

final class CallerTrust {
    private static final String COMPANION_PACKAGE = "com.openbubbles.wear";
    private static final String COMPANION_CERT_SHA256 =
            "e5d2b8f7a0df6015f4ace1ab06c600797557fd9fb52e04930786b792c6dd42ae";

    private CallerTrust() {
    }

    static boolean isTrusted(Context context, int uid) {
        if (context == null || uid <= 0) return false;
        try {
            PackageManager manager = context.getPackageManager();
            String[] packages = manager.getPackagesForUid(uid);
            if (packages == null) return false;
            boolean named = false;
            for (String name : packages) {
                if (COMPANION_PACKAGE.equals(name)) {
                    named = true;
                    break;
                }
            }
            if (!named) return false;
            PackageInfo info = manager.getPackageInfo(COMPANION_PACKAGE,
                    PackageManager.GET_SIGNING_CERTIFICATES);
            SigningInfo signing = info.signingInfo;
            if (signing == null) return false;
            Signature[] signatures = signing.hasMultipleSigners()
                    ? signing.getApkContentsSigners() : signing.getSigningCertificateHistory();
            if (signatures == null) return false;
            for (Signature signature : signatures) {
                if (signature != null && COMPANION_CERT_SHA256.equals(sha256(signature.toByteArray()))) return true;
            }
        } catch (Throwable t) {
            Log.w("OBWearPatch", "Unable to verify the companion caller", t);
        }
        return false;
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte value : digest) out.append(String.format(Locale.ROOT, "%02x", value));
            return out.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}
