package com.openbubbles.wear.patch;

import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Process;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPackageManager;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32, manifest = Config.NONE, application = Application.class)
public class CompanionMediaProviderTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void onlyCanonicalGuidUrisAreAccepted() throws Exception {
        String guid = "A0B1C2D3-1234-5678-90AB-ABCDEF123456";
        Uri uri = CompanionMediaProvider.uriFor(guid);
        assertEquals(guid, CompanionMediaProvider.attachmentGuid(uri));
        for (String invalid : new String[]{"", "../secret", "a/b", "a%2fb", "a.b", "a\\b", "a\nb", "a".repeat(201)}) {
            assertFalse(invalid, CompanionMediaProvider.validAttachmentGuid(invalid));
        }
        for (String invalid : new String[]{"file:///attachment/ABC", "content://wrong/attachment/ABC",
                "content://" + CompanionMediaProvider.AUTHORITY + "/attachment/ABC/extra",
                uri + "?path=/data/private", uri + "#fragment",
                "content://" + CompanionMediaProvider.AUTHORITY + "/attachment/%41BC",
                "content://" + CompanionMediaProvider.AUTHORITY + "/attachment/..%2fsecret"}) {
            assertThrows(invalid, FileNotFoundException.class,
                    () -> CompanionMediaProvider.attachmentGuid(Uri.parse(invalid)));
        }
    }

    @Test public void confinesFilesToDownloadedAttachmentDirectory() throws Exception {
        File root = temporary.newFolder("attachments");
        File nested = new File(root, "guid");
        assertTrue(nested.mkdir());
        File image = new File(nested, "photo.gif");
        Files.write(image.toPath(), new byte[]{1, 2, 3});
        assertEquals(image.getCanonicalFile(), CompanionMediaProvider.containedFile(root, image));
        assertTrue(CompanionMediaProvider.hasReadableFile(root, image.getAbsolutePath()));
        assertFalse(CompanionMediaProvider.hasReadableFile(root, null));
        assertFalse(CompanionMediaProvider.hasReadableFile(root, new File(root, "missing").getAbsolutePath()));
        assertFalse(CompanionMediaProvider.hasReadableFile(root, nested.getAbsolutePath()));
        File other = temporary.newFolder("attachments-other");
        File secret = new File(other, "secret.gif");
        Files.write(secret.toPath(), new byte[]{4});
        assertThrows(FileNotFoundException.class, () -> CompanionMediaProvider.containedFile(root, secret));
        assertThrows(FileNotFoundException.class, () -> CompanionMediaProvider.containedFile(root,
                new File(root, "../attachments-other/secret.gif")));
        assertThrows(FileNotFoundException.class, () -> CompanionMediaProvider.containedFile(root, root));
        assertThrows(FileNotFoundException.class, () -> CompanionMediaProvider.containedFile(root, nested));
        assertThrows(FileNotFoundException.class, () -> CompanionMediaProvider.containedFile(root, new File(root, "missing")));
    }

    @Test public void rejectsWriteModesAndNonImageMimeTypes() {
        CompanionMediaProvider.requireReadMode("r");
        for (String mode : new String[]{"w", "rw", "rwt", "wa", "wt", "", null}) {
            assertThrows(SecurityException.class, () -> CompanionMediaProvider.requireReadMode(mode));
        }
        assertEquals("image/gif", CompanionMediaProvider.imageMime("IMAGE/GIF"));
        assertEquals("image/jpeg", CompanionMediaProvider.imageMime("image/jpeg"));
        assertNull(CompanionMediaProvider.imageMime("text/plain"));
        assertNull(CompanionMediaProvider.imageMime("image/svg+xml"));
        assertNull(CompanionMediaProvider.imageMime("image/gif\r\ntext/plain"));
    }

    @Test public void rejectsSymlinkEscapingAttachmentDirectory() throws Exception {
        File root = temporary.newFolder("attachments");
        File secret = temporary.newFile("secret.gif");
        File link = new File(root, "linked.gif");
        try {
            Files.createSymbolicLink(link.toPath(), secret.toPath());
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
            org.junit.Assume.assumeNoException("Host cannot create symlinks", e);
        }
        assertThrows(FileNotFoundException.class, () -> CompanionMediaProvider.containedFile(root, link));
    }

    @Test public void everyDataEntryPointRejectsUnauthenticatedCallerBeforeDatabaseAccess() {
        CompanionMediaProvider provider = new CompanionMediaProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = CompanionMediaProvider.AUTHORITY;
        provider.attachInfo(RuntimeEnvironment.getApplication(), info);
        Uri uri = CompanionMediaProvider.uriFor("ABC");
        assertTrue(provider.onCreate());
        assertThrows(SecurityException.class, () -> provider.openFile(uri, "r"));
        assertThrows(SecurityException.class, () -> provider.getType(uri));
        assertThrows(SecurityException.class, () -> provider.query(uri, null, null, null, null));
        assertThrows(SecurityException.class, () -> provider.insert(uri, new ContentValues()));
        assertThrows(SecurityException.class, () -> provider.update(uri, new ContentValues(), null, null));
        assertThrows(SecurityException.class, () -> provider.delete(uri, null, null));
        assertNull(provider.getTypeAnonymous(uri));
    }

    @Test public void companionPackageNameAloneDoesNotAuthorizeAccess() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ShadowPackageManager manager = shadowOf(context.getPackageManager());
        PackageInfo info = new PackageInfo();
        info.packageName = "com.openbubbles.wear";
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = info.packageName;
        info.applicationInfo.uid = Process.myUid();
        info.signingInfo = new SigningInfo();
        shadowOf(info.signingInfo).setSignatures(new Signature[]{new Signature("wrong certificate".getBytes(StandardCharsets.UTF_8))});
        manager.installPackage(info);
        manager.setPackagesForUid(Process.myUid(), info.packageName);
        assertFalse(CallerTrust.isTrusted(context, Process.myUid()));
        CompanionMediaProvider provider = new CompanionMediaProvider();
        ProviderInfo providerInfo = new ProviderInfo();
        providerInfo.authority = CompanionMediaProvider.AUTHORITY;
        provider.attachInfo(context, providerInfo);
        assertThrows(SecurityException.class, () -> provider.openFile(CompanionMediaProvider.uriFor("ABC"), "r"));
    }

    @Test public void callerTrustFailsClosedAndPreservesSha256Encoding() {
        Context context = RuntimeEnvironment.getApplication();
        assertFalse(CallerTrust.isTrusted(context, 0));
        assertFalse(CallerTrust.isTrusted(context, -1));
        assertFalse(CallerTrust.isTrusted(context, Process.myUid()));
        assertFalse(CallerTrust.isTrusted(null, 1234));
        assertFalse(CallerTrust.isTrusted(new ContextWrapper(context) {
            @Override public PackageManager getPackageManager() { throw new IllegalStateException("unavailable"); }
        }, 1234));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                CallerTrust.sha256("abc".getBytes(StandardCharsets.UTF_8)));
    }
}
