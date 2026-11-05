plugins { `java-library`; `maven-publish` }
dependencies { api("io.netty:netty-buffer:4.2.7.Final"); api("com.google.code.gson:gson:2.13.2") }
publishing { publications { create<MavenPublication>("maven") { from(components["java"]) } } }
