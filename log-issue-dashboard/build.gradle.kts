plugins {
    java
    application
    idea
}

group = "com.logdashboard"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

repositories {
    mavenCentral()
}

dependencies {
    // JSON parsing for configuration and REST API
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Embedded Tomcat
    implementation("org.apache.tomcat.embed:tomcat-embed-core:10.1.18")
    implementation("org.apache.tomcat.embed:tomcat-embed-jasper:10.1.18")
    
    // Jakarta Servlet API (required for Tomcat 10+)
    implementation("jakarta.servlet:jakarta.servlet-api:6.0.0")
    
    // H2 embedded database for persistent storage
    implementation("com.h2database:h2:2.2.224")
}

application {
    mainClass.set("com.logdashboard.LogDashboardApp")
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "com.logdashboard.LogDashboardApp"
        )
    }
}

// Create a fat JAR with all dependencies included
tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    
    manifest {
        attributes("Main-Class" to "com.logdashboard.LogDashboardApp")
    }
    
    from(sourceSets.main.get().output)
    
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    }) {
        // Exclude signature files from signed JARs to prevent SecurityException
        exclude("META-INF/*.SF")
        exclude("META-INF/*.DSA")
        exclude("META-INF/*.RSA")
        exclude("META-INF/*.EC")
        exclude("META-INF/MANIFEST.MF")
    }
}

tasks.build {
    dependsOn(tasks.named("fatJar"))
}

idea {
    module {
        isDownloadJavadoc = true
        isDownloadSources = true
    }
}
