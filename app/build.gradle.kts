import com.android.build.api.variant.ApplicationVariant

plugins {
    id("com.android.application")
    kotlin("android") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    kotlin("plugin.compose") version "1.5.11"
}

repositories {
    google()
    mavenCentral()
}

android {
    compileSdk = 35

    defaultConfig {
        applicationId = "vion.board"
        minSdk = 21
        targetSdk = 35
        versionCode = 3701
        versionName = "3.7"
        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
        }
        create("nouserlib") {
            isMinifyEnabled = true
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
        }
        debug {
            isMinifyEnabled = true
            isJniDebuggable = false
            applicationIdSuffix = ".debug"
        }
        create("runTests") {
            isMinifyEnabled = false
            isJniDebuggable = false
        }
        create("debugNoMinify") {
            isDebuggable = true
            isMinifyEnabled = false
            isJniDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
        }
        base.archivesBaseName = "VionBoard_" + defaultConfig.versionName
        androidComponents.onVariants { variant: ApplicationVariant ->
            if (variant.buildType == "debug") {
                variant.androidResources.ignoreAssetsPatterns = listOf("main_ro.dict")
                variant.proguardFiles = emptyList()
                variant.proguardFiles.add(project.layout.buildDirectory.file(getDefaultProguardFile("proguard-android.txt").absolutePath))
                variant.proguardFiles.add(project.layout.buildDirectory.file(project.buildFile.parent + "/proguard-rules.pro"))
            }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        ndkBuild {
            path = File("src/main/jni/Android.mk")
        }
    }

    ndkVersion = "26.2.11394342"

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    namespace = "helium314.keyboard.latin"
    lint {
        abortOnError = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
}