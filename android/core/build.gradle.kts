plugins { kotlin("jvm") }

kotlin { jvmToolchain(17) }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.0")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("user.timezone", "Asia/Seoul")
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

// Package the canonical app learning data directly; no hand-maintained mirror.
tasks.processResources {
    from(rootProject.projectDir.parentFile.resolve("content")) {
        include("feature_cards_v1.json", "learning_courses_v1.json", "cards_v1.json", "cards_v1_1.json")
        into("content")
    }
}
