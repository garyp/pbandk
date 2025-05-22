import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

// Top-level build configuration

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.kotlinx.binary.compatibility.validator) apply false

    alias(libs.plugins.osdetector)
}

val sonatypeApiUser = providers.gradlePropertyOrEnvironmentVariable("sonatypeApiUser")
val sonatypeApiKey = providers.gradlePropertyOrEnvironmentVariable("sonatypeApiKey")
val sonatypeRepositoryId = providers.gradlePropertyOrEnvironmentVariable("sonatypeRepositoryId")
val publishToSonatype = sonatypeApiUser.isPresent && sonatypeApiKey.isPresent
if (!publishToSonatype) {
    logger.info("Sonatype API key not defined, skipping configuration of Maven Central publishing repository")
}

val signingKeyAsciiArmored = providers.gradlePropertyOrEnvironmentVariable("signingKeyAsciiArmored")
if (signingKeyAsciiArmored.isPresent) {
    subprojects {
        plugins.withType<SigningPlugin> {
            configure<SigningExtension> {
                useInMemoryPgpKeys(signingKeyAsciiArmored.get(), "")
                sign(extensions.getByType<PublishingExtension>().publications)
            }
        }
    }
} else {
    logger.info("PGP signing key not defined, skipping signing configuration")
}

val downloadProtoc by configurations.creating {
    isTransitive = false
}

val wellKnownTypes by configurations.creating {
    isTransitive = false
}

dependencies {
    downloadProtoc(libs.protobuf.compiler) {
        artifact {
            classifier = osdetector.classifier
            extension = "exe"
        }
    }
    wellKnownTypes(libs.protobuf.java)
}

val extractWellKnownTypeProtos by tasks.registering(Sync::class) {
    dependsOn(wellKnownTypes)
    from({
        wellKnownTypes.filter { it.extension == "jar" }.map { zipTree(it) }
    })
    include("**/*.proto")
    includeEmptyDirs = false
    into(layout.buildDirectory.dir("bundled-protos"))
}

allprojects {
    repositories {
        mavenCentral()
    }

    tasks.withType<AbstractTestTask> {
        testLogging {
            outputs.upToDateWhen { false }
            showStandardStreams = true
            exceptionFormat = TestExceptionFormat.FULL
            events = setOf(
                TestLogEvent.PASSED,
                TestLogEvent.SKIPPED,
                TestLogEvent.FAILED
            )
        }
    }

    if (publishToSonatype) {
        plugins.withType<MavenPublishPlugin>() {
            configure<PublishingExtension> {
                repositories {
                    maven {
                        name = "sonatype"
                        url = when {
                            project.version.toString().endsWith("-SNAPSHOT") ->
                                uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
                            !sonatypeRepositoryId.isPresent ->
                                throw IllegalStateException("Sonatype Repository ID must be provided for non-SNAPSHOT builds")
                            else ->
                                uri("https://s01.oss.sonatype.org/service/local/staging/deployByRepositoryId/${sonatypeRepositoryId.get()}")
                        }

                        credentials {
                            username = sonatypeApiUser.get()
                            password = sonatypeApiKey.get()
                        }
                    }
                }
            }
        }
    }
}
