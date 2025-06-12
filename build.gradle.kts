plugins {
    kotlin("jvm") version "2.1.20"
}

group = "org.magicghostvu"
version = "0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("io.netty:netty-handler:4.2.2.Final")
}
