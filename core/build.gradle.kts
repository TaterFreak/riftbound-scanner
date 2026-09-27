plugins {
    kotlin("jvm") version "2.0.21"
}

group = "fr.riftbound"
version = "0.1"

repositories { mavenCentral() }

dependencies {
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(17) }

tasks.test { useJUnitPlatform() }
