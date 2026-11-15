// Shared Maven publishing setup for the library modules. Group and version come from the root build
// (group) and gradle/libs.versions.toml (pulse); the artifact id is the module name.
// No signing and no remote repository: the only target is a local directory given with -PpulsePublishRepo=<dir>.
apply(plugin = "maven-publish")

// What Maven Central shows (POM name, description, url).
val pulseHomepage = "https://pulse.mightys.dev"
val pulseRepository = "https://github.com/MightyS-dev/pulse-android"
val pulseAuthor = "MightyS Team"

val moduleName = mapOf(
    "pulse-core" to "MightyS Pulse Core",
    "pulse-android" to "MightyS Pulse for Android",
)
val moduleDescription = mapOf(
    "pulse-core" to "JVM core of MightyS Pulse for Android, without Android classes. Add dev.mightys:pulse-android to your app; this module comes with it.",
    "pulse-android" to "Privacy-first analytics for Android apps and games, for Kotlin and Java. Named events, no advertising ID or device IDs, offline queue. Minimum SDK 21.",
)

fun org.gradle.api.publish.maven.MavenPom.pulseMetadata() {
    name.set(moduleName.getValue(project.name))
    description.set(moduleDescription.getValue(project.name))
    url.set(pulseHomepage)
    licenses { license { name.set("MIT License"); url.set("https://opensource.org/licenses/MIT") } }
    developers { developer { id.set("mightys"); name.set(pulseAuthor); url.set(pulseHomepage) } }
    scm {
        url.set(pulseRepository)
        connection.set("scm:git:$pulseRepository.git")
        developerConnection.set("scm:git:$pulseRepository.git")
    }
}

// Licence text inside every artifact under a module-specific META-INF name (a shared name would collide when
// an app merges both modules).
val licenseFile = rootProject.file("LICENSE.md")
val licenseEntry = "LICENSE-${project.name}.md"

// Minimal javadoc jar: a README describing where the KDoc lives. The KDoc ships in the -sources.jar of each artifact.
val javadocReadme by tasks.registering {
    val out = layout.buildDirectory.file("generated/javadoc-jar/README.md")
    val text = "# ${moduleName.getValue(project.name)} $version\n\n${moduleDescription.getValue(project.name)}\n\n" +
        "The public API is documented with KDoc in the -sources.jar of this release and in the README of the project: $pulseHomepage\n"
    inputs.property("text", text)
    outputs.file(out)
    doLast { out.get().asFile.apply { parentFile.mkdirs(); writeText(text) } }
}
val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    from(javadocReadme)
    from(licenseFile) { into("META-INF"); rename { licenseEntry } }
}

extensions.configure<PublishingExtension> {
    repositories {
        (findProperty("pulsePublishRepo") as String?)?.let { dir -> maven { name = "local"; url = uri(dir) } }
    }
}

if (plugins.hasPlugin("com.android.library")) {
    afterEvaluate {
        extensions.configure<PublishingExtension> {
            publications.create<MavenPublication>("release") {
                from(components["release"])
                artifact(javadocJar)
                artifactId = project.name
                pom { pulseMetadata() }
            }
        }
    }
} else {
    extensions.configure<JavaPluginExtension> { withSourcesJar() }
    tasks.named<Jar>("jar") { from(licenseFile) { into("META-INF"); rename { licenseEntry } } }
    tasks.named<Jar>("sourcesJar") { from(licenseFile) { into("META-INF"); rename { licenseEntry } } }
    afterEvaluate {
        extensions.configure<PublishingExtension> {
            publications.create<MavenPublication>("maven") {
                from(components["java"])
                artifact(javadocJar)
                artifactId = project.name
                pom { pulseMetadata() }
            }
        }
    }
}
