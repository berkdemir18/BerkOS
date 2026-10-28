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
    namespace = "com.demir.salon"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.demir.salon"
        minSdk = 26
        targetSdk = 34
        // Her derlemede artan sürüm: yyMMddHH (ör. 26102614). Ayarlar menüsünün altında yazar.
        versionCode = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHH")).toInt()
        versionName = "1.1"
    }

    // genel: herkese açık BerkOS. Sürüme özel parçalar src/<çeşit>/java/.../Ek.kt içinde.
    flavorDimensions += "surum"
    productFlavors {
        create("genel") {
            dimension = "surum"
            applicationId = "com.berkos.tv"
        }
    }

    signingConfigs {
        create("salon") {
            storeFile = rootProject.file("salon.jks")
            storePassword = imza.getProperty("sifre")
            keyAlias = "salon"
            keyPassword = imza.getProperty("sifre")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // İmza anahtarı (salon.jks) depoda yok; yoksa debug imzasıyla derlenir.
            signingConfig = signingConfigs.getByName(if (rootProject.file("salon.jks").exists()) "salon" else "debug")
        }
        // Gerçek TV'de profillemek için: release ile aynı imza (veriler silinmeden üstüne kurulur), küçültme yok.
        create("olcum") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            matchingFallbacks += "release"
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

