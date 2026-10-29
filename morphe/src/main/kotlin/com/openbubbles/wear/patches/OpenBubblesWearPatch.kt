package com.openbubbles.wear.patches

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch

internal const val SERVICE_CLASS = "Lcom/openbubbles/wear/patch/CompanionService;"
private const val MEDIA_PROVIDER_CLASS = "Lcom/openbubbles/wear/patch/CompanionMediaProvider;"

private val verifyBackendPatch = bytecodePatch {
    execute {
        requireSupportedVersion()
        if (listOf(SERVICE_CLASS, MEDIA_PROVIDER_CLASS, "Lcom/openbubbles/wear/patch/CallerTrust;",
                "Lcom/openbubbles/wear/patch/BridgeOps;").any { classDefByOrNull(it) != null }) {
            throw PatchException("The bridge is already present")
        }
        REQUIRED_BACKEND_METHODS.forEach { (type, signatures) ->
            val definition = classDefByOrNull(type)
                ?: throw PatchException("Required backend class missing: $type")
            val methods = definition.methods.map { method ->
                "${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"
            }.toSet()
            requireExactMethods(type, signatures, methods)
        }
    }
}

private val bridgeManifestPatch = resourcePatch {
    dependsOn(verifyBackendPatch)
    execute {
        document("AndroidManifest.xml").use(::addBridgeToManifest)
    }
}

@Suppress("unused")
val openBubblesWearBridgePatch = bytecodePatch(
    name = "Wear companion bridge",
    description = "Adds a certificate-authenticated IPC service for the OpenBubbles Wear companion.",
) {
    compatibleWith(
        Compatibility(
            packageName = "com.openbubbles.messaging",
            name = "OpenBubbles",
            targets = listOf(AppTarget(version = "2.0.0", versionCode = 20002289)),
        ),
    )
    // Check the original backend before extension merging can mask missing classes.
    dependsOn(bridgeManifestPatch)
    extendWith("openbubbles-wear.mpe")
    execute {
        addSettingsMenu()
        val service = classDefByOrNull(SERVICE_CLASS)
            ?: throw PatchException("Extension CompanionService is missing")
        if (service.superclass != "Landroid/app/Service;" || service.methods.none {
                it.name == "onBind" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Intent;") &&
                    it.returnType == "Landroid/os/IBinder;"
            }) {
            throw PatchException("Extension CompanionService must implement android.app.Service.onBind(Intent)")
        }
        val provider = classDefByOrNull(MEDIA_PROVIDER_CLASS)
            ?: throw PatchException("Extension CompanionMediaProvider is missing")
        if (provider.superclass != "Landroid/content/ContentProvider;") {
            throw PatchException("Extension CompanionMediaProvider must extend ContentProvider")
        }
        requireExactMethods(MEDIA_PROVIDER_CLASS, listOf(
            "<init>()V", "onCreate()Z",
            "openFile(Landroid/net/Uri;Ljava/lang/String;)Landroid/os/ParcelFileDescriptor;",
            "getType(Landroid/net/Uri;)Ljava/lang/String;",
            "getTypeAnonymous(Landroid/net/Uri;)Ljava/lang/String;",
            "query(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;",
            "insert(Landroid/net/Uri;Landroid/content/ContentValues;)Landroid/net/Uri;",
            "update(Landroid/net/Uri;Landroid/content/ContentValues;Ljava/lang/String;[Ljava/lang/String;)I",
            "delete(Landroid/net/Uri;Ljava/lang/String;[Ljava/lang/String;)I",
        ), provider.methods.map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }.toSet())
    }
}

private fun app.morphe.patcher.patch.BytecodePatchContext.requireSupportedVersion() {
    requireSupportedVersion(packageMetadata.packageName, packageMetadata.versionName, packageMetadata.versionCode)
}

internal fun requireSupportedVersion(packageName: String, versionName: String, versionCode: String) {
    if (packageName != "com.openbubbles.messaging" || versionName != "2.0.0" || versionCode != "20002289") {
        throw PatchException("Unsupported app: $packageName $versionName ($versionCode); expected native OpenBubbles 2.0.0 (20002289)")
    }
}

internal fun requireExactMethods(type: String, required: List<String>, methods: Set<String>) {
    required.forEach { signature ->
        if (signature !in methods) throw PatchException("Required backend method missing: $type->$signature")
    }
}

internal val REQUIRED_BACKEND_METHODS = mapOf(
    "Lcom/localob/messaging/app/LocalObApplication;" to listOf("onCreate()V"),
    "Lcom/localob/messaging/backend/db/Database;" to listOf(
        "getChats()Lio/objectbox/Box;",
        "getMessages()Lio/objectbox/Box;",
        "getHandles()Lio/objectbox/Box;",
        "getAttachments()Lio/objectbox/Box;",
        "getAttachmentsDir()Ljava/io/File;",
    ),
    "Lcom/localob/messaging/app/service/BackendForegroundService\$Companion;" to listOf(
        "getState()Lkotlinx/coroutines/flow/StateFlow;",
        "getInstance()Lcom/localob/messaging/app/service/BackendForegroundService;",
        "start(Landroid/content/Context;)V",
    ),
    "Lcom/localob/messaging/app/service/BackendState\$Ready;" to listOf(
        "getBackend()Lcom/localob/messaging/backend/domain/BackendService;",
    ),
    "Lcom/localob/messaging/backend/domain/BackendService;" to listOf(
        "chat(Ljava/util/List;Ljava/lang/String;ZLkotlin/coroutines/Continuation;)Ljava/lang/Object;",
        "findChats(Ljava/util/List;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
        "chatById(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
    ),
    "Lcom/localob/messaging/backend/domain/Chat;" to listOf(
        "send(Lcom/localob/messaging/backend/domain/MessageContent;Lkotlinx/datetime/Instant;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
        "markRead(ZLkotlin/coroutines/Continuation;)Ljava/lang/Object;",
        "getGuid()Ljava/lang/String;",
        "isGroup()Z",
        "getHandlesFlow()Lkotlinx/coroutines/flow/StateFlow;",
        "getDisplayNameFlow()Lkotlinx/coroutines/flow/StateFlow;",
        "getHasUnreadFlow()Lkotlinx/coroutines/flow/StateFlow;",
        "getLastActivityFlow()Lkotlinx/coroutines/flow/StateFlow;",
        "getLastMessageFlow()Lkotlinx/coroutines/flow/StateFlow;",
    ),
    "Lcom/localob/messaging/backend/db/Chat;" to listOf(
        "getGuid()Ljava/lang/String;",
        "getHandles()Lio/objectbox/relation/ToMany;",
        "getId()J", "getDateDeleted()Ljava/util/Date;", "isRoutingStub()Z", "isGroup()Z",
        "getDisplayName()Ljava/lang/String;", "getTitle()Ljava/lang/String;",
        "getChatIdentifier()Ljava/lang/String;", "getHasUnreadMessage()Ljava/lang/Boolean;",
        "getDbLatestMessage()Lio/objectbox/relation/ToOne;", "getDbOnlyLatestMessageDate()Ljava/util/Date;",
    ),
    "Lcom/localob/messaging/backend/domain/Message;" to listOf(
        "getGuid()Ljava/lang/String;", "getSender()Ljava/lang/String;", "isFromMe()Ljava/lang/Boolean;",
        "getTimestamp()Lkotlinx/datetime/Instant;",
        "getContentFlow()Lkotlinx/coroutines/flow/StateFlow;",
        "getDeliveryStateFlow()Lkotlinx/coroutines/flow/StateFlow;",
    ),
    "Lcom/localob/messaging/backend/db/Message;" to listOf(
        "getGuid()Ljava/lang/String;", "getId()J", "getText()Ljava/lang/String;",
        "getHasAttachments()Ljava/lang/Boolean;", "getDateCreated()Ljava/util/Date;",
        "getDateDeleted()Ljava/util/Date;", "getAssociatedMessageType()Ljava/lang/String;",
        "getSender()Ljava/lang/String;", "isFromMe()Ljava/lang/Boolean;",
    ),
    "Lcom/localob/messaging/backend/db/Handle;" to listOf("getAddress()Ljava/lang/String;"),
    "Lcom/localob/messaging/backend/db/Attachment;" to listOf(
        "getGuid()Ljava/lang/String;", "getMimeType()Ljava/lang/String;",
        "isDownloaded()Ljava/lang/Boolean;", "getMessage()Lio/objectbox/relation/ToOne;",
        "getLocalPathFlow()Lkotlinx/coroutines/flow/StateFlow;",
    ),
    "Lcom/localob/messaging/backend/domain/ContentRun\$Text;" to listOf(
        "<init>(Ljava/lang/String;Lcom/localob/messaging/backend/domain/RunFormat;)V",
        "getText()Ljava/lang/String;",
    ),
    "Lcom/localob/messaging/backend/domain/MessageContent;" to listOf(
        "<init>(Ljava/util/List;Ljava/util/List;Lcom/localob/messaging/backend/domain/BalloonContent;Lcom/localob/messaging/backend/domain/MessageRef;Lcom/localob/messaging/backend/domain/MessageEffect;Ljava/lang/String;Z)V",
        "getRuns()Ljava/util/List;",
    ),
    "Lcom/localob/messaging/backend/domain/RunFormat\$Companion;" to listOf(
        "getPlain()Lcom/localob/messaging/backend/domain/RunFormat\$Flags;",
    ),
)
