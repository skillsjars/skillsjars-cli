import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

plugins {
    kotlin("multiplatform") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("com.skillsjars.gradle-plugin") version "0.2.0"
    id("com.palantir.git-version") version "5.2.0"
}

// From `git describe`: tag v0.1.0 gives 0.1.0; other commits give e.g. 0.1.0-3-gabc1234 (or a bare hash before the
// first tag), with `.dirty` for uncommitted changes. Only exact release versions trigger the update notice.
val gitVersion: groovy.lang.Closure<String> by extra
version = gitVersion().removePrefix("v")


val ktorVersion = "3.6.1"

skillsjars {
    outputDir.set(layout.projectDirectory.dir(".kiro/skills"))
}

dependencies {
    skill("com.jamesward:skills:0.0.27")
}

val generateBuildInfo = tasks.register("generateBuildInfo") {
    val out = layout.buildDirectory.dir("generated/buildinfo/kotlin")
    val v = version.toString()
    inputs.property("version", v)
    outputs.dir(out)
    doLast {
        val file = out.get().file("sjm/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package sjm\n\nconst val SJM_VERSION: String = \"$v\"\n")
    }
}

// Mozilla's CA bundle (https://curl.se/ca/cacert.pem), compiled into the Linux binaries as a last resort for systems
// with no CA store, such as bare Debian and Ubuntu containers. Only the PEM blocks are kept.
val generateCaBundle = tasks.register("generateCaBundle") {
    val pem = layout.projectDirectory.file("certs/cacert.pem")
    val out = layout.buildDirectory.dir("generated/cabundle/kotlin")
    inputs.file(pem)
    outputs.dir(out)
    doLast {
        val blocks = Regex("-----BEGIN CERTIFICATE-----[\\s\\S]+?-----END CERTIFICATE-----")
            .findAll(pem.asFile.readText())
            .map { it.value + "\n" }
            .toList()
        check(blocks.size > 100) { "certs/cacert.pem has only ${blocks.size} certificates" }
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(blocks.joinToString("").toByteArray())
            .joinToString("") { "%02x".format(it) }
        // One string constant per certificate keeps each literal well under the compiler's limits.
        val literals = blocks.joinToString(",\n") { "    \"\"\"" + it + "\"\"\"" }
        val file = out.get().file("sjm/EmbeddedCaBundle.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package sjm\n\n" +
                "/** Generated from certs/cacert.pem. */\n" +
                "internal const val EMBEDDED_CA_BUNDLE_SHA256: String = \"$sha256\"\n\n" +
                "internal val embeddedCaBundle: String = listOf(\n$literals,\n).joinToString(\"\")\n",
        )
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
        optIn.add("kotlinx.cinterop.ExperimentalForeignApi")
    }

    linuxX64()
    linuxArm64()
    macosArm64()
    mingwX64()

    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.executable {
            baseName = "sjm"
            entryPoint = "sjm.main"
        }
        // platform.posix's def links -lcrypt, -lutil, -lresolv, and -lrt. Nothing uses them, and libcrypt.so.1 is
        // missing from default Fedora/Arch installs, so drop the unused ones.
        if (konanTarget.family == org.jetbrains.kotlin.konan.target.Family.LINUX) {
            binaries.all { linkerOpts("--as-needed") }
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateBuildInfo)
            dependencies {
                implementation("io.ktor:ktor-client-core:$ktorVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
                implementation("com.squareup.okio:okio:3.18.2")
                implementation("com.github.ajalt.clikt:clikt-core:5.1.0")
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation("io.ktor:ktor-client-mock:$ktorVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            }
        }
        linuxMain {
            kotlin.srcDir(generateCaBundle)
            dependencies { implementation("io.ktor:ktor-client-curl:$ktorVersion") }
        }
        // The OS HTTP stacks: smaller and faster to start than curl (SPEC.md "HTTP engines"), and they use the
        // OS's certificate store.
        macosMain {
            dependencies { implementation("io.ktor:ktor-client-darwin:$ktorVersion") }
        }
        mingwMain {
            dependencies { implementation("io.ktor:ktor-client-winhttp:$ktorVersion") }
        }
    }
}

// Real JARs for the extraction and install tests, built from src/commonTest/fixtures.
val fixtureJars = listOf("skill-jar", "empty-jar", "needy-jar").map { name ->
    tasks.register<Zip>("fixture-$name") {
        from("src/commonTest/fixtures/$name")
        archiveFileName = "$name.jar"
        destinationDirectory = layout.buildDirectory.dir("fixtures")
    }
}

tasks.withType<KotlinNativeTest>().configureEach {
    dependsOn(fixtureJars)
    environment("SJM_TEST_FIXTURES", layout.buildDirectory.dir("fixtures").get().asFile.absolutePath)
}
