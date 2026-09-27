plugins {
    kotlin("jvm") version "2.0.21"
}

group = "fr.riftbound"
version = "0.1"

repositories { mavenCentral() }

dependencies {
    testImplementation(kotlin("test"))
    // Uniquement pour charger data/cards.json dans les tests. La bibliotheque
    // elle-meme ne depend de rien : l'application Android fait son propre parsing.
    testImplementation("org.json:json:20240303")
}

kotlin { jvmToolchain(17) }

tasks.test { useJUnitPlatform() }
