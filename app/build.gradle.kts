plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.gms.google.services)
}

android {
    namespace = "com.softcraft.freechat"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.softcraft.freechat"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        multiDexEnabled = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }

    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.firebase.auth)
    implementation(libs.credentials)
    implementation(libs.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.firebase.database)
    implementation(libs.firebase.messaging)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    // BouncyCastle — X25519 ECDH + cryptographic primitives
    // IMPORTANT: use the jdk15to18 variant to avoid Android BC conflicts
    implementation("org.bouncycastle:bcprov-jdk15to18:1.84")
    // Source: https://mvnrepository.com/artifact/com.google.code.gson/gson
    implementation("com.google.code.gson:gson:2.13.2")
    // Encrypted SharedPreferences — local key storage
    implementation("androidx.security:security-crypto:1.1.0")
    // Country Code Picker — phone auth UI
    implementation("com.hbb20:ccp:2.7.3")
    // Glide — image loading from byte arrays
    implementation("com.github.bumptech.glide:glide:5.0.7")
    annotationProcessor("com.github.bumptech.glide:compiler:5.0.7")
}