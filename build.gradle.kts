import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.intellij.platform.grammarkit")
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3.6.1")
        testFramework(TestFrameworkType.Platform)

        // No plugin dependencies: only platform modules (runs in every IntelliJ-based IDE). The spell checker is a
        // module of the platform (optional dependency com.intellij.modules.spellchecker in plugin.xml).
        bundledModule("intellij.spellchecker")

        // IntelliJ Plugin Verifier for the task verifyPlugin (also run by the CI workflow)
        pluginVerifier()
    }
}

// Plugin Verifier: by default only against the target IDE (already downloaded); with
// -PverifyRecommendedIdes=true (CI) against all IDE versions recommended for the since/until build
// (several downloads of about 1.5 GB each).
intellijPlatform {
    pluginVerification {
        ides {
            val otherIdes = providers.gradleProperty("verifyOtherIdes").orNull
            if (providers.gradleProperty("verifyRecommendedIdes").orNull == "true") {
                recommended()
            } else if (otherIdes != null) {
                // e.g. -PverifyOtherIdes=WS,PY: other IntelliJ-based IDEs of the target version (product codes)
                for (code in otherIdes.split(",")) {
                    create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.fromCode(code.trim()), "2025.3")
                }
            } else {
                current()
            }
        }
    }
}

// Local builds: ./gradlew buildPlugin -Plocal appends a build stamp to the version in gradle.properties, e.g.
// 2.0.0.20261117.1530 (date and time as two numbers, each within the int range of the IDE's version comparison).
// The IDE then sees it as newer than the release 2.0.0 and older than the next release 2.0.1, so updates from the
// Marketplace keep working. project.version stays unchanged (the change notes are taken from its CHANGELOG
// section); only the version in plugin.xml and the name of the ZIP file get the stamp. A ValueSource is evaluated
// on every build, so the configuration cache does not reuse an old stamp.
abstract class BuildStamp : ValueSource<String, ValueSourceParameters.None> {
    override fun obtain(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd.HHmm"))
}

val localBuild = providers.gradleProperty("local").isPresent
val pluginVersion: String = if (localBuild) {
    "${project.version}.${providers.of(BuildStamp::class) {}.get()}"
} else {
    project.version.toString()
}

intellijPlatform {
    pluginConfiguration {
        version = pluginVersion
    }
}

sourceSets {
    main {
        java {
            srcDirs("src/main/gen")

        }
    }
}

tasks {
    // 1. Grammar-Kit Parser & PSI Generierung automatisieren
    generateParser {
        sourceFile.set(file("src/main/grammar/Ahk.bnf"))
        targetRootOutputDir.set(file("src/main/gen"))
        pathToParser.set("/de/kumpewo/autohotkey/parser/AhkParser.java")
        pathToPsiRoot.set("/de/kumpewo/autohotkey/psi")
        purgeOldFiles.set(false)
    }

    // 2. JFlex Lexer Generierung automatisieren
    generateLexer {
        sourceFile.set(file("src/main/grammar/Ahk.flex"))
        targetRootOutputDir.set(file("src/main/gen"))
        purgeOldFiles.set(false)
    }

    // 3. Beide Tasks vor das Kompilieren schalten
    compileJava {
        dependsOn(generateParser, generateLexer)
    }
    compileKotlin {
        dependsOn(generateParser, generateLexer)
    }

    // the ZIP of a local build carries the stamp, so it never replaces the ZIP of a release
    buildPlugin {
        archiveVersion = pluginVersion
    }

}
