import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val imza = Properties().apply {
    val f = rootProject.file("imza.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.demir.radyo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.demir.radyo"
        minSdk = 26
        targetSdk = 34
        versionCode = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHH")).toInt()
        versionName = "1.0"
    }

    // genel: herkese açık. Sürüme özel ayarlar src/<çeşit>/java/.../Ek.kt içinde.
    flavorDimensions += "surum"
    productFlavors {
        create("genel") {
            dimension = "surum"
        }
    }

    signingConfigs {
        create("radyo") {
            storeFile = rootProject.file("radyo.jks")
            storePassword = imza.getProperty("sifre")
            keyAlias = "radyo"
            keyPassword = imza.getProperty("sifre")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // İmza anahtarı (radyo.jks) depoda yok; yoksa debug imzasıyla derlenir.
            signingConfig = signingConfigs.getByName(if (rootProject.file("radyo.jks").exists()) "radyo" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Akış (MP3/AAC/HLS), şarkı adı (ICY), arka planda çalma, kumanda medya tuşları.
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-session:$media3")
}
