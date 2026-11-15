plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "ai.upet.vehicle"
    compileSdk = 35
    useLibrary("android.car", false)

    defaultConfig { minSdk = 28 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    testImplementation("junit:junit:4.13.2")
}
