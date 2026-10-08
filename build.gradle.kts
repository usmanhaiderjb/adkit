plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// JitPack builds the tag as the version: com.github.<user>.<repo>:adkit-core:<tag>
allprojects {
    // JitPack exports GROUP=com.github.<user> and ARTIFACT=<repo>; modules publish as <GROUP>.<ARTIFACT>:<module>.
    group = System.getenv("GROUP")?.let { g -> System.getenv("ARTIFACT")?.let { "$g.$it" } ?: g }
        ?: (findProperty("GROUP") as String?) ?: "com.github.adkit"
    version = System.getenv("VERSION") ?: (findProperty("VERSION_NAME") as String?) ?: "1.0.0"
}
