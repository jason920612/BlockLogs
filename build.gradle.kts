plugins {
    java
}

group = "com.blocklogs"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    // Paper API for Minecraft 26.2 (Java 25). Provided by the server at runtime.
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")

    // SQLite JDBC driver. NOT shaded into the jar — it is loaded at runtime by
    // BlockLogsLoader via Paper's MavenLibraryResolver (see paper-plugin.yml `loader`).
    // Declared here as compileOnly so the storage code compiles against it.
    compileOnly("org.xerial:sqlite-jdbc:3.50.1.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("paper-plugin.yml") {
        expand(props)
    }
}
