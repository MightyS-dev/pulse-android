plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android) }
android {
    namespace = "dev.mightys.pulse"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig { minSdk = 21; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    publishing { singleVariant("release") { withSourcesJar() } }
    lint { abortOnError = true; warningsAsErrors = true }
}
val licenseResources by tasks.registering(Copy::class) {
    from(rootProject.file("LICENSE.md")) { rename { "LICENSE-pulse-android.md" } }
    into(layout.buildDirectory.dir("generated/license-resources/META-INF"))
}
android.sourceSets.getByName("main").resources.srcDir(layout.buildDirectory.dir("generated/license-resources"))
tasks.matching { it.name.startsWith("process") && it.name.endsWith("JavaRes") }.configureEach { dependsOn(licenseResources) }
kotlin {
    explicitApi()
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11); allWarningsAsErrors.set(true) }
}
dependencies {
    api(libs.kotlin.stdlib) { exclude(group = "org.jetbrains", module = "annotations") }
    api(project(":pulse-core"))
    testImplementation(libs.junit)
}
apply(from = rootProject.file("gradle/publish.gradle.kts"))
