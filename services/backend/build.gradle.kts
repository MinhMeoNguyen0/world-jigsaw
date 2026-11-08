plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    id("com.diffplug.spotless") version "8.11.0"
}

group = "com.worldjigsaw"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot's BOM pins dependencies without explicit versions.
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("com.networknt:json-schema-validator:1.5.6")
    implementation("io.github.erdtman:java-json-canonicalization:1.1")
    implementation("org.locationtech.jts:jts-core:1.20.0")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.java-websocket:Java-WebSocket:1.6.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.register<JavaExec>("previewDemo") {
    group = "verification"
    description = "Run the private loopback preview transport demo"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.worldjigsaw.game.PreviewDemoServer"
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty(
        "worldjigsaw.test.classpath",
        sourceSets.test
            .get()
            .runtimeClasspath.asPath,
    )
}

spotless {
    java {
        googleJavaFormat("2.0.0")
    }
    kotlinGradle {
        ktlint()
    }
}

tasks.processResources {
    from("../../packages/contracts/v1/schema.json") {
        into("contracts/v1")
    }
}
