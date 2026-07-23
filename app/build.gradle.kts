plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.mg4.tasker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mg4.tasker"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    // MG4Tasker doit être signé avec la MÊME clé plateforme que MG4Control : c'est la seule
    // façon d'obtenir com.mg4.control.permission.TASKER_BRIDGE (protectionLevel="signature").
    // Sans elle, le bind au pont est refusé par le système et l'app est inerte.
    // À la différence de MG4Control, l'app ne déclare PAS sharedUserId="android.uid.system" :
    // elle n'accède jamais au véhicule directement, donc n'a aucun privilège à réclamer.
    val keystorePath = System.getenv("MG4_KEYSTORE") ?: (project.findProperty("mg4.keystore") as String?)
    signingConfigs {
        if (keystorePath != null && file(keystorePath).exists()) {
            create("platform") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("MG4_KEYSTORE_PASSWORD") ?: (project.findProperty("mg4.keystore.password") as String?)
                keyAlias = System.getenv("MG4_KEY_ALIAS") ?: (project.findProperty("mg4.key.alias") as String?) ?: "platform"
                keyPassword = System.getenv("MG4_KEY_PASSWORD") ?: (project.findProperty("mg4.key.password") as String?)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("platform")?.let { signingConfig = it }
        }
        debug {
            signingConfigs.findByName("platform")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }



    buildFeatures {
        viewBinding = true
        // ITaskerBridge : copie conforme du contrat déclaré par MG4Control.
        aidl = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    // AGP 9 removed the legacy applicationVariants output API; release APK naming is
    // handled by the release workflow.
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":mg4hardware"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.material)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.gson)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
