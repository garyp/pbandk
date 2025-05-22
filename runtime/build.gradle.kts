import kotlinx.validation.ExperimentalBCVApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    `maven-publish`
    signing
    alias(libs.plugins.kotlinx.binary.compatibility.validator)
}

description = "Kotlin runtime library for Protocol Buffers. It is built to work across multiple Kotlin platforms."

repositories {
    google()
}

apiValidation {
    nonPublicMarkers.add("pbandk.PbandkInternal")
    @OptIn(ExperimentalBCVApi::class)
    klib {
        enabled = true
    }
}

kotlin {
    explicitApi()

    androidTarget {
        publishAllLibraryVariants()
    }

    jvm()

    js {
        browser {}
        nodejs {}
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {}
        nodejs {}
    }

    // Native targets, according to https://kotlinlang.org/docs/native-target-support.html
    // Tier 1
    macosX64()
    macosArm64()
    iosSimulatorArm64()
    iosX64()
    // Tier 2
    linuxX64()
    linuxArm64()
    //watchosSimulatorArm64()
    //watchosX64()
    //watchosArm32()
    //watchosArm64()
    tvosSimulatorArm64()
    tvosX64()
    tvosArm64()
    iosArm64()
    // Tier 3
    //androidNativeArm32()
    //androidNativeArm64()
    //androidNativeX86()
    //androidNativeX64()
    mingwX64()
    //watchosDeviceArm64()

    sourceSets {
        all {
            languageSettings {
                optIn("pbandk.ExperimentalProtoJson")
                optIn("pbandk.ExperimentalProtoReflection")
                optIn("pbandk.PbandkInternal")
                optIn("pbandk.PublicForGeneratedCode")
                optIn("kotlin.js.ExperimentalJsExport")
            }
        }

        commonMain {
            dependencies {
                implementation(libs.kotlinx.serialization)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":test-types"))
            }
        }

        androidMain {
            kotlin.srcDir("src/commonJvmAndroid/kotlin")
            dependencies {
                api(project(":pbandk-protos"))
            }
        }

        val androidUnitTest by getting {
            dependencies {
                runtimeOnly(libs.robolectric.android.all)
            }
        }

        jvmMain {
            kotlin.srcDir("src/commonJvmAndroid/kotlin")
            dependencies {
                api(project(":pbandk-protos"))
            }
        }
    }
}

android {
    namespace = "pro.streem.pbandk"

    compileSdk = libs.versions.android.target.sdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.min.sdk.get().toInt()
    }
    testOptions {
        targetSdk = libs.versions.android.target.sdk.get().toInt()
    }
    lint {
        targetSdk = libs.versions.android.target.sdk.get().toInt()
    }
}

tasks.withType<KotlinCompile> {
    compilerOptions.jvmTarget.set(libs.versions.jvm.target.map { JvmTarget.fromTarget(it) })
}
tasks.withType<JavaCompile> {
    targetCompatibility = libs.versions.jvm.target.get()
}

val extractWellKnownTypeProtos = rootProject.tasks.named<Sync>("extractWellKnownTypeProtos")

tasks {
    val generateWellKnownTypeProtos by registering(KotlinProtocTask::class) {
        includeDir.set(layout.dir(extractWellKnownTypeProtos.map { it.destinationDir }))
        outputDir.set(project.file("src/commonMain/kotlin"))
        kotlinPackage.set("pbandk.wkt")
        logLevel.set("debug")
        protoFileSubdir("google/protobuf")
    }

    val generateProtos by registering {
        dependsOn(generateWellKnownTypeProtos)
    }
}

// Stub javadoc artifact to satisfy Maven Central requirements. It's only required for the jvm target.
// TODO: replace this with a real javadoc jar generated with Dokka
val jvmJavadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

afterEvaluate {
    // This needs to be inside of `afterEvaluate` to work correctly with the Android Gradle plugin
    publishing {
        publications.withType<MavenPublication>().configureEach {
            if (artifactId == "pbandk-runtime-jvm") {
                artifact(jvmJavadocJar)
            }
            configurePbandkPom(project.description!!)
        }
    }
}
