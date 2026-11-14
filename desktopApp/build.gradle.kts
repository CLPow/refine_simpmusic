@file:Suppress("UnstableApiUsage")

import org.apache.commons.io.FileUtils
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.conveyor)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.hotReload)
}

version =
    libs.versions.version.name
        .get()
        .removeSuffix("-hf")

kotlin {
    jvmToolchain(21)

    jvm {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
                }
            }
        }
    }

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":composeApp"))

                implementation(compose.desktop.currentOs)

                implementation(libs.kotlinx.coroutinesSwing)

                implementation(libs.sentry.jvm)

                implementation(libs.native.tray)

                implementation(libs.org.json)
            }
        }
    }
}

project.configurations.create("desktopRuntimeClasspath") {
    extendsFrom(project.configurations.getByName("jvmRuntimeClasspath"))
}

dependencies {
    linuxAarch64(libs.compose.linux.arm64)
    linuxAmd64(libs.compose.linux.x64)
    macAarch64(libs.compose.macos.arm64)
    macAmd64(libs.compose.macos.x64)
    windowsAarch64(libs.compose.windows.arm64)
    windowsAmd64(libs.compose.windows.x64)
}

tasks.named<hydraulic.conveyor.gradle.WriteConveyorConfigTask>("writeConveyorConfig") {
    dependsOn(tasks.named("proguardReleaseJars"))
    val proguardJarsDir = layout.buildDirectory.dir("compose/tmp/main-release/proguard")
    doLast {
        destination.get().asFile.appendText(
            """
            |app.fsname = simpmusic
            |app.display-name = SimpMusic
            |app.rdns-name = com.maxrave.simpmusic
            |
            |// Override the Gradle-detected classpath with the ProGuard'd
            |// jar directory. Conveyor expands a directory entry to every
            |// file inside it — saves ~750 MB raw / ~100 MB compressed in
            |// the resulting AppImage by replacing 221 raw jars with the
            |// shrunk equivalents from compose.desktop's proguard task.
            |app.inputs = [
            |    "${proguardJarsDir.get().asFile.absolutePath}"
            |]
            """.trimMargin() + "\n",
        )
    }
}

compose.desktop {
    application {
        mainClass = "com.maxrave.simpmusic.MainKt"
        jvmArgs += "--add-opens=java.base/java.nio=ALL-UNNAMED"

        nativeDistributions {
            appResourcesRootDir = rootDir.resolve("mpv-natives/")
            val listTarget = mutableListOf<TargetFormat>()
            if (org.gradle.internal.os.OperatingSystem
                    .current()
                    .isMacOsX
            ) {
                listTarget.addAll(
                    listOf(TargetFormat.Dmg, TargetFormat.Msi),
                )
            } else {
                listTarget.addAll(
                    listOf(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.AppImage),
                )
            }
            targetFormats(*listTarget.toTypedArray())
            modules("jdk.unsupported")
            packageName = "RefineMusic"
            macOS {
                val formatedDate =
                    Instant.now().let {
                        DateTimeFormatter
                            .ofPattern("yyyy.MM.dd")
                            .withZone(ZoneId.of("UTC"))
                            .format(it)
                    }
                includeAllModules = true
                packageVersion = formatedDate
                iconFile.set(rootDir.resolve("composeApp/icon/circle_app_icon.icns"))
                val macExtraPlistKeys =
                    """
                    <key>LSApplicationCategoryType</key>
                    <string>public.app-category.music</string>
                    <key>UIBackgroundModes</key>
                    <array>
                        <string>audio</string>
                        <string>fetch</string>
                        <string>processing</string>
                    </array>
                    <key>CFBundleURLTypes</key>
                    <array>
                        <dict>
                            <key>CFBundleTypeRole</key>
                            <string>Viewer</string>
                            <key>CFBundleURLName</key>
                            <string>com.maxrave.simpmusic.deeplink</string>
                            <key>CFBundleURLSchemes</key>
                            <array>
                                <string>simpmusic</string>
                            </array>
                        </dict>
                    </array>
                    """.trimIndent()
                infoPlist {
                    extraKeysRawXml = macExtraPlistKeys
                }
            }
            windows {
                includeAllModules = true
                packageVersion =
                    libs.versions.version.name
                        .get()
                        .removeSuffix("-hf")
                iconFile.set(rootDir.resolve("composeApp/icon/circle_app_icon.ico"))
                upgradeUuid = "83ce44d7-5433-4a68-a7e8-4332347310fb"
                perUserInstall = true
                dirChooser = true
                menu = true
                menuGroup = "Refine Music"
                shortcut = true
            }
            linux {
                includeAllModules = true
                packageVersion =
                    libs.versions.version.name
                        .get()
                        .removeSuffix("-hf")
                iconFile.set(rootDir.resolve("composeApp/icon/circle_app_icon.png"))
            }
        }

        buildTypes.release.proguard {
            version.set("7.8.1")
            optimize.set(true)
            obfuscate.set(true)
            configurationFiles.from(rootDir.resolve("composeApp/proguard-desktop-rules.pro"))
        }
    }
}

afterEvaluate {
    tasks.withType<JavaExec> {
        jvmArgs("--add-opens", "java.desktop/sun.awt=ALL-UNNAMED")
        jvmArgs("--add-opens", "java.desktop/java.awt.peer=ALL-UNNAMED")
        jvmArgs("--add-opens", "java.base/java.nio=ALL-UNNAMED")

        jvmArgs("-XX:ErrorFile=${rootProject.layout.buildDirectory.get().asFile}/hs_err_pid%p.log")

        val osArch = System.getProperty("os.arch").lowercase()
        val osSubDir =
            when {
                System.getProperty("os.name").contains("Mac") -> {
                    if (osArch.contains("aarch64")) "macos-arm64" else "macos-x64"
                }
                System.getProperty("os.name").contains("Win") -> {
                    if (osArch.contains("aarch64")) "windows-arm64" else "windows-x64"
                }
                else -> {
                    "linux-x64"
                }
            }
        val mpvNativesPath = rootDir.resolve("mpv-natives/$osSubDir")
        if (mpvNativesPath.isDirectory) {
            systemProperty("mpv.bundled.path", mpvNativesPath.absolutePath)
        } else {
            logger.info(
                "[mpv] ${mpvNativesPath.name} not staged yet — run " +
                    "`./gradlew :composeApp:mpvSetupAll`. Falling back to a system libmpv.",
            )
        }

        if (System.getProperty("os.name").contains("Mac")) {
            jvmArgs("--add-opens", "java.desktop/sun.awt=ALL-UNNAMED")
            jvmArgs("--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED")
            jvmArgs("--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED")
        }
    }
}

val conveyorMakeLinuxApp =
    tasks.register<Exec>("conveyorMakeLinuxApp") {
        group = "distribution"
        description = "Run `conveyor make linux-app` for Linux x86_64 (glibc)."
        dependsOn(":composeApp:mpvSetupAll")
        workingDir = rootDir
        commandLine(
            "conveyor",
            "--agree-to-license=1",
            "-Kapp.machines=linux.amd64.glibc",
            "make",
            "linux-app",
        )
        standardInput = System.`in`
    }

tasks.register("packageConveyorAppImage") {
    group = "distribution"
    description = "Wrap Conveyor's linux-app output (./output) into a portable .AppImage."
    notCompatibleWithConfigurationCache(
        "Reads project/layout/libs from within doLast to compute appimage paths.",
    )

    doLast {
        val appName = "SimpMusic"
        val conveyorOutput = rootDir.resolve("output")
        if (!conveyorOutput.exists()) {
            throw GradleException(
                "Conveyor output (./output) not found. Run `conveyor " +
                    "-Kapp.machines=linux.amd64.glibc make linux-app` first.",
            )
        }

        val appimagetool =
            layout.buildDirectory
                .dir("tmp")
                .get()
                .asFile
                .resolve("appimagetool-x86_64.AppImage")
        if (!appimagetool.exists()) {
            downloadFile(
                "https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage",
                appimagetool,
            )
        }
        if (!appimagetool.canExecute()) {
            appimagetool.setExecutable(true)
        }

        val appDir =
            layout.buildDirectory
                .dir("appimage/conveyor/$appName.AppDir")
                .get()
                .asFile
        if (appDir.exists()) {
            appDir.deleteRecursively()
        }

        val appDirSrc = rootDir.resolve("composeApp/appimage")
        if (appDirSrc.exists()) {
            FileUtils.copyDirectory(appDirSrc, appDir)
        } else {
            appDir.mkdirs()
        }
        FileUtils.copyDirectory(conveyorOutput, appDir)

        val iconSrc = rootDir.resolve("composeApp/icon/circle_app_icon.png")
        val iconDst = appDir.resolve("simpmusic.png")
        if (!iconDst.exists() && iconSrc.exists()) {
            FileUtils.copyFile(iconSrc, iconDst)
        }

        val versionName =
            libs.versions.version.name
                .get()
        val desktopFile = appDir.resolve("simpmusic.desktop")
        desktopFile.writeText(
            """[Desktop Entry]
            |Type=Application
            |Version=1.0
            |Name=SimpMusic
            |Comment=SimpMusic v$versionName - FOSS YouTube Music Client
            |Exec=bin/simpmusic %u
            |Icon=simpmusic
            |Terminal=false
            |Categories=Audio;AudioVideo;
            |StartupWMClass=SimpMusic
            |MimeType=x-scheme-handler/simpmusic;x-scheme-handler/wordbyword;
            |
            """.trimMargin(),
        )

        val appRun = appDir.resolve("AppRun")
        appRun.writeText(
            """#!/bin/sh
            |
            |SELF=${'$'}(readlink -f "${'$'}0")
            |HERE=${'$'}{SELF%/*}
            |
            |# Install icon into XDG dirs so GNOME/KDE pick it up the first time.
            |ICON_DIR="${'$'}HOME/.local/share/icons/hicolor/256x256/apps"
            |if [ ! -f "${'$'}ICON_DIR/simpmusic.png" ] || [ "${'$'}HERE/simpmusic.png" -nt "${'$'}ICON_DIR/simpmusic.png" ]; then
            |    mkdir -p "${'$'}ICON_DIR"
            |    cp "${'$'}HERE/simpmusic.png" "${'$'}ICON_DIR/simpmusic.png"
            |    gtk-update-icon-cache -f -t "${'$'}HOME/.local/share/icons/hicolor" 2>/dev/null || true
            |fi
            |
            |# Install .desktop file with absolute Exec path to the AppImage.
            |DESKTOP_DIR="${'$'}HOME/.local/share/applications"
            |mkdir -p "${'$'}DESKTOP_DIR"
            |APPIMAGE_PATH="${'$'}{APPIMAGE:-${'$'}SELF}"
            |sed "s|Exec=bin/simpmusic|Exec=${'$'}APPIMAGE_PATH|" "${'$'}HERE/simpmusic.desktop" > "${'$'}DESKTOP_DIR/com-maxrave-simpmusic-MainKt.desktop"
            |update-desktop-database "${'$'}DESKTOP_DIR" 2>/dev/null || true
            |
            |# Cap glibc's per-thread malloc arenas.
            |#
            |# glibc spawns a fresh 64 MB-aligned arena whenever it sees mutex contention, up to
            |# 8 x nproc of them, and never gives an arena back to the OS. Measured on a 20-core
            |# box: 161 arenas holding 1.45 GB of a 1.9 GB RSS, while the JVM heap was using only
            |# 121 MB of its 512 MB cap. Pinning the count to 2 took that to ~5 arenas. The cost is
            |# more allocator lock contention, which this workload does not notice - the audio path
            |# is native and its buffers are long-lived.
            |#
            |# Linux-only by construction: this file only exists inside the AppImage. macOS and
            |# Windows tune their allocators elsewhere, and MemoryTrimmer covers all three at runtime.
            |export MALLOC_ARENA_MAX=2
            |
            |cd "${'$'}HERE"
            |exec bin/simpmusic "${'$'}@"
            |
            """.trimMargin(),
        )
        appRun.setExecutable(true, false)

        val appExecutable = appDir.resolve("bin/simpmusic")
        if (appExecutable.exists() && !appExecutable.canExecute()) {
            appExecutable.setExecutable(true)
        }

        val outputAppImage = appDir.parentFile.resolve("$appName-x86_64.AppImage")
        val process =
            ProcessBuilder(
                appimagetool.canonicalPath,
                "$appName.AppDir",
                outputAppImage.name,
            ).directory(appDir.parentFile)
                .apply { environment()["ARCH"] = "x86_64" }
                .inheritIO()
                .start()

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException("appimagetool failed with exit code $exitCode")
        }
        println("[AppImage] Built: ${outputAppImage.absolutePath}")
    }
}

tasks.register("buildLinuxAppImage") {
    group = "distribution"
    description = "Full SimpMusic Desktop Linux AppImage build pipeline (mpvSetupAll → conveyor → AppImage)."
    dependsOn(conveyorMakeLinuxApp)
    finalizedBy("packageConveyorAppImage")
}

val conveyorMakeMacZipAmd64 =
    tasks.register<Exec>("conveyorMakeMacZipAmd64") {
        group = "distribution"
        description = "Run `conveyor make unnotarized-mac-zip` for macOS Intel."
        dependsOn(":composeApp:mpvSetupAll")
        workingDir = rootDir
        commandLine(
            "conveyor",
            "--agree-to-license=1",
            "-Kapp.machines=mac.amd64",
            "make",
            "unnotarized-mac-zip",
        )
        standardInput = System.`in`
    }

val conveyorMakeMacZipAarch64 =
    tasks.register<Exec>("conveyorMakeMacZipAarch64") {
        group = "distribution"
        description = "Run `conveyor make unnotarized-mac-zip` for macOS Apple Silicon."
        dependsOn(":composeApp:mpvSetupAll")
        workingDir = rootDir
        commandLine(
            "conveyor",
            "--agree-to-license=1",
            "-Kapp.machines=mac.aarch64",
            "make",
            "unnotarized-mac-zip",
        )
        standardInput = System.`in`
    }

tasks.register("buildMacZipAmd64") {
    group = "distribution"
    description = "Full SimpMusic Desktop macOS Intel .zip pipeline (mpvSetupAll → conveyor)."
    dependsOn(conveyorMakeMacZipAmd64)
}

tasks.register("buildMacZipAarch64") {
    group = "distribution"
    description = "Full SimpMusic Desktop macOS Apple Silicon .zip pipeline (mpvSetupAll → conveyor)."
    dependsOn(conveyorMakeMacZipAarch64)
}

val conveyorMakeWindowsMsix =
    tasks.register<Exec>("conveyorMakeWindowsMsix") {
        group = "distribution"
        description = "Run `conveyor make windows-msix` for Windows x86_64."
        dependsOn(":composeApp:mpvSetupAll")
        workingDir = rootDir
        commandLine(
            "conveyor",
            "--agree-to-license=1",
            "-Kapp.machines=windows.amd64",
            "make",
            "windows-msix",
        )
        standardInput = System.`in`
    }

tasks.register("buildWindowsMsix") {
    group = "distribution"
    description = "Full SimpMusic Desktop Windows .msix pipeline (mpvSetupAll → conveyor)."
    dependsOn(conveyorMakeWindowsMsix)
}

tasks.withType<AbstractJPackageTask>().configureEach {
    notCompatibleWithConfigurationCache("Compose Desktop JPackage tasks are not yet compatible with configuration cache")
}

private fun downloadFile(
    url: String,
    destFile: java.io.File,
) {
    val destParent = destFile.parentFile
    destParent.mkdirs()

    if (destFile.exists()) {
        destFile.delete()
    }

    println("Download $url")
    URI(url).toURL().openStream().use { input ->
        destFile.outputStream().use { output ->
            input.copyTo(output)
        }
    }
    println("Download finish")
}