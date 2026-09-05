// FileExplorer v1.6.2
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.xml.parsers.DocumentBuilderFactory

private val MIN_COMPILE_SDK = 36
private val MIN_TARGET_SDK = 35
private val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

private data class ResolvedDependency(
    val group: String,
    val name: String,
    val version: String,
)

private fun Project.resolvedProductionDependencies(): Set<ResolvedDependency> {
    val configurations = subprojects.flatMap { subproject ->
        subproject.configurations.filter { configuration ->
            configuration.isCanBeResolved &&
                configuration.name.contains("ReleaseRuntimeClasspath", ignoreCase = true)
        }
    }
    check(configurations.isNotEmpty()) { "No release runtime classpath was found for dependency verification" }

    return configurations.asSequence()
        .flatMap { configuration ->
            configuration.incoming.resolutionResult.allComponents.asSequence()
        }
        .mapNotNull { component ->
            val identifier = component.id as? ModuleComponentIdentifier ?: return@mapNotNull null
            ResolvedDependency(identifier.group, identifier.module, identifier.version)
        }
        .toSet()
}

private fun androidAttribute(element: Element, name: String): String =
    element.getAttributeNS(ANDROID_NAMESPACE, name).ifBlank {
        element.getAttribute("android:$name")
    }

private fun elements(document: Document, tagName: String): List<Element> = buildList {
    val nodes = document.getElementsByTagName(tagName)
    for (index in 0 until nodes.length) {
        (nodes.item(index) as? Element)?.let(::add)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

fun resourceValues(file: File): Map<String, String> {
    val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
    val values = linkedMapOf<String, String>()
    val children = document.documentElement.childNodes
    for (index in 0 until children.length) {
        val element = children.item(index) as? Element ?: continue
        val name = element.getAttribute("name")
        if (name.isBlank()) continue
        val value = when (element.tagName) {
            "string" -> element.textContent
            "plurals" -> buildString {
                val items = element.getElementsByTagName("item")
                for (itemIndex in 0 until items.length) append(items.item(itemIndex).textContent).append('\u0000')
            }
            else -> continue
        }
        check(values.put(name, value) == null) { "Duplicate resource key '$name' in ${file.path}" }
    }
    return values
}

fun formatTokens(value: String): Set<String> = Regex("%(?:[0-9]+\\$)?[a-zA-Z]")
    .findAll(value)
    .map { it.value }
    .toSet()

val verifyAccessibilityLocalization = tasks.register("verifyAccessibilityLocalization") {
    group = "verification"
    description = "Checks localized resource parity and rejects inline user-facing UI strings."
    doLast {
        val resourceRoot = file("core/designsystem/src/main/res")
        val defaultResources = resourceRoot.resolve("values/strings.xml")
        check(defaultResources.isFile) { "Missing default string resources: ${defaultResources.path}" }
        val defaultValues = resourceValues(defaultResources)
        val failures = mutableListOf<String>()

        resourceRoot.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("values-") }
            ?.mapNotNull { it.resolve("strings.xml").takeIf(File::isFile) }
            ?.sortedBy { it.path }
            ?.forEach { localeFile ->
                val localeValues = resourceValues(localeFile)
                val extras = localeValues.keys - defaultValues.keys
                if (extras.isNotEmpty()) {
                    failures += "${localeFile.parentFile.name} has unknown keys: ${extras.sorted().joinToString()}"
                }
                val placeholderMismatches = localeValues.keys.intersect(defaultValues.keys).filter { key ->
                    formatTokens(localeValues.getValue(key)) != formatTokens(defaultValues.getValue(key))
                }
                if (placeholderMismatches.isNotEmpty()) {
                    failures += "${localeFile.parentFile.name} changes format placeholders for: ${placeholderMismatches.sorted().joinToString()}"
                }
                val missing = defaultValues.keys - localeValues.keys
                if (missing.isNotEmpty()) {
                    logger.lifecycle("${localeFile.parentFile.name}: ${missing.size} keys use the Android default-language fallback")
                }
            }

        val inlinePatterns = listOf(
            Regex("\\bText\\s*\\(\\s*\\\"([^\\\"$]*)\\\""),
            Regex("contentDescription\\s*=\\s*\\\"([^\\\"$]*)\\\""),
            Regex("Toast\\.makeText\\([^\\n]*?\\\"([^\\\"$]*)\\\""),
            Regex("\\b(?:title|subtitle)\\s*=\\s*\\\"([^\\\"$]*)\\\""),
            Regex("qsTile\\?\\.label\\s*=\\s*\\\"([^\\\"$]*)\\\""),
        )
        val sourceFiles = listOf("app/src/main/java", "core", "feature")
            .flatMap { root ->
                fileTree(root)
                    .matching { include("**/*.kt"); exclude("**/build/**", "**/src/test/**", "**/src/androidTest/**") }
                    .files
            }
        sourceFiles.forEach { source ->
            source.readLines().forEachIndexed { lineIndex, line ->
                if (line.contains("label = \"selection_") || line.contains("label = \"grid_selection_")) return@forEachIndexed
                for (pattern in inlinePatterns) {
                    val match = pattern.find(line) ?: continue
                    val literal = match.groupValues[1]
                    if (literal.isNotBlank() && !literal.startsWith("/") && literal.any(Char::isLetter)) {
                        failures += "${source.path}:${lineIndex + 1}: inline user-facing string '$literal'"
                    }
                    break
                }
            }
        }

        check(failures.isEmpty()) { failures.joinToString("\n") }
    }
}

val verifyAndroidUpgradeReadiness = tasks.register("verifyAndroidUpgradeReadiness") {
    group = "verification"
    description = "Checks Android 15/16 SDK, manifest, storage, backup, and foreground-service readiness."
    doLast {
        val failures = mutableListOf<String>()
        val buildFiles = fileTree(rootProject.projectDir).matching {
            include("**/build.gradle.kts")
            exclude("**/build/**")
        }.files.filterNot { it.canonicalFile == rootProject.file("build.gradle.kts").canonicalFile }
        buildFiles.forEach { buildFile ->
            val compileSdk = Regex("compileSdk\\s*=\\s*(\\d+)")
                .find(buildFile.readText())
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
            if (compileSdk == null || compileSdk < MIN_COMPILE_SDK) {
                failures += "${buildFile.path}: compileSdk must be at least $MIN_COMPILE_SDK"
            }
        }

        val appBuild = rootProject.file("app/build.gradle.kts").readText()
        val targetSdk = Regex("targetSdk\\s*=\\s*(\\d+)")
            .find(appBuild)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
        if (targetSdk == null || targetSdk < MIN_TARGET_SDK) {
            failures += "app/build.gradle.kts: targetSdk must be at least $MIN_TARGET_SDK"
        }

        val manifestFiles = fileTree(rootProject.projectDir).matching {
            include("**/src/main/AndroidManifest.xml")
            include("**/src/androidTest/AndroidManifest.xml")
            exclude("**/build/**")
        }.files
        var appManifest: Document? = null
        var foregroundServiceCount = 0
        manifestFiles.forEach { manifestFile ->
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile)
            if (manifestFile.toPath().toString().replace('\\', '/') ==
                rootProject.file("app/src/main/AndroidManifest.xml").toPath().toString().replace('\\', '/')
            ) {
                appManifest = document
            }
            listOf("activity", "activity-alias", "service", "receiver", "provider")
                .flatMap { elements(document, it) }
                .forEach { component ->
                    val exported = androidAttribute(component, "exported")
                    val hasIntentFilter = elements(component.ownerDocument, "intent-filter")
                        .any { filter -> filter.parentNode == component }
                    if (hasIntentFilter && exported.isBlank()) {
                        failures += "${manifestFile.path}: ${component.tagName} with an intent-filter must declare android:exported"
                    }
                    if (component.tagName == "provider" && exported == "true" &&
                        androidAttribute(component, "permission").isBlank() &&
                        androidAttribute(component, "grantUriPermissions") != "true"
                    ) {
                        failures += "${manifestFile.path}: exported provider needs a permission or URI grants"
                    }
                    if (component.tagName == "service" &&
                        androidAttribute(component, "foregroundServiceType").isNotBlank()
                    ) {
                        foregroundServiceCount++
                    }
                }
        }

        val mainManifest = appManifest
        if (mainManifest == null) {
            failures += "app/src/main/AndroidManifest.xml is missing"
        } else {
            val application = elements(mainManifest, "application").firstOrNull()
            if (application == null || androidAttribute(application, "enableOnBackInvokedCallback") != "true") {
                failures += "app/src/main/AndroidManifest.xml: predictive-back compatibility must be enabled"
            }
            val permissions = elements(mainManifest, "uses-permission")
                .map { androidAttribute(it, "name") }
                .toSet()
            setOf(
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.MANAGE_EXTERNAL_STORAGE",
                "android.permission.QUERY_ALL_PACKAGES",
            ).forEach { permission ->
                if (permission !in permissions) failures += "app manifest is missing $permission"
            }
            if (foregroundServiceCount > 0 && "android.permission.FOREGROUND_SERVICE" !in permissions) {
                failures += "foreground services require android.permission.FOREGROUND_SERVICE"
            }
            if (application == null || androidAttribute(application, "allowBackup") != "true" ||
                androidAttribute(application, "fullBackupContent").isBlank() ||
                androidAttribute(application, "dataExtractionRules").isBlank()
            ) {
                failures += "app manifest must declare backup and data-extraction rules"
            }

            val queries = elements(mainManifest, "queries").firstOrNull()
            val queryActions = queries?.getElementsByTagName("action")
                ?.let { nodes -> (0 until nodes.length).mapNotNull { nodes.item(it) as? Element } }
                ?.map { androidAttribute(it, "name") }
                .orEmpty()
            val queryCategories = queries?.getElementsByTagName("category")
                ?.let { nodes -> (0 until nodes.length).mapNotNull { nodes.item(it) as? Element } }
                ?.map { androidAttribute(it, "name") }
                .orEmpty()
            if ("android.intent.action.MAIN" !in queryActions ||
                "android.intent.category.LAUNCHER" !in queryCategories
            ) {
                failures += "app manifest must declare a launcher <queries> fallback for App Manager"
            }
        }

        listOf(
            rootProject.file("app/src/main/res/xml/backup_rules.xml"),
            rootProject.file("app/src/main/res/xml/data_extraction_rules.xml"),
        ).forEach { rulesFile ->
            if (!rulesFile.isFile) failures += "missing backup policy ${rulesFile.path}"
        }

        val permissionHelper = rootProject.file(
            "core/storage/src/main/java/com/explorer/fileexplorer/core/storage/PermissionHelper.kt",
        ).readText()
        if (!permissionHelper.contains("Environment.isExternalStorageManager()") ||
            !permissionHelper.contains("WRITE_EXTERNAL_STORAGE")
        ) {
            failures += "PermissionHelper must retain all-files and legacy storage fallbacks"
        }

        val appManagerSource = rootProject.file(
            "feature/apps/src/main/java/com/explorer/fileexplorer/feature/apps/AppsScreen.kt",
        ).readText()
        if (!appManagerSource.contains("QUERY_ALL_PACKAGES") ||
            !appManagerSource.contains("queryIntentActivities")
        ) {
            failures += "App Manager must provide a least-privilege package visibility fallback"
        }

        val permissionsMatrix = rootProject.file("README.md").readText()
        listOf(
            "Permission or component",
            "API range",
            "Backup implication",
            "Owner review",
            "FileDocumentsProvider",
        ).forEach { requiredText ->
            if (requiredText !in permissionsMatrix) {
                failures += "README.md permission matrix is missing '$requiredText'"
            }
        }

        listOf(
            rootProject.file("feature/transfer/src/main/java/com/explorer/fileexplorer/feature/transfer/TransferService.kt"),
            rootProject.file("feature/network/src/main/java/com/explorer/fileexplorer/feature/network/ShareServerService.kt"),
        ).forEach { serviceFile ->
            if (!serviceFile.readText().contains("override fun onTimeout")) {
                failures += "${serviceFile.path}: dataSync foreground service must handle Android 15 timeout"
            }
        }

        check(failures.isEmpty()) { failures.joinToString("\n") }
        logger.lifecycle(
            "Android upgrade readiness passed: compileSdk >= $MIN_COMPILE_SDK, targetSdk >= $MIN_TARGET_SDK, " +
                "$foregroundServiceCount foreground-service declarations checked",
        )
    }
}

val verifyDependencyProvenance = tasks.register("verifyDependencyProvenance") {
    group = "verification"
    description = "Checks fixed dependency versions, repository policy, and the resolved production graph."
    doLast {
        val failures = mutableListOf<String>()
        val settingsText = rootProject.file("settings.gradle.kts").readText()
        listOf("FAIL_ON_PROJECT_REPOS", "google", "mavenCentral", "https://jitpack.io", "exclusiveContent")
            .filterNot(settingsText::contains)
            .forEach { failures += "settings.gradle.kts is missing explicit repository policy: $it" }

        val dynamicVersion = Regex("(?i)(?:\\+|snapshot|latest|(?:\\[[^]]+]|\\([^)]*\\)))")
        val catalogText = rootProject.file("gradle/libs.versions.toml").readText()
        val versionsSection = Regex("(?ms)^\\[versions\\]\\s*(.*?)(?=^\\[|\\z)")
            .find(catalogText)
            ?.groupValues
            ?.get(1)
            .orEmpty()
        versionsSection.lineSequence()
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            .forEach { line ->
                val version = Regex("=\\s*\\\"([^\\\"]+)\\\"").find(line)?.groupValues?.get(1)
                if (version == null || dynamicVersion.containsMatchIn(version)) {
                    failures += "version catalog has a non-fixed version: $line"
                }
            }

        val librariesSection = Regex("(?ms)^\\[libraries\\]\\s*(.*?)(?=^\\[|\\z)")
            .find(catalogText)
            ?.groupValues
            ?.get(1)
            .orEmpty()
        librariesSection.lineSequence()
            .filter { it.contains("= {") && !it.trimStart().startsWith("#") }
            .filterNot { it.contains("version.ref") || it.contains("version =") }
            .forEach { line ->
                if (!line.trimStart().startsWith("compose-")) {
                    failures += "library must declare a version or version.ref: $line"
                }
            }

        val directDependency = Regex(
            "(?:implementation|api|compileOnly|runtimeOnly|ksp|testImplementation|androidTestImplementation|" +
                "debugImplementation|releaseImplementation)\\(\\\"([^\\\"]+)\\\"\\)",
        )
        fileTree(rootProject.projectDir).matching {
            include("**/*.gradle.kts")
            exclude("**/build/**")
        }.files.forEach { buildFile ->
            directDependency.findAll(buildFile.readText()).forEach { match ->
                val notation = match.groupValues[1]
                val parts = notation.split(':')
                if (parts.size >= 3) {
                    if (parts[2].isBlank() || dynamicVersion.containsMatchIn(parts[2])) {
                        failures += "${buildFile.path}: dependency is not fixed: $notation"
                    }
                } else if (parts.size == 2 && !notation.startsWith("androidx.compose.ui:")) {
                    failures += "${buildFile.path}: dependency needs an explicit version or version catalog alias: $notation"
                }
            }
        }

        val resolved = rootProject.resolvedProductionDependencies()
        resolved.forEach { dependency ->
            if (dependency.version.isBlank() || dynamicVersion.containsMatchIn(dependency.version)) {
                failures += "resolved production dependency is not fixed: ${dependency.group}:${dependency.name}:${dependency.version}"
            }
        }
        check(failures.isEmpty()) { failures.joinToString("\n") }
        logger.lifecycle("Dependency provenance passed for ${resolved.size} resolved production modules")
        resolved.sortedWith(compareBy(ResolvedDependency::group, ResolvedDependency::name, ResolvedDependency::version))
            .forEach { dependency ->
                logger.lifecycle("  ${dependency.group}:${dependency.name}:${dependency.version}")
            }
    }
}

val verifyExportedSurfaceSmoke = tasks.register("verifyExportedSurfaceSmoke") {
    group = "verification"
    description = "Checks the manifest contract for exported Android and system-facing surfaces."
    doLast {
        val manifestFile = rootProject.file("app/src/main/AndroidManifest.xml")
        check(manifestFile.isFile) { "Missing application manifest: ${manifestFile.path}" }
        val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile)
        val components = listOf("activity", "service", "provider").flatMap { elements(manifest, it) }
        fun component(suffix: String): Element = components.firstOrNull {
            androidAttribute(it, "name").endsWith(suffix)
        } ?: error("Manifest is missing $suffix")

        check(androidAttribute(component("MainActivity"), "exported") == "true") {
            "MainActivity must remain an exported launcher"
        }
        val tile = component("ShareServerTileService")
        check(androidAttribute(tile, "exported") == "true" &&
            androidAttribute(tile, "permission") == "android.permission.BIND_QUICK_SETTINGS_TILE"
        ) { "Quick Settings tile must be explicitly exported with its binding permission" }
        listOf("TransferService", "ShareServerService").forEach { suffix ->
            check(androidAttribute(component(suffix), "exported") == "false") {
                "$suffix must remain internal"
            }
        }
        val fileProvider = component("androidx.core.content.FileProvider")
        check(androidAttribute(fileProvider, "exported") == "false" &&
            androidAttribute(fileProvider, "grantUriPermissions") == "true"
        ) { "FileProvider must be private and grant URI permissions explicitly" }
        val documentsProvider = component("FileDocumentsProvider")
        check(androidAttribute(documentsProvider, "exported") == "true" &&
            androidAttribute(documentsProvider, "permission") == "android.permission.MANAGE_DOCUMENTS" &&
            androidAttribute(documentsProvider, "grantUriPermissions") == "true"
        ) { "DocumentsProvider must expose only the managed SAF surface" }

        val application = elements(manifest, "application").single()
        check(
            elements(application.ownerDocument, "meta-data").any { metadata ->
                metadata.parentNode == application &&
                    androidAttribute(metadata, "name") ==
                    "com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME"
            },
        ) { "Cast options provider metadata is missing" }
        logger.lifecycle("Exported surface manifest smoke passed for ${components.size} components")
    }
}

val releaseSmoke = tasks.register("releaseSmoke") {
    group = "verification"
    description = "Builds and installs the debug artifact, then runs exported-surface and API-level smoke tests."
    dependsOn(
        verifyExportedSurfaceSmoke,
        verifyAccessibilityLocalization,
        verifyAndroidUpgradeReadiness,
        ":app:assembleDebug",
        ":app:connectedDebugAndroidTest",
    )
    doLast {
        val artifact = rootProject.file("app/build/outputs/apk/debug/app-debug.apk")
        check(artifact.isFile && artifact.length() > 0L) {
            "Debug smoke artifact is missing or empty: ${artifact.path}"
        }
        logger.lifecycle("Release smoke passed with ${artifact.length()}-byte debug APK")
    }
}

val scanDependencyAdvisories = tasks.register("scanDependencyAdvisories") {
    group = "verification"
    description = "Scans the resolved production Maven graph against OSV and fails on untriaged advisories."
    doLast {
        val dependencies = rootProject.resolvedProductionDependencies().sortedWith(
            compareBy(ResolvedDependency::group, ResolvedDependency::name, ResolvedDependency::version),
        )
        val queries = dependencies.map { dependency ->
            mapOf(
                "package" to mapOf(
                    "ecosystem" to "Maven",
                    "name" to "${dependency.group}:${dependency.name}",
                ),
                "version" to dependency.version,
            )
        }
        val request = HttpRequest.newBuilder(URI("https://api.osv.dev/v1/querybatch"))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(mapOf("queries" to queries))))
            .build()
        val response = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build()
            .send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) {
            "OSV query failed with HTTP ${response.statusCode()}: ${response.body().take(500)}"
        }

        @Suppress("UNCHECKED_CAST")
        val results = ((JsonSlurper().parseText(response.body()) as Map<*, *>) ["results"] as? List<*>)
            ?: emptyList<Any?>()
        val allowlisted = providers.gradleProperty("osvAllowlist").orNull
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toSet()
            ?: emptySet()
        val findings = results.flatMapIndexed { index, result ->
            val vulnerabilities = (result as? Map<*, *>)?.get("vulns") as? List<*> ?: emptyList<Any?>()
            vulnerabilities.mapNotNull { vulnerability ->
                val record = vulnerability as? Map<*, *> ?: return@mapNotNull null
                val id = record["id"]?.toString() ?: return@mapNotNull null
                if (id in allowlisted) return@mapNotNull null
                val dependency = dependencies.getOrNull(index) ?: return@mapNotNull null
                "${dependency.group}:${dependency.name}:${dependency.version} -> $id " +
                    record["summary"].toString().takeIf { it != "null" }.orEmpty()
            }
        }
        check(findings.isEmpty()) {
            "Untriaged OSV advisories found:\n${findings.joinToString("\n")}" +
                "\nUse -PosvAllowlist=OSV-ID,... only with a documented release decision."
        }
        logger.lifecycle("OSV advisory scan passed for ${dependencies.size} resolved production modules")
    }
}

subprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(verifyAccessibilityLocalization, verifyAndroidUpgradeReadiness, verifyDependencyProvenance)
    }
}
