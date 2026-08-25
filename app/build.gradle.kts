plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.evsuite.tasker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.evsuite.tasker"
        minSdk = 28
        targetSdk = 34
        versionCode = 11
        versionName = "2.3.2"
    }

    // EVTasker doit être signé avec la MÊME clé plateforme que EVProfile : c'est la seule
    // façon d'obtenir com.evsuite.profile.permission.TASKER_BRIDGE (protectionLevel="signature").
    // Sans elle, le bind au pont est refusé par le système et l'app est inerte.
    // À la différence de EVProfile, l'app ne déclare PAS sharedUserId="android.uid.system" :
    // elle n'accède jamais au véhicule directement, donc n'a aucun privilège à réclamer.
    val keystorePath = System.getenv("EV_KEYSTORE") ?: (project.findProperty("evsuite.keystore") as String?)
    signingConfigs {
        if (keystorePath != null && file(keystorePath).exists()) {
            create("platform") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("EV_KEYSTORE_PASSWORD") ?: (project.findProperty("evsuite.keystore.password") as String?)
                keyAlias = System.getenv("EV_KEY_ALIAS") ?: (project.findProperty("evsuite.key.alias") as String?) ?: "platform"
                keyPassword = System.getenv("EV_KEY_PASSWORD") ?: (project.findProperty("evsuite.key.password") as String?)
            }
        }
    }

    // Distribution channels (mirrors ABRP / EVProfile):
    //  - stable  : tagged releases, NO self-update. The updater class is not in the APK.
    //  - unstable: pre-releases published on every push to master, with OTA so testers stay
    //              current without manual work. Installs alongside stable (.unstable suffix).
    // Note: EVProfile targets the ignition broadcast at package "com.evsuite.tasker"; the
    // unstable id is "com.evsuite.tasker.unstable", so an unstable build is a test/manual-run
    // channel and does not receive the auto ignition trigger.
    flavorDimensions += "channel"
    productFlavors {
        create("stable") {
            dimension = "channel"
            buildConfigField("boolean", "OTA_ENABLED", "false")
            buildConfigField("boolean", "CONSOLE_VISIBLE_BY_DEFAULT", "false")
        }
        create("unstable") {
            dimension = "channel"
            applicationIdSuffix = ".unstable"
            // Version stays numerically comparable for the updater ("1.0.0.42-unstable"):
            // the CI passes -PunstableBuild=<n>; 0 locally.
            versionName = "${defaultConfig.versionName}.${project.findProperty("unstableBuild") ?: "0"}"
            versionNameSuffix = "-unstable"
            buildConfigField("boolean", "OTA_ENABLED", "true")
            buildConfigField("boolean", "CONSOLE_VISIBLE_BY_DEFAULT", "true")
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
        buildConfig = true
        // ITaskerBridge : copie conforme du contrat déclaré par EVProfile.
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

// Prints the unstable versionName so the release workflow can name the APK asset
// numerically comparable ("EVTasker-unstable-1.0.0.42.apk"). The pre-release itself is
// always tagged "unstable" and overwritten, so the asset name is what the updater reads.
tasks.register("printUnstableVersion") {
    doLast {
        println("${android.defaultConfig.versionName}.${project.findProperty("unstableBuild") ?: "0"}")
    }
}

dependencies {
    implementation(project(":evhardware"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.material)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.recyclerview)
    implementation(libs.gson)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.kotlinx.coroutines.android)
    implementation("com.google.zxing:core:3.5.3")

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
