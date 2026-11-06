plugins { `java-library`; `maven-publish` }
dependencies {
    api(project(":protocol"))
    implementation("io.netty:netty-transport:4.2.7.Final")
    implementation("io.netty:netty-codec-base:4.2.7.Final")
    implementation("io.netty:netty-handler:4.2.7.Final")
}
tasks.test {
    maxHeapSize = "512m"
    systemProperty("packetforge.cache", rootProject.layout.buildDirectory.dir("minecraft").get().asFile.absolutePath)
    systemProperty("packetforge.version", providers.gradleProperty("minecraftVersion").orElse("26.3").get())
}
publishing { publications { create<MavenPublication>("maven") { from(components["java"]) } } }
