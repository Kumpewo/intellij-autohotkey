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

/**
 * Local plugin repository for local builds: copies the plugin ZIP into a fixed folder outside of `build/` (so
 * `./gradlew clean` keeps it), removes older ZIPs there and writes `updatePlugins.xml` (the format of a custom
 * plugin repository) with plugin ID, version, since/until build taken from the patched `plugin.xml`. An IDE that
 * lists `file:///<folder>/updatePlugins.xml` under *Settings > Plugins > Manage Plugin Repositories* then offers
 * each local build as an ordinary plugin update.
 */
abstract class LocalPluginRepository : DefaultTask() {
    /** The plugin ZIP produced by `buildPlugin`. */
    @get:InputFile
    abstract val pluginZip: RegularFileProperty

    /** The patched `plugin.xml` (source of ID, version and compatible builds). */
    @get:InputFile
    abstract val pluginXml: RegularFileProperty

    /** The repository folder that receives the ZIP and `updatePlugins.xml`. */
    @get:OutputDirectory
    abstract val repositoryDir: DirectoryProperty

    /** Copies the ZIP, deletes older ZIPs and writes `updatePlugins.xml`. */
    @TaskAction
    fun publish() {
        val dir = repositoryDir.get().asFile
        val zip = pluginZip.get().asFile
        dir.listFiles { f -> f.name.endsWith(".zip") && f.name != zip.name }?.forEach { it.delete() }
        val target = File(dir, zip.name)
        zip.copyTo(target, overwrite = true)

        val xml = pluginXml.get().asFile.readText()
        fun find(regex: String): String? = Regex(regex).find(xml)?.groupValues?.get(1)
        val id = find("<id>\\s*([^<\\s]+)\\s*</id>") ?: throw GradleException("No <id> in ${pluginXml.get()}")
        val version = find("<version>\\s*([^<\\s]+)\\s*</version>")
            ?: throw GradleException("No <version> in ${pluginXml.get()}")
        val since = find("since-build=\"([^\"]*)\"")
        val until = find("until-build=\"([^\"]*)\"")
        val ideaVersion = listOfNotNull(
            since?.let { "since-build=\"$it\"" },
            until?.let { "until-build=\"$it\"" }
        ).joinToString(" ")

        val url = target.toPath().toUri().toString()
        File(dir, "updatePlugins.xml").writeText(
            """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<plugins>
            |  <plugin id="$id" url="$url" version="$version">
            |    <idea-version $ideaVersion/>
            |  </plugin>
            |</plugins>
            |""".trimMargin()
        )
        logger.lifecycle("Local plugin repository: ${File(dir, "updatePlugins.xml").toPath().toUri()}")
    }
}

// Java 21 for compiling, instrumenting and testing, whatever JDK runs Gradle (JAVA_HOME); found among the installed
// JDKs, otherwise downloaded by the foojay resolver (settings.gradle.kts). The CI also uses Java 21.
kotlin {
    jvmToolchain(21)
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
        if (localBuild) {
            finalizedBy("localPluginRepository")
        }
    }

    // ./gradlew buildPlugin -Plocal also updates the local plugin repository in local-repo/ (see LocalPluginRepository)
    register<LocalPluginRepository>("localPluginRepository") {
        description = "Copies the plugin ZIP to local-repo/ and writes updatePlugins.xml for a custom plugin repository."
        pluginZip = buildPlugin.flatMap { it.archiveFile }
        pluginXml = patchPluginXml.flatMap { it.outputFile }
        repositoryDir = layout.projectDirectory.dir("local-repo")
    }

}
