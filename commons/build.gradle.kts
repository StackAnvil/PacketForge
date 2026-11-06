plugins { `java-library`; `maven-publish` }
dependencies {
    api("io.netty:netty-buffer:4.2.7.Final")
    api("com.google.code.gson:gson:2.13.2")
    api("net.kyori:adventure-api:5.2.0")
    implementation("net.kyori:adventure-nbt:5.2.0")
    implementation("net.kyori:adventure-text-serializer-gson:5.2.0")
    implementation("org.cloudburstmc:nbt:3.1.0.Final")
}
publishing { publications { create<MavenPublication>("maven") { from(components["java"]) } } }
