import java.util.Properties
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- 簽章設定（簽章金鑰放在 repo 的 signing/，可隨時替換）----
val signingDir = rootProject.file("../signing")
val keystoreB64 = File(signingDir, "keystore.b64")
val signingPropsFile = File(signingDir, "signing.properties")
val hasRepoSigning = keystoreB64.exists() && signingPropsFile.exists()
val signingProps = Properties()
if (hasRepoSigning) {
    signingPropsFile.inputStream().use { signingProps.load(it) }
    val p12 = File(signingDir, "keystore.p12")
    if (!p12.exists()) {
        p12.writeBytes(Base64.getMimeDecoder().decode(keystoreB64.readText().trim()))
    }
}

android {
    signingConfigs {
        if (hasRepoSigning) {
            create("repo") {
                storeFile = File(signingDir, "keystore.p12")
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    namespace = "com.dsh.ledger"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dsh.ledger"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        if (hasRepoSigning) {
            getByName("debug") { signingConfig = signingConfigs.getByName("repo") }
        }
        release {
            isMinifyEnabled = false
            if (hasRepoSigning) { signingConfig = signingConfigs.getByName("repo") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
