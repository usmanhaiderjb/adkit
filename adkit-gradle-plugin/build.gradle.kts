plugins {
    `java-gradle-plugin`
    `maven-publish`
}

group = System.getenv("GROUP")?.let { g -> System.getenv("ARTIFACT")?.let { "$g.$it" } ?: g } ?: "com.github.adkit"
version = System.getenv("VERSION") ?: "1.0.0"

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

dependencies {
    testImplementation("junit:junit:4.13.2")
}

gradlePlugin {
    plugins {
        register("adkitCheck") {
            id = "dev.adkit.check"
            implementationClass = "dev.adkit.gradle.AdKitCheckPlugin"
            displayName = "AdKit release check"
            description = "Fails release builds that still contain Google test AdMob ids."
        }
    }
}
