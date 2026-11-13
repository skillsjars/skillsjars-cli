import org.jetbrains.kotlin.gradle.plugin.mpp.DisableCacheInKotlinVersion
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

plugins {
    kotlin("multiplatform") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("com.skillsjars.gradle-plugin") version "0.2.0"
}

version = providers.gradleProperty("sjm.version").getOrElse("0.0.0-dev")

// Spike variants (see SPEC.md "Spike 1"): -Psjm.engine=native|curl and -Psjm.cli=clikt-core|clikt.
// `native` is the OS HTTP stack where Ktor has one (darwin on macOS, winhttp on Windows); Linux always uses curl.
val engine = providers.gradleProperty("sjm.engine").getOrElse("native")
val cli = providers.gradleProperty("sjm.cli").getOrElse("clikt-core")
require(engine == "native" || engine == "curl") { "sjm.engine must be native or curl, was $engine" }
require(cli == "clikt-core" || cli == "clikt") { "sjm.cli must be clikt-core or clikt, was $cli" }

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
        // clikt 5.1.0's `clikt` and `clikt-core` both define Context.selfAndAncestors, so debug binaries linked
        // against Kotlin/Native's library caches fail with a duplicate symbol. Only the `clikt` variant needs this.
        if (cli == "clikt") {
            binaries.all {
                @OptIn(KotlinNativeCacheApi::class)
                disableNativeCache(
                    DisableCacheInKotlinVersion.`2_4_21`,
                    "clikt and clikt-core both define Context.selfAndAncestors",
                    uri("https://github.com/ajalt/clikt/issues"),
                )
            }
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateBuildInfo)
            kotlin.srcDir("src/cli-$cli/kotlin")
            dependencies {
                implementation("io.ktor:ktor-client-core:$ktorVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
                implementation("com.squareup.okio:okio:3.18.2")
                implementation("com.github.ajalt.clikt:$cli:5.1.0")
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
            kotlin.srcDir("src/engine-curl/kotlin")
            dependencies { implementation("io.ktor:ktor-client-curl:$ktorVersion") }
        }
        macosMain {
            kotlin.srcDir(if (engine == "curl") "src/engine-curl/kotlin" else "src/engine-darwin/kotlin")
            dependencies { implementation("io.ktor:ktor-client-${if (engine == "curl") "curl" else "darwin"}:$ktorVersion") }
        }
        mingwMain {
            kotlin.srcDir(if (engine == "curl") "src/engine-curl/kotlin" else "src/engine-winhttp/kotlin")
            dependencies { implementation("io.ktor:ktor-client-${if (engine == "curl") "curl" else "winhttp"}:$ktorVersion") }
        }
    }
}

// Real JARs for the extraction and install tests, built from src/commonTest/fixtures.
val fixtureJars = listOf("skill-jar", "empty-jar").map { name ->
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
