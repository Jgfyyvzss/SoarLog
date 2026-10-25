import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "io.github.jgfyyvzss.soarlog"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.jgfyyvzss.soarlog"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // Debug builds and test runs install as a separate app, so they never replace or uninstall the
        // pilot's own copy and its logbook.
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Lists the app's languages (from the values-* folders) so Android 13+ offers it in the per-app language
    // setting. The default language is named in res/resources.properties.
    androidResources {
        generateLocaleConfig = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

/**
 * Copies a built APK to `SoarLog-<versionName>-<buildType>.apk`, because AGP 9 no longer lets a build rename its
 * own output. Follows the listenToArtifacts recipe in android/gradle-recipes (agp-9.2 branch, checked 2026-10-23).
 */
abstract class NamedApkCopy : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val input: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:Internal
    abstract val builtArtifactsLoader: Property<BuiltArtifactsLoader>

    @get:Input
    abstract val buildType: Property<String>

    @TaskAction
    fun copy() {
        val dir = output.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        val built = builtArtifactsLoader.get().load(input.get()) ?: throw GradleException("Cannot load the built APK")
        built.elements.forEach { apk ->
            val version = apk.versionName?.takeIf { it.isNotBlank() }?.let { "-$it" }.orEmpty()
            val target = File(dir, "SoarLog$version-${buildType.get()}.apk")
            Files.copy(File(apk.outputFile).toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            logger.lifecycle("Named APK: ${target.path}")
        }
    }
}

// The release APK is also saved as app/build/outputs/named_apks/release/SoarLog-<versionName>-release.apk.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val copy = tasks.register<NamedApkCopy>("nameApkFor${variant.name.replaceFirstChar { it.uppercase() }}") {
            output.set(layout.buildDirectory.dir("outputs/named_apks/${variant.name}"))
            builtArtifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
            buildType.set(variant.buildType ?: variant.name)
        }
        variant.artifacts.use(copy).wiredWith { it.input }.toListenTo(SingleArtifact.APK)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.paging.compose)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    // Declared directly so the app and its device tests use the same coroutines version as kotlinx-coroutines-test.
    implementation(libs.kotlinx.coroutines.android)
    // Reads which way up a camera photo is, so a witness photo can be turned upright before it is shrunk.
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Test only: a local server standing in for WeGlide, so no automated test ever calls the real API.
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
