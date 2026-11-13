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
            dependencies { implementation("io.ktor:ktor-client-curl:$ktorVersion") }
        }
        // The OS HTTP stacks: smaller and faster to start than curl (SPEC.md "Spike 1 results"), and they use the
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
