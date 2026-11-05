plugins { application }
dependencies {
    implementation("com.github.javaparser:javaparser-core:3.28.2")
    implementation("com.google.code.gson:gson:2.13.2")
    runtimeOnly("org.vineflower:vineflower:1.12.0")
}
application { mainClass.set("org.stackanvil.packetforge.generator.Main") }
tasks.run { workingDir(rootProject.projectDir) }
dependencies { testImplementation(project(":commons")) }
