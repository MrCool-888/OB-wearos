import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
}

group = "com.openbubbles.wear"

repositories {
    google()
    mavenCentral()
}

// The official Desktop release contains Patcher 2.0.1 and its provided dependencies.
// Using it as compileOnly avoids requiring GitHub Packages credentials on local builds.
val morpheCli = layout.buildDirectory.file("tools/morphe-desktop-1.19.1-all.jar")
val downloadMorphe by tasks.registering {
    val url = "https://github.com/MorpheApp/morphe-desktop/releases/download/v1.19.1/morphe-desktop-1.19.1-all.jar"
    val sha256 = "a51472252cd63b2eac635dbb5a154ff918bfc15268da89679feb3d760230c573"
    inputs.property("url", url)
    inputs.property("sha256", sha256)
    outputs.file(morpheCli)
    doLast {
        val output = morpheCli.get().asFile
        output.parentFile.mkdirs()
        val temporary = File(output.parentFile, "${output.name}.part")
        try {
            URI(url).toURL().openStream().use { input ->
                temporary.outputStream().use(input::copyTo)
            }
            check(temporary.sha256() == sha256) { "Morphe CLI SHA-256 mismatch" }
            temporary.copyTo(output, overwrite = true)
        } finally {
            temporary.delete()
        }
    }
}

val providedMorphe = files(morpheCli).builtBy(downloadMorphe)
val d8 by configurations.creating
dependencies {
    compileOnly(providedMorphe)
    testImplementation(providedMorphe)
    testImplementation(kotlin("test-junit"))
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("com.google.guava:guava:32.0.1-android")
    testRuntimeOnly("com.google.guava:guava:32.0.1-android")
    // SDK 35's D8 cannot preserve the Kotlin 2.4 metadata needed by Android patch loaders.
    add(d8.name, "com.android.tools:r8:9.1.31")
}
afterEvaluate {
    configurations.getByName<org.gradle.api.artifacts.Configuration>("testRuntimeClasspath")
        .resolutionStrategy.force("com.google.guava:guava:32.0.1-android")
}

kotlin.compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
java.targetCompatibility = JavaVersion.VERSION_11

// Test sources run on JDK 17; keep JVM target consistent for test compilation.
tasks.named<JavaCompile>("compileTestJava") {
    sourceCompatibility = JavaVersion.VERSION_17.toString()
    targetCompatibility = JavaVersion.VERSION_17.toString()
}
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

// The injected service compiles against the stock app's classes. The jar is
// produced from the app's own DEX (d2j-dex2jar) and is used only as a classpath:
// it is never packaged into the patch or the final APK.
val appClassesJar = providers.gradleProperty("appClassesJar").map { file(it) }
    .orElse(provider { file("../work/openbubbles-classes-v52.jar") })
val sdk = providers.environmentVariable("ANDROID_HOME")
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
val androidJar = sdk.map { "$it/platforms/android-35/android.jar" }

sourceSets.test { java.srcDir("src/extension/java") }
dependencies {
    testCompileOnly(files(androidJar))
    testImplementation(files(androidJar))
    testImplementation(files(appClassesJar))
    testImplementation("org.robolectric:robolectric:4.14.1")
}
tasks.test { maxHeapSize = "2g" }

val extensionClasses by tasks.registering(JavaCompile::class) {
    source = fileTree("src/extension/java") { include("**/*.java") }
    classpath = files(androidJar, appClassesJar)
    destinationDirectory.set(layout.buildDirectory.dir("extension-classes"))
    sourceCompatibility = "11"
    targetCompatibility = "11"
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
    doFirst {
        check(androidJar.get().let(::file).isFile) {
            "Set ANDROID_HOME to an Android SDK with android-35"
        }
        check(appClassesJar.get().isFile) {
            "Missing Stock app classes: ${appClassesJar.get()} (run d2j-dex2jar on work/com.openbubbles.messaging.apk)"
        }
    }
}

val extensionClassJar by tasks.registering(Jar::class) {
    dependsOn(extensionClasses)
    archiveFileName.set("openbubbles-wear-extension.jar")
    destinationDirectory.set(layout.buildDirectory.dir("extension"))
    from(extensionClasses.map { it.destinationDirectory })
}

val extensionDexDirectory = layout.buildDirectory.dir("extension-dex")
val compileExtensionDex by tasks.registering(JavaExec::class) {
    dependsOn(extensionClassJar)
    mainClass.set("com.android.tools.r8.D8")
    classpath = d8
    inputs.file(extensionClassJar.flatMap { it.archiveFile })
    inputs.files(androidJar, appClassesJar, providedMorphe)
    outputs.dir(extensionDexDirectory)
    doFirst {
        extensionDexDirectory.get().asFile.mkdirs()
        args(
            "--release", "--min-api", "32",
            "--lib", androidJar.get(),
            "--classpath", appClassesJar.get().absolutePath,
            "--classpath", morpheCli.get().asFile.absolutePath,
            "--output", extensionDexDirectory.get().asFile.absolutePath,
            extensionClassJar.get().archiveFile.get().asFile.absolutePath,
        )
    }
}

// One ZIP with JVM patch classes, Android DEX, manifest metadata and the raw
// extension DEX; no Patcher or app classes bundled.
tasks.jar {
    dependsOn(compileExtensionDex)
    archiveClassifier.set("jvm")
    from(compileExtensionDex) {
        include("classes.dex")
        rename { "openbubbles-wear.mpe" }
    }
    manifest.attributes(
        "Name" to "OpenBubbles Wear bridge patches",
        "Description" to "Adds the certificate-authenticated Wear companion IPC service.",
        "Version" to project.version.toString(),
        "Patcher-Version" to "2.0.1",
    )
}

val dexDirectory = layout.buildDirectory.dir("patch-dex")
val compilePatchesDex by tasks.registering(JavaExec::class) {
    dependsOn(tasks.jar)
    mainClass.set("com.android.tools.r8.D8")
    classpath = d8
    inputs.file(tasks.jar.flatMap { it.archiveFile })
    inputs.files(androidJar, providedMorphe)
    outputs.dir(dexDirectory)
    doFirst {
        check(sdk.isPresent) { "Set ANDROID_HOME to an Android SDK with android-35" }
        dexDirectory.get().asFile.mkdirs()
        args(
            "--release", "--min-api", "26",
            "--lib", androidJar.get(),
            "--classpath", morpheCli.get().asFile.absolutePath,
            "--output", dexDirectory.get().asFile.absolutePath,
            tasks.jar.get().archiveFile.get().asFile.absolutePath,
        )
    }
}

// The .mpp is rebuilt from the final patch jar so the JVM classes and the DEX
// come from exactly the same sources.
val packageMpp by tasks.registering(Zip::class) {
    dependsOn(compilePatchesDex)
    archiveFileName.set("${project.name}-${project.version}.mpp")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    from(tasks.jar.map { zipTree(it.archiveFile) })
    from(dexDirectory) { include("classes*.dex") }
}
tasks.assemble { dependsOn(packageMpp) }

val inputApk = providers.gradleProperty("inputApk").map { file(it) }
    .orElse(provider { file("../work/com.openbubbles.messaging.apk") })
val stockApkSha256 = "a382a9cbef6a83288f11ed8c4763b56c5fedb24ee6505cd21b6bbb545cdc6cc0"
val verifyInputApk by tasks.registering {
    inputs.file(inputApk)
    doLast {
        verifyPinnedFile(inputApk.get(), stockApkSha256)
    }
}

val patchedApk = layout.buildDirectory.file("patched/openbubbles-wear-unsigned.apk")
val patchApk by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks the stock APK SHA-256 and applies the patch locally, unsigned; never installs."
    dependsOn(packageMpp, tasks.test, verifyInputApk)
    classpath = providedMorphe
    mainClass.set("app.morphe.MorpheLauncherKt")
    maxHeapSize = "2g"
    // Morphe's desktop launcher is built for Java 21; the build itself can run on 17.
    providers.gradleProperty("morpheJava").orNull?.let { executable = file("$it/bin/java.exe").absolutePath }
    doFirst {
        args(
            "patch", "--patches", packageMpp.get().archiveFile.get().asFile.absolutePath,
            "--out", patchedApk.get().asFile.absolutePath,
            "--temporary-files-path", layout.buildDirectory.dir("patch-work").get().asFile.absolutePath,
            "--unsigned", "--validate", inputApk.get().absolutePath,
        )
    }
}

// The supplied base requires both base__abi and base__density. Keep a complete
// split set instead of pretending a library-only ZIP merge is a standalone APK.
val arm64Apk = providers.gradleProperty("inputArm64Split").map { file(it) }
    .orElse(provider { file("../work/config.arm64_v8a.apk") })
val inputXapk = providers.gradleProperty("inputXapk").map { file(it) }
    .orElse(provider { file("../OpenBubbles_2.0.0_APKPure.xapk") })
val splitHashes = linkedMapOf(
    "config.arm64_v8a.apk" to "5559a7b606837833a674bcd341cd72ef47b16446d3b751843148e1a3e3e6dfe2",
    "config.en.apk" to "2ff1ddcce4d3d48135a4d720fbc59ccca25cf2d630b44516c53ab73743e67389",
    "config.mdpi.apk" to "e8c9aa39865d03aa055d267fe7b18175c17244562285b3895d1da2bb6edb7464",
)
val packagingInputs = layout.buildDirectory.dir("packaging-inputs")
val installableDirectory = layout.buildDirectory.dir("installable")
val releaseProperties = file("../keystore.properties")
val buildTools = sdk.map { file("$it/build-tools/35.0.0") }
val javaBinary = File(System.getProperty("java.home"), "bin/java.exe").absolutePath

fun androidTool(name: String, vararg args: String): List<String> {
    check(buildTools.isPresent) { "Set ANDROID_HOME to an Android SDK with build-tools 35.0.0" }
    val tool = File(buildTools.get(), if (name == "apksigner") "lib/apksigner.jar" else "$name.exe")
    check(tool.isFile) { "Missing Android build-tools 35.0.0: ${tool.name}" }
    return (if (name == "apksigner") listOf(javaBinary, "-jar", tool.absolutePath) else listOf(tool.absolutePath)) + args
}

fun runPackagingTool(command: List<String>, secrets: Map<String, String> = emptyMap()): String {
    val process = ProcessBuilder(command).redirectErrorStream(true).apply { environment().putAll(secrets) }.start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    check(process.waitFor() == 0) {
        // Signing diagnostics must not expose the local signing configuration.
        if (secrets.isNotEmpty()) "APK signing failed; check the local release key configuration"
        else "Android packaging tool failed: $output"
    }
    return output
}

fun releaseSigning(propertiesFile: File): Properties {
    check(propertiesFile.isFile) { "Missing keystore.properties; release signing is required (no debug/unsigned fallback)" }
    val properties = Properties().apply { propertiesFile.inputStream().use { load(it) } }
    check(listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !properties.getProperty(it).isNullOrBlank() }) {
        "Incomplete keystore.properties; release signing is required"
    }
    check(File(propertiesFile.parentFile, properties.getProperty("storeFile")).isFile ||
        File(properties.getProperty("storeFile")).let { it.isAbsolute && it.isFile }) { "Release keystore is missing" }
    return properties
}

fun signingStore(properties: Properties): File = File(properties.getProperty("storeFile")).let {
    if (it.isAbsolute) it else File(releaseProperties.parentFile, it.path)
}

fun releaseCertificate(properties: Properties): String = try {
    val store = KeyStore.getInstance(signingStore(properties), properties.getProperty("storePassword").toCharArray())
    val alias = properties.getProperty("keyAlias")
    check(store.isKeyEntry(alias) && store.getKey(alias, properties.getProperty("keyPassword").toCharArray()) != null)
    MessageDigest.getInstance("SHA-256").digest(store.getCertificate(alias).encoded).hex()
} catch (_: Exception) {
    throw GradleException("Cannot open the configured release signing key; no key is created or replaced")
}

val verifySigningKey by tasks.registering {
    group = "verification"
    description = "Fails closed if the existing local release signing key is unavailable."
    doLast { releaseCertificate(releaseSigning(releaseProperties)) }
}

val verifyPackagingInputs by tasks.registering {
    group = "verification"
    description = "Checks pinned stock base, ARM64 split, XAPK and resource split inputs; never patches or installs."
    dependsOn(verifyInputApk)
    doLast {
        verifyPinnedFile(arm64Apk.get(), splitHashes.getValue("config.arm64_v8a.apk"))
        verifyPinnedFile(inputXapk.get(), "4a7101bf9c8a854ca9d82da1605a2df785e3133e27603b479fcbae87335e7938")
        packagingInputs.get().asFile.mkdirs()
        ZipFile(inputXapk.get()).use { zip ->
            splitHashes.filterKeys { it != "config.arm64_v8a.apk" }.forEach { (name, hash) ->
                val entry = checkNotNull(zip.getEntry(name)) { "Missing XAPK member: $name" }
                val output = File(packagingInputs.get().asFile, name)
                zip.getInputStream(entry).use { input -> output.outputStream().use(input::copyTo) }
                verifyPinnedFile(output, hash)
            }
        }
        check(nativeLibraries(arm64Apk.get()).size == 9) { "Expected all nine supplied ARM64 native libraries" }
        check(runPackagingTool(androidTool("aapt2", "dump", "xmltree", inputApk.get().absolutePath,
            "--file", "AndroidManifest.xml")).contains("base__abi,base__density")) { "Stock split requirements changed" }
    }
}

fun installableSources(): Map<String, File> = linkedMapOf(
    "openbubbles-wear-patched-base.apk" to patchedApk.get().asFile,
    "config.arm64_v8a.apk" to arm64Apk.get(),
    "config.en.apk" to File(packagingInputs.get().asFile, "config.en.apk"),
    "config.mdpi.apk" to File(packagingInputs.get().asFile, "config.mdpi.apk"),
)

fun verifyApkSet(directory: File, certificate: String) {
    installableSources().forEach { (name, source) ->
        val apk = File(directory, name)
        check(apk.isFile) { "Missing installable split: $name" }
        val badging = runPackagingTool(androidTool("aapt2", "dump", "badging", apk.absolutePath))
        check(badging.contains("name='com.openbubbles.messaging' versionCode='20002289'")) { "Package/version mismatch: $name" }
        val expectedSplit = if (name.startsWith("config.")) name.removeSuffix(".apk") else null
        check(if (expectedSplit == null) !badging.lineSequence().first().contains(" split=")
            else badging.contains("split='$expectedSplit'")) { "Split identity mismatch: $name" }
        runPackagingTool(androidTool("zipalign", "-c", "-P", "16", "4", apk.absolutePath))
        val signatures = runPackagingTool(androidTool("apksigner", "verify", "--verbose", "--print-certs", apk.absolutePath))
        verifyReleaseSigner(signatures, certificate)
        check(apkPayload(apk) == apkPayload(source)) { "Signing changed APK payload: $name" }
    }
    val manifest = runPackagingTool(androidTool("aapt2", "dump", "xmltree",
        File(directory, "openbubbles-wear-patched-base.apk").absolutePath, "--file", "AndroidManifest.xml"))
    check(manifest.contains("com.openbubbles.wear.patch.CompanionService")) { "Refusing to package an unpatched base" }
    check(manifest.contains("base__abi,base__density")) { "Patched split requirements changed" }
    check(nativeLibraries(File(directory, "config.arm64_v8a.apk")) == nativeLibraries(arm64Apk.get())) {
        "Native library payload is incomplete or changed"
    }
}

val packageInstallableApks by tasks.registering {
    group = "build"
    description = "Patches, aligns, release-signs and verifies a complete ARM64 APK set; never installs."
    dependsOn(verifySigningKey, verifyPackagingInputs, patchApk)
    doLast {
        val properties = releaseSigning(releaseProperties)
        val certificate = releaseCertificate(properties)
        val base = patchedApk.get().asFile
        check(base.isFile && base.sha256() != stockApkSha256) { "Refusing to sign the stock base APK" }
        check(runPackagingTool(androidTool("aapt2", "dump", "xmltree", base.absolutePath, "--file", "AndroidManifest.xml"))
            .contains("com.openbubbles.wear.patch.CompanionService")) { "Refusing to sign an unpatched base APK" }
        val staging = layout.buildDirectory.dir("installable-staging").get().asFile.apply { mkdirs() }
        installableSources().forEach { (name, source) ->
            val aligned = File(staging, "aligned-$name")
            runPackagingTool(androidTool("zipalign", "-f", "-P", "16", "4", source.absolutePath, aligned.absolutePath))
            runPackagingTool(androidTool("apksigner", "sign", "--ks", signingStore(properties).absolutePath,
                "--ks-key-alias", properties.getProperty("keyAlias"), "--ks-pass", "env:OB_STORE_PASSWORD",
                "--key-pass", "env:OB_KEY_PASSWORD", "--v4-signing-enabled", "false",
                "--out", File(staging, name).absolutePath, aligned.absolutePath), mapOf(
                "OB_STORE_PASSWORD" to properties.getProperty("storePassword"),
                "OB_KEY_PASSWORD" to properties.getProperty("keyPassword"),
            ))
        }
        verifyApkSet(staging, certificate)
        val output = installableDirectory.get().asFile.apply { mkdirs() }
        installableSources().keys.forEach { File(staging, it).copyTo(File(output, it), overwrite = true) }
        File(output, "SHA256SUMS").writeText(installableSources().keys.joinToString("\n", postfix = "\n") {
            "${File(output, it).sha256()}  $it"
        })
        File(output, "INSTALL.txt").writeText("""
            Complete patched OpenBubbles 2.0.0 ARM64 APK set (not standalone APKs).
            Package: com.openbubbles.messaging; Android 12L/API 32+; supplied English/density resources.
            Release certificate SHA-256: $certificate
            Stock uses a different key. If stock is installed, stop: uninstalling it yourself loses its local data.
            No task exports app data, uninstalls, installs, wipes or bypasses signature checks.
            From this directory, after you have handled the stock install yourself:
            adb -s PHONE_SERIAL install-multiple -r openbubbles-wear-patched-base.apk config.arm64_v8a.apk config.en.apk config.mdpi.apk
            Install the matching release-signed phone bridge and watch companion separately.
            Keep the same keystore.properties and keystore for every future patched update; always update all four APKs.
            Never install the base alone or mix these APKs with stock-signed splits.
        """.trimIndent() + "\n")
        logger.lifecycle("Verified ARM64 APK set: ${output.absolutePath}")
    }
}
// Fail on bad inputs/key before spending time rebuilding the patch.
patchApk { mustRunAfter(verifySigningKey, verifyPackagingInputs) }

tasks.register("verifyInstallableApks") {
    group = "verification"
    description = "Independently verifies an existing installable set against the release key and current patched/source payloads."
    dependsOn(verifySigningKey, verifyPackagingInputs)
    mustRunAfter(packageInstallableApks)
    doLast { verifyApkSet(installableDirectory.get().asFile, releaseCertificate(releaseSigning(releaseProperties))) }
}

val packagingSelfTest by tasks.registering {
    group = "verification"
    description = "Asserts fail-closed input hashes, native library presence, and missing release-key handling; no patching/signing."
    doLast {
        val fixture = layout.buildDirectory.dir("packaging-self-test").get().asFile.apply { mkdirs() }
        val input = File(fixture, "input").apply { writeText("pinned") }
        verifyPinnedFile(input, input.sha256())
        check(runCatching { verifyPinnedFile(input, "0".repeat(64)) }.isFailure)
        check(runCatching { verifyPinnedFile(File(fixture, "absent"), "0".repeat(64)) }.isFailure)
        check(runCatching { releaseSigning(File(fixture, "missing.properties")) }.isFailure)
        val emptyApk = File(fixture, "empty.apk")
        ZipOutputStream(emptyApk.outputStream()).use { }
        check(runCatching { nativeLibraries(emptyApk) }.isFailure)
        val nativeApk = File(fixture, "native.apk")
        ZipOutputStream(nativeApk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/test.so").apply {
                method = ZipEntry.STORED; size = 0; compressedSize = 0; crc = 0
            })
            zip.closeEntry()
        }
        check(nativeLibraries(nativeApk).keys == setOf("lib/arm64-v8a/test.so"))
        ZipOutputStream(nativeApk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/test.so"))
            zip.closeEntry()
        }
        check(runCatching { nativeLibraries(nativeApk) }.isFailure)
        verifyReleaseSigner("Signer #1 certificate SHA-256 digest: abcd", "abcd")
        check(runCatching { verifyReleaseSigner("Signer #1 certificate SHA-256 digest: abcd", "dcba") }.isFailure)
        check(runCatching { verifyReleaseSigner("", "abcd") }.isFailure)
        logger.lifecycle("Packaging fail-closed self-checks passed")
    }
}
tasks.check { dependsOn(packagingSelfTest) }
packageInstallableApks { dependsOn(packagingSelfTest) }

fun verifyPinnedFile(input: File, expected: String) {
    check(input.isFile) { "Missing pinned packaging input: ${input.name}" }
    check(input.sha256() == expected) { "Unsupported input SHA-256: ${input.name}; only the supplied stock 2.0.0 APK set is supported" }
}

fun nativeLibraries(apk: File): Map<String, String> = ZipFile(apk).use { zip ->
    val entries = zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("lib/") }.toList()
    check(entries.isNotEmpty() && entries.all {
        it.name.matches(Regex("lib/arm64-v8a/[^/]+\\.so")) && it.method == ZipEntry.STORED
    }) { "Missing/unexpected/compressed native libraries: ${apk.name}" }
    entries.associate { entry -> entry.name to zip.getInputStream(entry).use { MessageDigest.getInstance("SHA-256").digest(it.readBytes()).hex() } }
}

fun verifyReleaseSigner(output: String, certificate: String) {
    val signers = Regex("Signer #\\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)")
        .findAll(output).map { it.groupValues[1].lowercase() }.toList()
    check(signers == listOf(certificate)) { "APK is not signed exclusively by the configured release key" }
}

fun apkPayload(apk: File): Map<String, String> = ZipFile(apk).use { zip ->
    val entries = zip.entries().asSequence().filter { !it.isDirectory &&
        !it.name.matches(Regex("META-INF/(MANIFEST\\.MF|[^/]+\\.(SF|RSA|DSA|EC))", RegexOption.IGNORE_CASE)) &&
        it.name != "stamp-cert-sha256"
    }.toList()
    check(entries.map { it.name }.distinct().size == entries.size) { "Duplicate ZIP members: ${apk.name}" }
    entries.associate { entry -> entry.name to zip.getInputStream(entry).use { MessageDigest.getInstance("SHA-256").digest(it.readBytes()).hex() } }
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

fun File.sha256(): String = inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}
