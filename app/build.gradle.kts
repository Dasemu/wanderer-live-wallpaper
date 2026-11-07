plugins {
    id("com.android.application")
}

// Single source of truth for the version: the root VERSION file (bumped by CI).
val appVersion = rootProject.file("VERSION").readText().trim()
val (vMajor, vMinor, vPatch) = appVersion.split(".").map { it.toInt() }

// Release signing comes from environment variables (see .env.example); absent => unsigned release.
val keystorePath: String? = System.getenv("WANDERER_KEYSTORE_PATH")

android {
    namespace = "es.dasemu.wanderer"
    compileSdk = 36

    defaultConfig {
        applicationId = "es.dasemu.wanderer"
        minSdk = 29
        targetSdk = 36
        versionCode = vMajor * 10000 + vMinor * 100 + vPatch
        versionName = appVersion
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("WANDERER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("WANDERER_KEY_ALIAS")
                keyPassword = System.getenv("WANDERER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // org.json is stubbed in android.jar for JVM unit tests; the real one is needed to test palette parsing.
    testImplementation("org.json:json:20260814")
}
