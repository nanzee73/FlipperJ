import org.gradle.internal.os.OperatingSystem

plugins {
    application
    // single runnable jar with all dependencies (merges the ImageIO plugin registrations)
    id("com.gradleup.shadow") version "8.3.5"
}

group = "de.flipperj"
// The release workflow passes the tag version, e.g. -PappVersion=1.2.0 for tag v1.2.0.
// macOS installers require the first number to be at least 1.
version = (findProperty("appVersion") as String?) ?: "1.0.0"

repositories {
    mavenCentral()
    // C64GfxLib (shared code with MuleJ), built by JitPack from the public repository – no login needed.
    // To work on the library at the same time, see settings.gradle.kts (-Pc64gfxLocal).
    maven("https://jitpack.io") { content { includeGroupByRegex("com\\.github\\.nanzee73.*") } }
}

val c64gfxVersion = "v0.10.0"

val twelveMonkeys = "3.12.0"

dependencies {
    implementation("com.github.nanzee73.C64GfxLib:c64gfx-swing:$c64gfxVersion")   // includes c64gfx-core

    // Extra ImageIO readers (registered automatically via SPI): WebP, TIFF, PSD, TGA, PCX, PNM, IFF/ILBM,
    // SGI, PICT, ICNS and an improved BMP/JPEG reader. Only needed at runtime.
    listOf("webp", "tiff", "psd", "tga", "pcx", "pnm", "iff", "sgi", "pict", "icns", "bmp", "jpeg").forEach {
        runtimeOnly("com.twelvemonkeys.imageio:imageio-$it:$twelveMonkeys")
    }

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
}

application {
    mainClass.set("de.flipperj.App")
    applicationName = "flipperj"
}

tasks.jar {
    archiveBaseName.set("flipperj")
    manifest {
        attributes(
            "Implementation-Title" to "FlipperJ",
            "Implementation-Version" to (System.getenv("DISPLAY_VERSION") ?: project.version),
            "Main-Class" to "de.flipperj.App"
        )
    }
}

// build/libs/flipperj-<version>-all.jar – start with: java -jar flipperj-<version>-all.jar
tasks.shadowJar {
    archiveBaseName.set("flipperj")
    archiveClassifier.set("all")
    mergeServiceFiles()
}

// License texts inside the jars and next to the start scripts
tasks.withType<Jar>().configureEach { from(files("LICENSE", "THIRD-PARTY-NOTICES.md")) { into("META-INF") } }
distributions { main { contents { from("LICENSE", "THIRD-PARTY-NOTICES.md") } } }

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
}

// ---------------------------------------------------------------------------------------------
// Native packages with a bundled Java runtime (jpackage, part of JDK 17+).
// Must run on the target system: dmg on macOS, msi on Windows, deb on Linux.
//   ./gradlew jpackage                          -> installer for this OS
//   ./gradlew jpackage -PinstallerType=app-image -> portable app folder (no installation)
// Output: build/jpackage
// ---------------------------------------------------------------------------------------------
val os: OperatingSystem = OperatingSystem.current()

tasks.register<Exec>("jpackage") {
    group = "distribution"
    description = "Builds a native installer with a bundled Java runtime for the current OS."
    dependsOn("installDist")

    val appVersion = project.version.toString()
    // macOS installers take only 1-3 numbers and refuse a first 0: the .dmg gets 1.x.y instead of 0.x.y and
    // drops a fourth number (0.7.3.666 -> 1.7.3; only inside the installer, file names keep the real version)
    val installerVersion = if (!os.isMacOsX) appVersion else {
        val parts = appVersion.split('.', '-').filter { it.isNotEmpty() && it.all(Char::isDigit) }.take(3).toMutableList()
        if (parts.isEmpty()) parts.add("1")
        if (parts[0] == "0") parts[0] = "1"
        parts.joinToString(".")
    }
    val input = layout.buildDirectory.dir("install/flipperj/lib").get().asFile
    val dest = layout.buildDirectory.dir("jpackage").get().asFile
    val type = (findProperty("installerType") as String?) ?: when {
        os.isMacOsX -> "dmg"
        os.isWindows -> "msi"
        else -> "deb"
    }
    val icon = file(when {
        os.isMacOsX -> "packaging/flipperj.icns"
        os.isWindows -> "packaging/flipperj.ico"
        else -> "packaging/flipperj.png"
    })
    val jpackage = File(System.getProperty("java.home"), "bin/jpackage" + if (os.isWindows) ".exe" else "")

    val args = mutableListOf(
        "--type", type,
        "--name", "FlipperJ",
        "--app-version", installerVersion,
        "--vendor", "FlipperJ",
        "--description", "FLI / AFLI / IFLI image converter for the Commodore 64",
        "--copyright", "Copyright (c) 2026 nanzee73, PolyForm Noncommercial License 1.0.0",
        "--input", input.path,
        "--main-jar", "flipperj-$appVersion.jar",
        "--main-class", "de.flipperj.App",
        "--icon", icon.path,
        "--dest", dest.path,
        "--java-options", "-Xmx" + ((findProperty("maxHeap") as String?)?.takeIf { it.isNotBlank() } ?: "2g")
    )
    if (type != "app-image") args += listOf("--license-file", "LICENSE")
    when {
        os.isMacOsX -> args += listOf(
            "--mac-package-name", "FlipperJ",
            "--mac-package-identifier", "de.flipperj",
            "--mac-app-category", "public.app-category.graphics-design"
        )
        os.isWindows -> if (type != "app-image") args += listOf(
            "--win-menu", "--win-menu-group", "FlipperJ", "--win-shortcut", "--win-dir-chooser",
            "--win-upgrade-uuid", "6f1c2e3a-7b4d-4c5e-9a8f-0d1e2f3a4b5c"
        )
        else -> if (type != "app-image") args += listOf(
            "--linux-package-name", "flipperj",
            "--linux-shortcut", "--linux-menu-group", "Graphics", "--linux-app-category", "graphics"
        )
    }

    executable = jpackage.path
    setArgs(args)
    doFirst { delete(dest) }
}
