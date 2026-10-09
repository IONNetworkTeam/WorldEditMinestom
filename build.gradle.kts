val worldEditVersion = "7.4.5"

plugins {
    kotlin("jvm") version "2.3.20"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "de.ionnetwork"
version = "1.0"

repositories {
    mavenCentral()
    mavenLocal()                                        // de.ionnetwork:minestom-extension-api
    maven(url = "https://jitpack.io")                   // Minestom
    maven(url = "https://maven.enginehub.org/repo/")    // worldedit-core
}

dependencies {
    // Provided by the running MinestomConversion server, never bundled: bundling either would put a
    // second copy of Minestom (or of the extension contract itself) on this jar's classloader, and
    // the loader hands both down from its parent.
    compileOnly("com.github.Minestom:Minestom:29988a8012") {
        exclude(group = "com.github.Minestom.Minestom", module = "testing")
    }
    compileOnly("de.ionnetwork:minestom-extension-api:1.0")

    // Both are on the server's classpath at runtime (Minestom logs through slf4j; worldedit-core
    // declares Guava as a runtime dependency and exposes it in its own signatures), but neither
    // reaches this module's compile classpath on its own.
    compileOnly("org.slf4j:slf4j-api:2.0.16")
    compileOnly("com.google.guava:guava:33.6.0-jre")

    // WorldEdit, bundled. This is the whole reason this repository exists separately: worldedit-core
    // is GPLv3, so anything linking it is GPLv3 too, and IONPlugins is not.
    implementation("com.sk89q.worldedit:worldedit-core:$worldEditVersion") {
        // worldedit-core asks for the full ANTLR *tool* (a compile-scope dependency of its build),
        // which drags in ICU4J and StringTemplate - about 30 MB of code generator that only runs at
        // ANTLR build time. The generated parsers WorldEdit ships need antlr4-runtime alone, which
        // stays.
        exclude(group = "org.antlr", module = "antlr4")
    }
    runtimeOnly("org.antlr:antlr4-runtime:4.13.2")

    // Used directly for the dense edit buffer, and already present as a worldedit-core runtime
    // dependency - declaring it here just puts it on the compile classpath. It is relocated with
    // everything else, so this jar's copy and WorldEdit's are the same relocated classes.
    implementation("it.unimi.dsi:fastutil:8.5.18")

    // WorldEdit logs through Log4j; the server logs through SLF4J. Without a bridge, Log4j finds no
    // provider and every WorldEdit log line is dropped with an "could not find a logging provider"
    // error at startup. slf4j-api itself is deliberately NOT bundled - it must be the server's, or
    // the bridged lines would go to a second, unconfigured logger.
    runtimeOnly("org.apache.logging.log4j:log4j-to-slf4j:2.26.0")

    testImplementation(kotlin("test"))
    testImplementation("com.github.Minestom:Minestom:29988a8012") {
        exclude(group = "com.github.Minestom.Minestom", module = "testing")
    }
    testImplementation("de.ionnetwork:minestom-extension-api:1.0")
    testImplementation("com.google.guava:guava:33.6.0-jre")
    // The real Minestom, not just its API: the command parser is exercised directly in tests.
    testImplementation("com.github.Minestom:Minestom:29988a8012") {
        exclude(group = "com.github.Minestom.Minestom", module = "testing")
    }
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
}

tasks.shadowJar {
    archiveFileName.set("WorldEditMinestom.jar")
    mergeServiceFiles()

    // WorldEdit reads its own version from the manifest of the jar it was loaded from - ours - and
    // it looks for its own `WorldEdit-Version` key, not the standard `Implementation-Version`
    // (see `WorldEditManifest`). Without it, every log line and `//version` says "(unknown)".
    // `WorldEdit-Kind` names the folder its own error messages tell an admin to look in.
    manifest {
        attributes(
            "Implementation-Title" to "WorldEdit",
            "Implementation-Version" to worldEditVersion,
            "WorldEdit-Version" to worldEditVersion,
            "WorldEdit-Kind" to "MOD"
        )
    }

    // Every third-party library WorldEdit needs is relocated, and this is not defensive tidiness -
    // without it the extension does not start. ExtensionLoader gives each jar a parent-first
    // URLClassLoader, so any package the server's own shaded jar also contains resolves to the
    // server's copy, whatever version that is. The server ships an older Guava, and WorldEdit's
    // PlatformManager calls Maps.toImmutableEnumMap in its constructor, which that copy does not
    // have: NoSuchMethodError before a single command is registered.
    //
    // Relocating moves this jar's copies to package names nothing else can own, so WorldEdit binds
    // to the versions it was compiled against regardless of what the server ships now or later.
    // WorldEdit's own packages (com.sk89q.*, org.enginehub.*) are deliberately left alone: nothing
    // else on the server has them, and they are what the GPL boundary is drawn around.
    val shade = "de.ionnetwork.worldedit.minestom.libs"
    relocate("com.google.common", "$shade.guava")
    relocate("com.google.thirdparty", "$shade.guava.thirdparty")
    relocate("com.google.gson", "$shade.gson")
    relocate("it.unimi.dsi", "$shade.fastutil")
    relocate("org.yaml.snakeyaml", "$shade.snakeyaml")
    relocate("org.mozilla.javascript", "$shade.rhino")
    relocate("org.antlr", "$shade.antlr")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

/**
 * Drops the built extension into a MinestomConversion server's `extensions/` directory.
 * Point `-PextensionTarget=/path/to/server` at the test server, e.g.
 *   ./gradlew deploy -PextensionTarget=$HOME/Development/TestServers/IONNetwork/MinestomConversion
 */
tasks.register<Copy>("deploy") {
    dependsOn(tasks.shadowJar)
    val target = providers.gradleProperty("extensionTarget")
        .orElse("${System.getProperty("user.home")}/Development/TestServers/IONNetwork/MinestomConversion")
    from(tasks.shadowJar)
    into(target.map { "$it/extensions" })
}
