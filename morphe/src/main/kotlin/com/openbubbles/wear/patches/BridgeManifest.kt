package com.openbubbles.wear.patches

import app.morphe.patcher.patch.PatchException
import org.w3c.dom.Document
import org.w3c.dom.Element

internal const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
internal const val SERVICE_NAME = "com.openbubbles.wear.patch.CompanionService"
internal const val BRIDGE_ACTION = "com.openbubbles.wear.PATCH_BRIDGE"
internal const val MEDIA_PROVIDER_NAME = "com.openbubbles.wear.patch.CompanionMediaProvider"
internal const val MEDIA_AUTHORITY = "com.openbubbles.messaging.wear.media"

internal fun addBridgeToManifest(document: Document) {
    val manifest = document.documentElement
    requireSupportedVersion(
        manifest.getAttribute("package"),
        manifest.getAttributeNS(ANDROID_NS, "versionName"),
        manifest.getAttributeNS(ANDROID_NS, "versionCode"),
    )
    val applications = manifest.getElementsByTagName("application")
    if (applications.length != 1) throw PatchException("Expected one application in the manifest")
    val application = applications.item(0) as Element
    if (application.getAttributeNS(ANDROID_NS, "name") != "com.localob.messaging.app.LocalObApplication") {
        throw PatchException("Expected the native LocalObApplication")
    }
    if (application.getElementsByTagName("service").let { services ->
            (0 until services.length).any { (services.item(it) as Element).getAttributeNS(ANDROID_NS, "name") == SERVICE_NAME }
        }) {
        throw PatchException("The bridge service is already declared")
    }
    val providers = application.getElementsByTagName("provider")
    if ((0 until providers.length).any {
            val provider = providers.item(it) as Element
            provider.getAttributeNS(ANDROID_NS, "name") == MEDIA_PROVIDER_NAME ||
                MEDIA_AUTHORITY in provider.getAttributeNS(ANDROID_NS, "authorities").split(';')
        }) {
        throw PatchException("The bridge media provider or authority is already declared")
    }

    val service = document.createElement("service").apply {
        setAttributeNS(ANDROID_NS, "android:name", SERVICE_NAME)
        setAttributeNS(ANDROID_NS, "android:exported", "true")
    }
    service.appendChild(document.createElement("intent-filter").apply {
        appendChild(document.createElement("action").apply {
            setAttributeNS(ANDROID_NS, "android:name", BRIDGE_ACTION)
        })
    })
    // The helper checks sendingUid + companion certificate on every Messenger request.
    // A signature permission would reject the separately signed companion.
    application.appendChild(service)
    application.appendChild(document.createElement("provider").apply {
        setAttributeNS(ANDROID_NS, "android:name", MEDIA_PROVIDER_NAME)
        setAttributeNS(ANDROID_NS, "android:authorities", MEDIA_AUTHORITY)
        setAttributeNS(ANDROID_NS, "android:exported", "true")
        setAttributeNS(ANDROID_NS, "android:grantUriPermissions", "false")
        // Every access checks Binder UID + companion certificate; URI grants cannot delegate access.
    })

    val queries = manifest.getElementsByTagName("queries").item(0) as? Element
        ?: document.createElement("queries").also { manifest.insertBefore(it, application) }
    val packages = queries.getElementsByTagName("package")
    if ((0 until packages.length).none {
            (packages.item(it) as Element).getAttributeNS(ANDROID_NS, "name") == "com.openbubbles.wear"
        }) {
        queries.appendChild(document.createElement("package").apply {
            setAttributeNS(ANDROID_NS, "android:name", "com.openbubbles.wear")
        })
    }
}
