plugins {
    java
    application
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
    // JSON parsing for configuration
    implementation("com.google.code.gson:gson:2.10.1")
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
    })
}

tasks.build {
    dependsOn(tasks.named("fatJar"))
}
