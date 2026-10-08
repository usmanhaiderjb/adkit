plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("dev.adkit.check")
}

// The sample ships Google's demo app id in its manifest, so only warn here. Real apps keep the default (fail).
adkitCheck {
    failOnTestIds.set(false)
}

android {
    namespace = "dev.adkit.sample"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.adkit.sample"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":adkit-core"))
    implementation(project(":adkit-compose"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
}
