import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("org.jetbrains.intellij.platform") version "2.10.2"
  kotlin("jvm") version "2.2.21"
  jacoco
  `maven-publish`
}

repositories {
  mavenCentral()
  intellijPlatform { defaultRepositories() }
}

group = "net.samcaldwell.latex"
// Default version for local builds; CI uses tag-derived version if available
var computedVersion = "0.1.1"
val refName = System.getenv("GITHUB_REF_NAME") ?: System.getenv("GITHUB_REF")?.substringAfterLast('/')
if (refName != null && refName.startsWith("v")) {
  val tagVer = refName.removePrefix("v")
  // Allow SemVer with optional pre-release suffix
  val semver = Regex("^\\d+\\.\\d+\\.\\d+(?:-[A-Za-z0-9]+)?$")
  if (semver.matches(tagVer)) {
    computedVersion = tagVer
  }
}
version = computedVersion

// IntelliJ Platform & bundled plugin dependencies (2.x DSL)
dependencies {
  implementation("org.apache.pdfbox:pdfbox:3.0.6")
  implementation("com.google.code.gson:gson:2.13.2")

  testImplementation(kotlin("test"))
  testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
  testImplementation("io.mockk:mockk:1.13.12")
  testImplementation("junit:junit:4.13.2")
  testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
  testRuntimeOnly("org.jetbrains.kotlinx:kotlinx-coroutines-debug:1.7.3")

  intellijPlatform {
    intellijIdeaCommunity("2024.2", useInstaller = false)
    bundledPlugin("com.intellij.java")
    testFramework(TestFrameworkType.Platform)
  }
}

// Additional source set for LightPlatform integration tests
val lightTest by sourceSets.creating {
  compileClasspath += sourceSets["test"].compileClasspath + sourceSets["main"].output
  runtimeClasspath += sourceSets["test"].runtimeClasspath + sourceSets["main"].output
}

configurations[lightTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[lightTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

// Use Java 17 toolchain for compiling (IntelliJ 2024.2 requires 17)
kotlin { jvmToolchain(17) }

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
}

configurations.all {
  resolutionStrategy {
    force("org.apache.pdfbox:pdfbox:2.0.30")
    force("org.apache.pdfbox:fontbox:2.0.30")
  }
}

tasks {
  runIde { jvmArgs = listOf("-Xmx2g") }

  test {
    useJUnitPlatform()
    // Ensure tests run on JDK 17 to match IntelliJ 2024.2 runtime
    val toolchains = project.extensions.getByType(org.gradle.jvm.toolchain.JavaToolchainService::class.java)
    javaLauncher.set(toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    // Attach coroutines agent required by the IntelliJ test framework
    val agent = project.layout.projectDirectory.file(".intellijPlatform/coroutines-javaagent-legacy.jar").asFile
    if (agent.exists()) {
      jvmArgs("-javaagent:${agent.absolutePath}")
    }
  }

  jacocoTestReport {
    dependsOn(test)
    if (providers.systemProperty("enableLightTests").orNull == "true") {
      dependsOn("lightTest")
    }
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
      "net/samcaldwell/latex/**"
    )

    val classDirs = files(sourceSets["main"].output.classesDirs)
    classDirectories.setFrom(
      classDirs.asFileTree.matching {
        // First include nothing, then add explicit includes
        exclude(excludePatterns)
        include(includePatterns)
      }
    )
    executionData(fileTree(layout.buildDirectory.dir("jacoco")).matching { include("*.exec") })
  }

  jacocoTestCoverageVerification {
    dependsOn(test)
    if (providers.systemProperty("enableLightTests").orNull == "true") {
      dependsOn("lightTest")
    }
    violationRules {
      // Global minimum for the whole plugin package
      rule {
        element = "BUNDLE"
        limit {
          // Minimum overall coverage required
          counter = "INSTRUCTION"
          value = "COVEREDRATIO"
          minimum = BigDecimal("0.80")
        }
      }

      // Ensure BibLibraryService specifically also meets the threshold
      rule {
        element = "CLASS"
        includes = listOf("net.samcaldwell.latex.BibLibraryService")
        limit {
          counter = "INSTRUCTION"
          value = "COVEREDRATIO"
          minimum = BigDecimal("0.80")
        }
      }
    }
    val excludePatterns = listOf("**/*")
    val includePatterns = listOf("net/samcaldwell/latex/**")
    val classDirs = files(sourceSets["main"].output.classesDirs)
    classDirectories.setFrom(
      classDirs.asFileTree.matching {
        exclude(excludePatterns)
        include(includePatterns)
      }
    )
    executionData(fileTree(layout.buildDirectory.dir("jacoco")).matching { include("*.exec") })
  }

  // Also gate 'check' on coverage verification so CI fails appropriately
  check { dependsOn(jacocoTestCoverageVerification) }

  // LightPlatform tests task for CI
  register<Test>("lightTest") {
    description = "Runs LightPlatform integration tests"
    group = "verification"
    testClassesDirs = sourceSets["lightTest"].output.classesDirs
    classpath = sourceSets["lightTest"].runtimeClasspath
    useJUnit()
    systemProperty("idea.is.unit.test", "true")
    systemProperty("idea.home.path", layout.projectDirectory.asFile.absolutePath)
    systemProperty("enableLightTests", "true")
    systemProperty("idea.log.assert.disabled", "true")

    val toolchains = project.extensions.getByType(org.gradle.jvm.toolchain.JavaToolchainService::class.java)
    javaLauncher.set(toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })

    val agent = project.layout.projectDirectory.file(".intellijPlatform/coroutines-javaagent-legacy.jar").asFile
    if (agent.exists()) {
      jvmArgs("-javaagent:${agent.absolutePath}")
    }
  }
}

publishing {
  publications {
    create<MavenPublication>("pluginZip") {
      groupId = project.group.toString()
      artifactId = "intellij-latex-plugin"
      version = project.version.toString()

      artifact(layout.buildDirectory.file("distributions/${'$'}{project.name}-${'$'}{project.version}.zip")) {
        extension = "zip"
        builtBy(tasks.named("buildPlugin"))
      }
      pom {
        name.set("intellij-latex-plugin")
        description.set("LaTeX and Bibliography Manager IntelliJ plugin distribution")
      }
    }
  }
  repositories {
    maven {
      name = "GitHubPackages"
      val repo = providers.gradleProperty("github.repo").orElse(System.getenv("GITHUB_REPOSITORY") ?: "OWNER/REPO").get()
      url = uri("https://maven.pkg.github.com/${'$'}repo")
      credentials {
        username = providers.environmentVariable("GITHUB_ACTOR").orElse(System.getenv("GITHUB_ACTOR") ?: "").get()
        password = providers.environmentVariable("GITHUB_TOKEN").orElse(System.getenv("GITHUB_TOKEN") ?: "").get()
      }
    }
  }
}
