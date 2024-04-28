plugins {
    kotlin("jvm") version "1.9.22"
    application
}

group = "com.mhozza"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
    implementation("com.github.ajalt.clikt:clikt:4.2.2")
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("ch.qos.logback:logback-classic:1.5.6")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}

application {
    mainClass = "com.mhozza.MainKt"
}
