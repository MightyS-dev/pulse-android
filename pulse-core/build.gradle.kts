plugins { alias(libs.plugins.kotlin.jvm); `java-library` }
kotlin {
    explicitApi()
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11); allWarningsAsErrors.set(true) }
}
java { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
dependencies {
    api(libs.kotlin.stdlib) { exclude(group = "org.jetbrains", module = "annotations") }
    testImplementation(libs.junit)
}
tasks.test { testLogging { events("passed", "failed", "skipped", "standardError") } }
val generateVersion by tasks.registering {
    val destination = layout.buildDirectory.dir("generated/version")
    val sdkVersion = project.version.toString()
    inputs.property("sdkVersion", sdkVersion)
    outputs.dir(destination)
    doLast {
        val output = destination.get().file("dev/mightys/pulse/BuildVersion.kt").asFile
        output.parentFile.mkdirs()
        output.writeText("package dev.mightys.pulse\ninternal object BuildVersion { const val VALUE = \"$sdkVersion\" }\n")
    }
}
kotlin.sourceSets.main { kotlin.srcDir(generateVersion) }
apply(from = rootProject.file("gradle/publish.gradle.kts"))
