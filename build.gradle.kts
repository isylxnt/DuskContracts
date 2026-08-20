import java.util.zip.ZipFile
import java.security.MessageDigest

plugins {
    java
}

group = "dev.isylxnt"
version = "1.0"

val hikariVersion = "5.1.0"
val sqliteVersion = "3.46.1.0"
val mysqlVersion = "9.0.0"
val mariadbVersion = "3.4.1"
val paperLibraries = listOf(
    "com.zaxxer:HikariCP:$hikariVersion",
    "org.xerial:sqlite-jdbc:$sqliteVersion",
    "com.mysql:mysql-connector-j:$mysqlVersion",
    "org.mariadb.jdbc:mariadb-java-client:$mariadbVersion"
)

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(26))
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-parameters"))
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("org.jetbrains:annotations:24.1.0")

    implementation("com.zaxxer:HikariCP:$hikariVersion") { exclude(group = "org.slf4j") }
    implementation("org.xerial:sqlite-jdbc:$sqliteVersion") { exclude(group = "org.slf4j") }
    implementation("com.mysql:mysql-connector-j:$mysqlVersion")
    implementation("org.mariadb.jdbc:mariadb-java-client:$mariadbVersion") { exclude(group = "com.github.waffle", module = "waffle-jna") }

    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.testcontainers:junit-jupiter:1.20.1")
    testImplementation("org.testcontainers:mysql:1.20.1")
    testImplementation("org.testcontainers:mariadb:1.20.1")
}

tasks.processResources {
    inputs.properties(
        "version" to project.version,
        "hikariVersion" to hikariVersion,
        "sqliteVersion" to sqliteVersion,
        "mysqlVersion" to mysqlVersion,
        "mariadbVersion" to mariadbVersion
    )
    filesMatching("plugin.yml") {
        expand(
            "version" to project.version,
            "hikariVersion" to hikariVersion,
            "sqliteVersion" to sqliteVersion,
            "mysqlVersion" to mysqlVersion,
            "mariadbVersion" to mariadbVersion
        )
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("file.encoding", "UTF-8")
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(rootProject.file("LICENSE")) {
        into("META-INF")
    }
}

tasks.jar {
    archiveBaseName.set("DuskContracts")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")
}

val verifyJar = tasks.register("verifyJar") {
    group = "verification"
    dependsOn(tasks.jar)
    doLast {
        val jar = tasks.jar.get().archiveFile.get().asFile
        check(jar.name == "DuskContracts-1.0.jar") { "Unexpected artifact name: ${jar.name}" }
        ZipFile(jar).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            check("plugin.yml" in names) { "plugin.yml is missing" }
            check("META-INF/LICENSE" in names) { "MIT license is missing" }
            check(names.none { it.startsWith("org/bukkit/") || it.startsWith("io/papermc/paper/") }) {
                "Server API was accidentally bundled"
            }
            check(names.none { it.startsWith("net/milkbowl/") || it.startsWith("me/clip/") }) { "Optional API was accidentally bundled" }
            val forbiddenLibraries = listOf("com/zaxxer/", "org/sqlite/", "com/mysql/", "org/mariadb/", "com/google/protobuf/", "dev/isylxnt/duskcontracts/libs/")
            check(names.none { name -> forbiddenLibraries.any(name::startsWith) }) { "A runtime library was accidentally bundled in the thin JAR" }
            val pluginClass = zip.getInputStream(zip.getEntry("dev/isylxnt/duskcontracts/bootstrap/DuskContractsPlugin.class")).readNBytes(8)
            val major = (pluginClass[6].toInt() and 0xff) shl 8 or (pluginClass[7].toInt() and 0xff)
            check(major == 61) { "Expected Java 17 class version 61, got $major" }
            val pluginYaml = zip.getInputStream(zip.getEntry("plugin.yml")).bufferedReader().readText()
            check("libraries:" in pluginYaml) { "plugin.yml does not declare Paper runtime libraries" }
            paperLibraries.forEach { library ->
                check("- $library" in pluginYaml) { "plugin.yml is missing runtime library $library" }
            }
        }
    }
}

tasks.check { dependsOn(verifyJar) }

val runtimeDriverSmoke = tasks.register<JavaExec>("runtimeDriverSmoke") {
    group = "verification"
    dependsOn(tasks.testClasses)
    mainClass.set("dev.isylxnt.duskcontracts.persistence.RuntimeDriverSmoke")
    classpath = sourceSets.test.get().runtimeClasspath
}
tasks.check { dependsOn(runtimeDriverSmoke) }

val compatibilityTargets = mapOf(
    "Paper1206" to "io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT",
    "Paper12111" to "io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT",
    "Paper262" to "io.papermc.paper:paper-api:26.2.build.112-stable",
    "Folia1201" to "dev.folia:folia-api:1.20.1-R0.1-SNAPSHOT",
    "Folia1206" to "dev.folia:folia-api:1.20.6-R0.1-SNAPSHOT",
    "Folia12111" to "dev.folia:folia-api:1.21.11-R0.1-SNAPSHOT",
    "Folia262" to "dev.folia:folia-api:26.2.build.4-beta"
)
val compatibilityCompileTasks = compatibilityTargets.map { (label, api) ->
    val classpath = configurations.create("${label.replaceFirstChar(Char::lowercase)}CompileClasspath") {
        isCanBeResolved = true
        extendsFrom(configurations.implementation.get())
    }
    dependencies.add(classpath.name, api)
    dependencies.add(classpath.name, "me.clip:placeholderapi:2.11.6")
    dependencies.add(classpath.name, "org.jetbrains:annotations:24.1.0")
    tasks.register<JavaCompile>("compileAgainst$label") {
        description = "Compile the common source against $api"
        source(sourceSets.main.get().java)
        this.classpath = classpath
        destinationDirectory.set(layout.buildDirectory.dir("compatibility/$label"))
        options.release.set(17)
        options.encoding = "UTF-8"
    }
}
tasks.register("compatibilityCompile") {
    group = "verification"
    dependsOn(compatibilityCompileTasks)
}

val dependencyChecksumFile = layout.projectDirectory.file("gradle/dependency-checksums.txt")
val verifiedConfigurationNames = listOf(
    "runtimeClasspath", "testRuntimeClasspath",
    "paper1206CompileClasspath", "paper12111CompileClasspath", "paper262CompileClasspath",
    "folia1201CompileClasspath", "folia1206CompileClasspath", "folia12111CompileClasspath", "folia262CompileClasspath"
)
fun resolvedDependencyChecksums(): List<String> = verifiedConfigurationNames.flatMap { configurationName ->
    configurations.getByName(configurationName).resolvedConfiguration.resolvedArtifacts.map { artifact ->
        val digest = MessageDigest.getInstance("SHA-256")
        artifact.file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        "$configurationName|${artifact.moduleVersion.id}|${artifact.file.name}|${digest.digest().joinToString("") { "%02x".format(it) }}"
    }
}.distinct().sorted()

tasks.register("generateDependencyChecksums") {
    group = "build setup"
    doLast {
        dependencyChecksumFile.asFile.parentFile.mkdirs()
        dependencyChecksumFile.asFile.writeText(resolvedDependencyChecksums().joinToString("\n", postfix = "\n"))
    }
}
val verifyDependencies = tasks.register("verifyDependencies") {
    group = "verification"
    doLast {
        check(dependencyChecksumFile.asFile.isFile) { "Run generateDependencyChecksums after an intentional dependency change" }
        val expected = dependencyChecksumFile.asFile.readLines().filter(String::isNotBlank)
        val actual = resolvedDependencyChecksums()
        check(actual == expected) { "Resolved dependency checksums differ from gradle/dependency-checksums.txt" }
    }
}
tasks.check { dependsOn(verifyDependencies) }
