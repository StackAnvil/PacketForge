import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.plugins.JavaApplication

plugins {
    base
    id("com.diffplug.spotless") version "8.11.0" apply false
}
val palantirJavaFormatVersion = providers.gradleProperty("palantirJavaFormatVersion").get()
// The generator's source formatter uses the JDK compiler parser.
val formatterJvmArgs = listOf("api", "code", "file", "parser", "tree", "util").map {
    "--add-exports=jdk.compiler/com.sun.tools.javac.$it=ALL-UNNAMED"
}
allprojects {
    group = "org.stackanvil.packetforge"
    version = "0.1.0-SNAPSHOT"
    repositories { mavenCentral() }
}
subprojects {
    apply(plugin = "java-library")
    apply(plugin = "com.diffplug.spotless")
    extensions.configure<SpotlessExtension> {
        java {
            target("src/*/java/**/*.java")
            palantirJavaFormat(palantirJavaFormatVersion)
            removeUnusedImports()
        }
    }
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        withSourcesJar()
    }
    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.14.1"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
    tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
    tasks.withType<JavaExec>().configureEach { jvmArgs(formatterJvmArgs) }
    if (name == "generator") {
        tasks.withType<Test>().configureEach { jvmArgs(formatterJvmArgs) }
        plugins.withId("application") {
            extensions.configure<JavaApplication> {
                applicationDefaultJvmArgs = formatterJvmArgs
            }
        }
    }
}
