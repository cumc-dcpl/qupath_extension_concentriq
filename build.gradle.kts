plugins {
    // Support writing the extension in Groovy (remove this if you don't want to)
    groovy
    // To optionally create a shadow/fat jar that bundle up any non-core dependencies
    id("com.gradleup.shadow") version "8.3.5"
    // QuPath Gradle extension convention plugin
    id("qupath-conventions")
    
}

qupathExtension {
    name = "qupath-extension-concentriq"
    group = "io.github.qupath"
    version = "1.0.0-beta.1-tumbling-turkey"
    description = "A QuPath extension to allow seamless integration between Qupath and the Proscia Concentriq Image Management System"
    automaticModule = "io.github.qupath.extension.template"
}

// TODO: Define your dependencies here
dependencies {

    // Main dependencies for most QuPath extensions
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("io.github.qupath:qupath-extension-svg:0.7.0")

    // If you aren't using Groovy, this can be removed
    shadow(libs.bundles.groovy)

    // For testing
    testImplementation(libs.bundles.qupath)
    testImplementation(libs.junit)

    // The JUnit Platform launcher is needed at test-runtime to actually execute tests, but isn't
    // pulled in automatically by junit-jupiter alone (it's a separate org.junit.platform artifact).
    // Without this, tests compile fine but fail at execution with "Failed to load JUnit Platform".
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")

    // Explicit JavaFX deps for test compilation, matching the version already resolved
    // elsewhere in this build (confirmed via `gradlew.bat buildEnvironment`). Declared directly
    // here rather than through the javafx{} block below, since that block's multi-configuration
    // DSL (configurations(...)/configuration=...) proved ambiguous/version-sensitive to get
    // working for both main and test at once - this sidesteps that entirely for test.
    testImplementation("org.openjfx:javafx-graphics:25.0.2")
    testImplementation("org.openjfx:javafx-controls:25.0.2")
    testImplementation("org.openjfx:javafx-fxml:25.0.2")

}

javafx {
    modules("javafx.controls", "javafx.graphics", "javafx.fxml")
}

tasks.test {
    useJUnitPlatform()
}