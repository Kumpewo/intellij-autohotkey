import org.jetbrains.intellij.platform.gradle.TestFrameworkType

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

}
