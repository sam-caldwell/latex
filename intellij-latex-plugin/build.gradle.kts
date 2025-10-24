plugins {
  id("org.jetbrains.intellij.platform") version "2.10.2"
  kotlin("jvm") version "1.9.23"
  jacoco
}

repositories {
  mavenCentral()
  intellijPlatform { defaultRepositories() }
}

group = "com.samcaldwell.latex"
version = "0.1.0"

// IntelliJ Platform & bundled plugin dependencies (2.x DSL)
dependencies {
  implementation("org.apache.pdfbox:pdfbox:3.0.6")
  implementation("com.google.code.gson:gson:2.13.2")

  testImplementation(kotlin("test"))
  testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
  testImplementation("io.mockk:mockk:1.13.12")
  testImplementation("junit:junit:4.13.2")
  testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")

  intellijPlatform {
    intellijIdeaCommunity("2024.2", useInstaller = false)
    bundledPlugin("com.intellij.java")
    testFramework()
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

  test {
    useJUnitPlatform()
  }

  jacocoTestReport {
    dependsOn(test)
    reports {
      xml.required.set(true)
      html.required.set(true)
      csv.required.set(false)
    }
    // Limit coverage scope to logic that is unit-testable outside the IDE runtime.
    // UI- and platform-bound classes are excluded.
    val excludePatterns = listOf(
      // Exclude all by default, then include specific classes below
      "**/*"
    )
    val includePatterns = listOf(
      "com/samcaldwell/latex/LookupSettingsService*"
    )

    val classDirs = files(sourceSets["main"].output.classesDirs)
    classDirectories.setFrom(
      classDirs.asFileTree.matching {
        // First include nothing, then add explicit includes
        exclude(excludePatterns)
        include(includePatterns)
      }
    )
  }

  jacocoTestCoverageVerification {
    dependsOn(test)
    violationRules {
      rule {
        limit {
          // Minimum overall coverage required
          counter = "INSTRUCTION"
          value = "COVEREDRATIO"
          minimum = BigDecimal("0.80")
        }
      }
    }
    val excludePatterns = listOf("**/*")
    val includePatterns = listOf("com/samcaldwell/latex/LookupSettingsService*")
    val classDirs = files(sourceSets["main"].output.classesDirs)
    classDirectories.setFrom(
      classDirs.asFileTree.matching {
        exclude(excludePatterns)
        include(includePatterns)
      }
    )
  }

  // Also gate 'check' on coverage verification so CI fails appropriately
  check { dependsOn(jacocoTestCoverageVerification) }
}
