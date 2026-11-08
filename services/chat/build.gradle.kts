plugins {
    application
    id("com.diffplug.spotless") version "8.11.0"
}

group = "com.worldjigsaw"
version = "0.0.1-SNAPSHOT"
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
repositories { mavenCentral() }
dependencies {
    implementation("com.ibm.icu:icu4j:74.2") // Unicode 15.1
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.4")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
application { mainClass = "com.worldjigsaw.chat.ChatServer" }
tasks.withType<Test> { useJUnitPlatform() }
spotless {
    java { googleJavaFormat("2.0.0") }
    kotlinGradle { ktlint() }
}
