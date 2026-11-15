import java.util.Properties
import java.io.File

plugins {
    id("com.android.application")
    kotlin("android")
}
val signingPropertiesFile = rootProject.file("signing.local.properties")
val signingValues = Properties().apply {
    if (signingPropertiesFile.isFile) signingPropertiesFile.inputStream().use { load(it) }
}
val signingNames = listOf("storeFile", "storePasswordFile", "keyAlias", "keyPasswordFile")
val signingRequested = signingPropertiesFile.isFile
if (signingRequested && signingNames.any { signingValues.getProperty(it).isNullOrBlank() }) {
    throw GradleException("Local release signing properties are incomplete.")
}
fun externalSigningFile(name: String): File {
    val target = file(signingValues.getProperty(name)).canonicalFile
    val repository = rootProject.projectDir.parentFile.canonicalFile
    if (!target.isFile || target.toPath().startsWith(repository.toPath())) {
        throw GradleException("Signing material must be an existing file outside the repository.")
    }
    return target
}
fun signingSecret(name: String): String {
    val secretFile = externalSigningFile(name)
    if (secretFile.length() > 4096) throw GradleException("Signing password file is invalid.")
    val value = secretFile.readText(Charsets.UTF_8).trimEnd('\r', '\n')
    if (value.length < 8 || value.length > 256) throw GradleException("Signing password file is invalid.")
    return value
}
android {
    namespace = rootProject.extra["upetApplicationId"].toString()
    compileSdk = 35
    useLibrary("android.car", false)
    defaultConfig {
        applicationId = namespace
        minSdk = 28
        targetSdk = 35
        versionCode = 3
        versionName = "0.4.0"
    }
    buildFeatures { buildConfig = true }
    val submissionSigning = if (signingRequested) signingConfigs.create("submission") {
        storeFile = externalSigningFile("storeFile")
        storePassword = signingSecret("storePasswordFile")
        keyAlias = signingValues.getProperty("keyAlias")
        keyPassword = signingSecret("keyPasswordFile")
    } else null
    buildTypes {
        debug { buildConfigField("boolean", "DEMO_SIGNALS", "true") }
        release {
            isMinifyEnabled = false
            signingConfig = submissionSigning
            buildConfigField("boolean", "DEMO_SIGNALS", "false")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = true }
}
// Missing local properties produces an unsigned build; signing material is never embedded.
val releaseArtifactName = providers.gradleProperty("teamName").get() + "_" +
    providers.gradleProperty("appName").get() + "_release.apk"
tasks.register<Copy>("prepareReleaseApk") {
    dependsOn("assembleRelease")
    val expectedApk = if (signingRequested) "app-release.apk" else "app-release-unsigned.apk"
    from(layout.buildDirectory.dir("outputs/apk/release")) { include(expectedApk) }
    doFirst {
        if (!layout.buildDirectory.file("outputs/apk/release/$expectedApk").get().asFile.isFile) {
            throw GradleException("Expected release APK was not produced.")
        }
    }
    into(layout.buildDirectory.dir("prepared-release"))
    rename { releaseArtifactName }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":vehicle"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
}
