plugins { `java-library`; `maven-publish` }
dependencies { api(project(":commons")) }
val minecraftVersion = providers.gradleProperty("minecraftVersion").orElse("26.3")
val generated = layout.buildDirectory.dir("generated")
val generateProtocol = tasks.register<JavaExec>("generateProtocol") {
    dependsOn(":generator:classes")
    classpath = project(":generator").extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
    mainClass.set("org.stackanvil.packetforge.generator.Main")
    inputs.property("minecraftVersion", minecraftVersion)
    inputs.files(project(":generator").fileTree("src/main"))
    outputs.dir(generated)
    args("--version", minecraftVersion.get(), "--cache", rootProject.layout.buildDirectory.dir("minecraft").get().asFile,
        "--output", generated.get().asFile)
    maxHeapSize = "3g"
}
sourceSets.main {
    java.srcDir(generateProtocol.map { generated.get().dir("java") })
    resources.srcDir(generateProtocol.map { generated.get().dir("resources") })
}
tasks.test {
    maxHeapSize = "1g"
    systemProperty("packetforge.cache", rootProject.layout.buildDirectory.dir("minecraft").get().asFile.absolutePath)
    systemProperty("packetforge.version", minecraftVersion.get())
}
publishing { publications { create<MavenPublication>("maven") { from(components["java"]) } } }
dependencies { testImplementation("com.google.code.gson:gson:2.13.2") }
