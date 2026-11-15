plugins {
    kotlin("jvm") version "2.0.0" apply false
    kotlin("android") version "2.0.0" apply false
    id("com.android.application") version "8.8.2" apply false
    id("com.android.library") version "8.8.2" apply false
}

// Temporary identity; applicationId and app namespace read this single value.
extra["upetApplicationId"] = "ai.upet.app"
