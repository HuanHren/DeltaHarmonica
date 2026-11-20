import com.android.build.api.variant.HostTestBuilder

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}
// Release signing credentials are stored outside the repository.
val releaseStoreFilePath =
    providers.gradleProperty("DH_RELEASE_STORE_FILE").get()

val releaseStorePassword =
    providers.gradleProperty("DH_RELEASE_STORE_PASSWORD").get()

val releaseKeyAlias =
    providers.gradleProperty("DH_RELEASE_KEY_ALIAS").get()

val releaseKeyPassword =
    providers.gradleProperty("DH_RELEASE_KEY_PASSWORD").get()

android {

    namespace = "com.huanhren.deltaharmonica"
    buildToolsVersion = "37.0.0"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.huanhren.deltaharmonica"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        // Retain versionCode 5 for same-signer comparison with 2.3.0 during device acceptance.
        versionName = "2.4.0-rc.2"

        testInstrumentationRunner =
            "com.huanhren.deltaharmonica.AccessibilityTestRunner"
    }

    // 正式 Release 签名
    signingConfigs {
        create("release") {
            storeFile = file(releaseStoreFilePath)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {

        // 正式版本使用自己的长期签名
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }

        // 保留原来的测试版本配置
        create("instrumented") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".instrumented"
            matchingFallbacks += "debug"
            isDebuggable = true
        }
    }

    testBuildType = "instrumented"

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
    }
}

androidComponents {
    beforeVariants { variant ->
        variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    val miuixVersion = "0.9.5-beta01"
    val composeVersion = "1.12.2"

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("org.jetbrains.compose.ui:ui:$composeVersion")
    implementation("org.jetbrains.compose.foundation:foundation:$composeVersion")
    implementation("top.yukonga.miuix.kmp:miuix-ui:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-preference:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-blur:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-material:$miuixVersion")
    implementation("top.yukonga.miuix.kmp:miuix-glass:$miuixVersion")
    implementation("org.jetbrains.compose.ui:ui-tooling-preview:$composeVersion")
    debugImplementation("org.jetbrains.compose.ui:ui-tooling:$composeVersion")
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("dev.atsushieno:ktmidi-jvm:0.9.2") { exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7"); exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8") }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    // Compose Multiplatform 1.12.2 delegates Android UI tests to this AndroidX version.
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.12.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}


