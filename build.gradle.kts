plugins {
    java
}

group = "at.vystorm"
version = "0.31.0-26.2"
val pluginVersion = version.toString()

val coreJar = file(providers.gradleProperty("vystormCoreJar").getOrElse("libs/Vystorm_Core.jar"))

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.121-stable")
    compileOnly(files(coreJar))

    testImplementation("org.junit.jupiter:junit-jupiter:6.0.1")
    testImplementation("org.mockito:mockito-core:5.20.0")
    testImplementation(files(coreJar))
    testImplementation("io.papermc.paper:paper-api:26.2.build.121-stable")
    // Only for StorageIntegrationTest against a local MariaDB; on the server the driver ships with Purpur.
    testRuntimeOnly("com.mysql:mysql-connector-j:9.2.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.1")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

configurations.configureEach {
    // The compiler runs on JDK 25; only emitted bytecode targets Java 21.
    if (isCanBeResolved) attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}

tasks.processResources {
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.jar {
    archiveFileName = "VystormInsomnia-${project.version}.jar"
}

tasks.test {
    useJUnitPlatform()
}

tasks.compileJava {
    doFirst {
        require(coreJar.isFile) {
            "Vystorm Core JAR fehlt: -PvystormCoreJar=/pfad/zur/Vystorm_Core-0.48.0-Vystorm-Core-26.2.jar"
        }
    }
}

val paper1206Libraries = file(providers.gradleProperty("paper1206Libraries")
    .getOrElse((System.getProperty("user.home") + "/source/tools/smoke-1206/libraries")))
val compat1206 by tasks.registering(JavaCompile::class) {
    description = "Compiles common runtime against Paper 1.20.6 and Java 21."
    group = "verification"
    source(sourceSets.main.get().allJava)
    classpath = files(coreJar, fileTree(paper1206Libraries) { include("**/*.jar") },
        configurations.compileClasspath.get().filter { it.name.startsWith("annotations-") })
    destinationDirectory = layout.buildDirectory.dir("classes/java/compat1206")
    javaCompiler = javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(25) }
    doFirst {
        require(paper1206Libraries.resolve("io/papermc/paper/paper-api/1.20.6-R0.1-SNAPSHOT/paper-api-1.20.6-R0.1-SNAPSHOT.jar").isFile) {
            "Paper 1.20.6 libraries missing: -Ppaper1206Libraries=/path/to/libraries"
        }
    }
}
tasks.check { dependsOn(compat1206) }
tasks.test {
    dependsOn(compat1206)
    systemProperty("compat1206.classpath", compat1206.get().classpath.asPath)
    systemProperty("compat1206.mainClasses", sourceSets.main.get().output.classesDirs.asPath)
}

// The project cache lives inside build and is locked by Gradle on Windows.
tasks.clean {
    setDelete(provider { layout.buildDirectory.get().asFile.listFiles()?.filter { it.name != "gradle-cache" } ?: emptyList<File>() })
}
