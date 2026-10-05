import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
	kotlin("jvm") version "2.4.10"
	kotlin("plugin.serialization") version "2.4.10"
	id("org.jetbrains.compose") version "1.11.1"
	id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
}

group = "io.github.psd2live"
version = "2.0.4"

// Cubism proprietary binaries under src/main/resources/cubism/ are opt-in only.
// Default jars/distributions must NOT embed them. Enable with:
//   -Ppsd2live.includeCubism=true
// or env PSD2LIVE_INCLUDE_CUBISM=true
val includeCubism: Boolean =
	(findProperty("psd2live.includeCubism")?.toString()?.equals("true", ignoreCase = true) == true) ||
		(System.getenv("PSD2LIVE_INCLUDE_CUBISM")?.equals("true", ignoreCase = true) == true)

val hostOs = System.getProperty("os.name").lowercase().let { os ->
	when {
		os.contains("mac") || os.contains("darwin") -> "macos"
		os.contains("linux") || os.contains("nix") -> "linux"
		else -> "windows"
	}
}
val hostArm = System.getProperty("os.arch").lowercase().let { it.startsWith("aarch64") || it.startsWith("arm") }

kotlin {
	jvmToolchain(21)
	// Kotlin's redundant-null-check bytecode pass can spend minutes in FastAnalyzer on the large
	// Compose/editor methods in this project, especially on a fresh Windows build. HotSpot still
	// optimizes the resulting bytecode at runtime; skipping this compiler pass keeps clean builds usable.
	compilerOptions {
		freeCompilerArgs.add("-Xno-optimize")
	}
}

dependencies {
	implementation(platform("io.ktor:ktor-bom:3.5.1"))
	// Core engine dependencies (ported from Umamo: format, runtime, interop, render, edit)
	implementation(kotlin("reflect"))
	implementation("org.jdom:jdom:1.1.3")
	implementation("com.squareup.okio:okio:3.17.0")
	implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
	implementation("app.cash.sqldelight:sqlite-driver:2.0.2")

	// LWJGL (OpenGL rendering pipeline)
	val lwjglNatives = "natives-$hostOs" + if (hostArm) "-arm64" else ""
	implementation(platform("org.lwjgl:lwjgl-bom:3.4.2"))
	implementation("org.lwjgl:lwjgl")
	implementation("org.lwjgl:lwjgl-opengl")
	// The editing canvas renders on its own hidden-window GL context (io.github.psd2live.render).
	implementation("org.lwjgl:lwjgl-glfw")
	runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
	runtimeOnly("org.lwjgl:lwjgl-opengl::$lwjglNatives")
	runtimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")
	implementation("io.modelcontextprotocol:kotlin-sdk-server:0.15.0")
	implementation("io.ktor:ktor-server-cio")
	implementation("io.ktor:ktor-server-auth")
	implementation("io.ktor:ktor-server-content-negotiation")
	implementation("io.ktor:ktor-server-sse")
	implementation("io.ktor:ktor-serialization-kotlinx-json")
	runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
	implementation("net.java.dev.jna:jna:5.18.0")
	implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
	implementation(compose.desktop.currentOs)
	implementation("org.jetbrains.compose.runtime:runtime:1.11.1")
	implementation("org.jetbrains.compose.foundation:foundation:1.11.1")
	implementation("org.jetbrains.compose.ui:ui:1.11.1")
	implementation("org.jetbrains.compose.material:material:1.11.1")
	testImplementation(kotlin("test"))
}

// JNA and sqlite-jdbc bundle native libraries for every platform. Resolve them with only the build
// host's, so run, tests and every Compose package (app image, Exe, Msi, Deb) carry just those.
abstract class KeepHostNatives : TransformAction<KeepHostNatives.Parameters> {
	interface Parameters : TransformParameters {
		@get:Input val jnaDir: Property<String>
		@get:Input val sqliteDir: Property<String>
	}

	@get:InputArtifact
	abstract val input: Provider<FileSystemLocation>

	override fun transform(outputs: TransformOutputs) {
		val jar = input.get().asFile
		if (!jar.name.startsWith("jna-") && !jar.name.startsWith("sqlite-jdbc-")) {
			outputs.file(input)
			return
		}
		val jnaDir = "com/sun/jna/${parameters.jnaDir.get()}/"
		val sqliteDir = "org/sqlite/native/${parameters.sqliteDir.get()}/"
		// Platform directories are the hyphenated ones (linux-x86-64); ptr/, win32/ etc. hold classes.
		val jnaPlatform = Regex("com/sun/jna/[^/]+-[^/]+/.*")
		fun keep(name: String) = when {
			name.matches(jnaPlatform) -> name.startsWith(jnaDir)
			name.startsWith("org/sqlite/native/") -> name.endsWith("/") || name.startsWith(sqliteDir)
			else -> true
		}
		ZipFile(jar).use { zin ->
			ZipOutputStream(outputs.file(jar.name).outputStream().buffered()).use { zout ->
				for (entry in zin.entries()) {
					if (!keep(entry.name)) continue
					zout.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
					zin.getInputStream(entry).use { it.copyTo(zout) }
					zout.closeEntry()
				}
			}
		}
	}
}

val hostNativesOnly = Attribute.of("psd2live.hostNativesOnly", Boolean::class.javaObjectType)
dependencies {
	attributesSchema { attribute(hostNativesOnly) }
	artifactTypes.getByName("jar") { attributes.attribute(hostNativesOnly, false) }
	registerTransform(KeepHostNatives::class) {
		from.attribute(hostNativesOnly, false)
		to.attribute(hostNativesOnly, true)
		parameters {
			val arch = if (hostArm) "aarch64" else "x86-64"
			jnaDir.set(if (hostOs == "windows") "win32-$arch" else "${hostOs.replace("macos", "darwin")}-$arch")
			sqliteDir.set(mapOf("windows" to "Windows", "macos" to "Mac", "linux" to "Linux")[hostOs] + "/" + if (hostArm) "aarch64" else "x86_64")
		}
	}
}
configurations.runtimeClasspath { attributes.attribute(hostNativesOnly, true) }

tasks.named("compileTestKotlin").configure {
	enabled = true
}

tasks.test {
	enabled = true
	useJUnitPlatform()
	maxHeapSize = "2g"
	// CI keeps no test reports, so a failure's message and stack must reach the log.
	testLogging {
		events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
		exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
		showStackTraces = true
	}
}


// Keep Cubism out of classpath resources unless explicitly opted in.
tasks.processResources {
	if (!includeCubism) {
		exclude("cubism/**")
	}
}

tasks.named<Jar>("jar") {
	if (!includeCubism) {
		exclude("cubism/**")
	}
}

// License texts ship inside every package, under the app's resources directory.
tasks.withType<Sync>().matching { it.name == "prepareAppResources" }.configureEach {
	from("LICENSE", "THIRD_PARTY_NOTICES.md")
	from("licenses") { into("licenses") }
}

compose.desktop {
	application {
		mainClass = "io.github.psd2live.MainKt"
		jvmArgs += listOf("-Xmx8g", "-Dfile.encoding=UTF-8", "-Dsun.java2d.uiScale.enabled=true")
		nativeDistributions {
			// ModelDownloader uses java.net.http.HttpClient. Compose's automatic
			// runtime module scan can miss this API because it is only loaded when
			// the optional texture-upscale workflow is opened.
			// LWJGL (the canvas GPU renderer) reaches native memory through sun.misc.Unsafe.
			modules("java.net.http", "jdk.unsupported")
			// Compose only packages formats supported on the build host; Deb is for Linux.
			targetFormats(
				org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
				org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
				org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
			)
			packageName = "PSD2Live"
			packageVersion = "2.0.4"
			description = "PSD2Live - Automated Live2D Rigging Pipeline"
			copyright = "© 2026 PSD2Live. Licensed under GPL-3.0."
			vendor = "PSD2Live"

			windows {
				iconFile.set(project.file("src/main/resources/icons/psd2live.ico"))
				menuGroup = "PSD2Live"
				upgradeUuid = "8e9c4b1a-2d3e-4f5a-6b7c-8d9e0f1a2b3c"
			}
		}
	}
}

afterEvaluate {
	// The run-gui scripts build through Gradle but start the JVM themselves from these files. Under
	// `gradlew run` the app is a child of the daemon, outside the terminal's process group, so Ctrl+C
	// only reaches it through Gradle's cancellation (and cmd's "Terminate batch job" prompt on Windows).
	val run = tasks.getByName<JavaExec>("run")
	tasks.register("writeRunArgs") {
		group = "compose desktop"
		description = "Builds the app and writes the java launcher and argument file used by run-gui scripts."
		dependsOn(run.taskDependencies)
		doLast {
			val dir = layout.buildDirectory.dir("run").get().asFile.apply { mkdirs() }
			fun quote(arg: String) = "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
			// Gradle adds its daemon's locale; a direct launch should see the user's own.
			val jvmArgs = run.allJvmArgs.filterNot { arg -> listOf("-Duser.country", "-Duser.language", "-Duser.variant").any(arg::startsWith) }
			// Launch from a content-named copy of the app jar: a build while the app runs rewrites
			// build/libs, and classes loaded lazily from the rewritten jar fail (Windows refuses the
			// rewrite instead). Old copies go once nothing holds them; Windows keeps the open one.
			val jar = tasks.getByName<Jar>("jar").archiveFile.get().asFile
			val hash = MessageDigest.getInstance("SHA-256").digest(jar.readBytes())
				.joinToString("") { "%02x".format(it) }.take(16)
			val snapshot = dir.resolve("app-$hash.jar")
			if (!snapshot.exists()) {
				val partial = dir.resolve("${snapshot.name}.${ProcessHandle.current().pid()}.tmp")
				jar.copyTo(partial, overwrite = true)
				Files.move(partial.toPath(), snapshot.toPath(), StandardCopyOption.REPLACE_EXISTING)
			}
			dir.listFiles { file -> file.name.startsWith("app-") && file != snapshot }?.forEach { it.delete() }
			val classpath = run.classpath.files.map { if (it == jar) snapshot else it }
			val args = jvmArgs + listOf("-cp", classpath.joinToString(File.pathSeparator), run.mainClass.get())
			dir.resolve("jvm.args").writeText(args.joinToString("\n", postfix = "\n") { quote(it) })
			dir.resolve("java").writeText(run.javaLauncher.get().executablePath.asFile.absolutePath)
		}
	}

	tasks.findByName("compileTestKotlin")?.enabled = true
	tasks.findByName("test")?.enabled = true
}
