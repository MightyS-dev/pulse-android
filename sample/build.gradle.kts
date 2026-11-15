plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android) }

// Pass your app key at build time, it is never committed: ./gradlew :sample:assembleDebug -PpulseAppKey=YOUR_KEY
val pulseAppKey: String = (project.findProperty("pulseAppKey") as String?).orEmpty()

android {
    namespace = "dev.mightys.pulse.sample"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "dev.mightys.pulse.sample"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = rootProject.libs.versions.pulse.get()
        buildConfigField("String", "PULSE_APP_KEY", "\"" + pulseAppKey.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    lint { abortOnError = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
dependencies {
    implementation(project(":pulse-android"))
    implementation(libs.kotlin.stdlib)
}
