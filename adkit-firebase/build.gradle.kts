plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "dev.adkit.firebase"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(project(":adkit-core"))
    api(libs.firebase.config)
    api(libs.firebase.analytics)
    implementation(libs.kotlinx.coroutines.play.services)
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                from(components["release"])
                artifactId = "adkit-firebase"
            }
        }
    }
}
