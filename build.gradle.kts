plugins {
  java
  alias(libs.plugins.quarkus)
  alias(libs.plugins.axion.release)
  jacoco
  checkstyle
  pmd
}

repositories {
  mavenCentral()
  mavenLocal()
}

dependencies {
  implementation(enforcedPlatform(libs.quarkus.bom))
  implementation(libs.quarkus.picocli)
  implementation(libs.quarkus.arc)
  implementation(libs.jspecify)
  testImplementation(libs.quarkus.junit5)
  testImplementation(libs.quarkus.junit5.mockito)
  testImplementation(libs.testcontainers.core)
  testImplementation(libs.testcontainers.junit)

}

scmVersion {
  tag {
    prefix.set("v")
    versionSeparator.set("")
  }

  snapshotCreator { _, _ -> "" }

  versionIncrementer { context ->
    val prev = context.currentVersion
    val tagName = "v${prev}"
    val log = ProcessBuilder("git", "log", "--pretty=format:%s%n%b", "$tagName..HEAD")
      .directory(project.rootDir)
      .redirectErrorStream(true)
      .start()
    val commits = log.inputStream.bufferedReader().readText().trim()
    log.waitFor()

    val isBreaking = commits.lines().any { line ->
      line.startsWith("BREAKING CHANGE") || line.contains("!:")
    }
    val hasFeat = commits.lines().any { it.matches(Regex("^feat(\\(.*\\))?[!]?:.*")) }

    @Suppress("DEPRECATION")
    val major = prev.majorVersion.toLong()

    @Suppress("DEPRECATION")
    val minor = prev.minorVersion.toLong()

    @Suppress("DEPRECATION")
    val patch = prev.patchVersion.toLong()

    when {
      isBreaking -> com.github.zafarkhaja.semver.Version.of(major + 1, 0, 0)
      hasFeat -> com.github.zafarkhaja.semver.Version.of(major, minor + 1, 0)
      else -> com.github.zafarkhaja.semver.Version.of(major, minor, patch + 1)
    }
  }
}

group = "com.kemalabdic"
version = scmVersion.version

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

tasks.register<Exec>("installGitHooks") {
  description = "Configures git to use .githooks directory"
  group = "setup"
  commandLine("git", "config", "core.hooksPath", ".githooks")
}

tasks.named("compileJava") {
  dependsOn("installGitHooks")
}

tasks.processResources {
  filesMatching(listOf("banner.txt", "version.properties")) {
    filter(
      org.apache.tools.ant.filters.ReplaceTokens::class,
      "tokens" to mapOf(
        "app.name" to project.name,
        "app.version" to project.version.toString()
      )
    )
  }
}

tasks.withType<JavaCompile> {
  options.encoding = "UTF-8"
  options.compilerArgs.add("-parameters")
}

checkstyle {
  toolVersion = libs.versions.checkstyle.get()
  isIgnoreFailures = false
}

pmd {
  toolVersion = libs.versions.pmd.get()
  isConsoleOutput = true
  isIgnoreFailures = false
  ruleSetFiles = files("config/pmd/pmd-ruleset.xml")
  ruleSets = listOf()
}

tasks.test {
  finalizedBy(tasks.jacocoTestReport, tasks.jacocoTestCoverageVerification)
}

tasks.jacocoTestReport {
  dependsOn(tasks.test)
  reports {
    xml.required.set(true)
    html.required.set(true)
    csv.required.set(true)
  }
}

tasks.jacocoTestCoverageVerification {
  dependsOn(tasks.test)
  violationRules {
    rule {
      limit {
        counter = "COMPLEXITY"
        minimum = "0.90".toBigDecimal()
      }
    }
  }
}
