import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// 從 local.properties 讀 iAI Hub 設定（這個檔案在 .gitignore 裡，不會上傳 GitHub）
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(name: String): String = localProps.getProperty(name, "").trim()

android {
    namespace = "com.example.chatreader"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.chatreader"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 程式裡用 BuildConfig.IAI_BASE、BuildConfig.IAI_KEY 讀取
        buildConfigField("String", "IAI_BASE", "\"${localProp("IAI_BASE")}\"")
        buildConfigField("String", "IAI_KEY", "\"${localProp("IAI_KEY")}\"")
        buildConfigField("String", "SYS1_URL", "\"${localProp("SYS1_URL")}\"")
        buildConfigField("String", "SYS1_KEY", "\"${localProp("SYS1_KEY")}\"")
    }

    buildFeatures {
        buildConfig = true
        resValues = true
    }

    // 同一份程式打包成兩個 App：交友大師（friend）與 AI 行車助理（drive）
    flavorDimensions += "contest"
    productFlavors {
        create("friend") {
            dimension = "contest"
            applicationIdSuffix = ".friend"
            resValue("string", "app_name", "交友大師")
            resValue("string", "service_label", "交友大師 讀取服務")
        }
        create("drive") {
            dimension = "contest"
            applicationIdSuffix = ".drive"
            targetSdk = 34   // 黑客松實作組要求 Target SDK 34
            resValue("string", "app_name", "AI 行車助理")
            resValue("string", "service_label", "AI 行車助理 服務")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
