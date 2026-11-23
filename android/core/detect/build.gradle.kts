plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { targetCompatibility = JavaVersion.VERSION_17; sourceCompatibility = JavaVersion.VERSION_17 }
dependencies {
    // TODO(phase12): add back `api(project(":core:rules"))` when core:rules is merged (for SurfaceMode).
    api(libs.kotlinx.serialization.json)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(libs.junit.platform.launcher)
}
tasks.test {
    useJUnitPlatform()
    val seed = rootProject.file("../shared/detectors/detectors.v1.json")
    inputs.file(seed)
    systemProperty("steady.detectors.seed", seed.absolutePath)
}
