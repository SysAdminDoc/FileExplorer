// FileExplorer v1.5.0 — All Phases + Advanced Features
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

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

subprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(verifyAccessibilityLocalization)
    }
}
