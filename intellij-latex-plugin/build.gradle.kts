plugins {
  id("org.jetbrains.intellij.platform") version "2.10.2"
  kotlin("jvm") version "1.9.23"
}

repositories {
  mavenCentral()
  intellijPlatform { defaultRepositories() }
}

group = "com.samcaldwell.latex"
version = "0.1.0"

// IntelliJ Platform & bundled plugin dependencies (2.x DSL)
dependencies {
  implementation("org.apache.pdfbox:pdfbox:2.0.30")
  implementation("com.google.code.gson:gson:2.10.1")

  intellijPlatform {
    intellijIdeaCommunity("2024.2", useInstaller = false)
    bundledPlugin("com.intellij.java")
  }
}

// Use Java 17 toolchain for compiling (IntelliJ 2024.2 requires 17)
kotlin { jvmToolchain(17) }

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
}

tasks {
  runIde { jvmArgs = listOf("-Xmx2g") }
}
