import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass = "pbandk.conformance.MainKt"
    applicationName = "conformance"
}

dependencies {
    implementation(project(":conformance:conformance-lib"))
}

tasks.withType<KotlinCompile> {
    compilerOptions.jvmTarget.set(libs.versions.jvm.target.map { JvmTarget.fromTarget(it) })
}
tasks.withType<JavaCompile> {
    targetCompatibility = libs.versions.jvm.target.get()
}
