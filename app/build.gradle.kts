plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}
val releaseVersion = rootProject.file("VERSION").readText().trim()
val versionParts = releaseVersion.split('.').map(String::toInt)
require(versionParts.size == 3 && versionParts.all { it in 0..999 })
val noticeAssets = layout.buildDirectory.dir("generated/noticeAssets")
val copyNotices = tasks.register<Sync>("copyNotices") {
    from(rootProject.file("LICENSE"), rootProject.file("docs/licenses/Apache-2.0.txt"))
    into(noticeAssets.map { it.dir("licenses") })
}
android {
    namespace = "io.github.yyan115.opentune"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.yyan115.opentune"
        minSdk = 26
        targetSdk = 36
        versionCode = versionParts[0] * 1_000_000 + versionParts[1] * 1_000 + versionParts[2]
        versionName = releaseVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    fun signingValue(name: String) = providers.environmentVariable("OPENTUNE_$name")
    val releaseKeyPath = signingValue("KEYSTORE").orNull
    if (releaseKeyPath != null) signingConfigs.create("release") {
        storeFile = file(releaseKeyPath)
        storePassword = signingValue("STORE_PASSWORD").get()
        keyAlias = signingValue("KEY_ALIAS").getOrElse("opentune")
        keyPassword = signingValue("KEY_PASSWORD").get()
    }
    buildTypes {
        release {
            if (releaseKeyPath != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            // Adds the en-XA and ar-XB test languages, which mark every string that comes from a resource.
            isPseudoLocalesEnabled = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures { compose = true; buildConfig = true }
    // The list of languages in the system settings follows the values-xx folders.
    androidResources { generateLocaleConfig = true }
    sourceSets.getByName("main").assets.srcDir(noticeAssets)
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { animationsDisabled = true }
    lint { abortOnError = true; checkDependencies = true }
}
tasks.named("preBuild") { dependsOn(copyNotices) }
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.browser)
    implementation(libs.lifecycle.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.dash)
    implementation(libs.media3.session)
    implementation(libs.media3.hls)
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.datasource)
    implementation(libs.work.runtime)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.rules)
    debugImplementation(libs.compose.test.manifest)
    debugImplementation(libs.compose.tooling)
}
