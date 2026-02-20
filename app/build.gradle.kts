import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val webClientDir = rootProject.file("web-client")
val webNodeModulesDir = webClientDir.resolve("node_modules")
val webAssetsDir = rootProject.file("app/src/main/assets/web")
val webAssetsIndex = webAssetsDir.resolve("index.html")
val npmExecutable = sequenceOf("npm", "npm.cmd", "npm.bat")
    .firstOrNull { candidate ->
        runCatching {
            providers.exec {
                commandLine(candidate, "--version")
            }.result.get().exitValue == 0
        }.isSuccess
    }
val npmCommand = npmExecutable ?: "npm"
val appVersionCode = providers.gradleProperty("app.versionCode").map(String::toInt).orElse(1)
val appVersionName = providers.gradleProperty("app.versionName").orElse("v1.0.0")

android {
    namespace = "com.tudorc.mediabus"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.tudorc.mediabus"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode.get()
        versionName = appVersionName.get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.nanohttpd)
    implementation(libs.okhttp.tls)
    implementation(libs.jmdns)
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.slf4j.nop)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

val installWebClientDeps by tasks.registering(Exec::class) {
    workingDir(webClientDir)
    commandLine(npmCommand, "install", "--no-audit", "--no-fund")
    onlyIf { npmExecutable != null && !webNodeModulesDir.exists() }
}

val buildWebClient by tasks.registering(Exec::class) {
    dependsOn(installWebClientDeps)
    workingDir(webClientDir)
    inputs.files(
        fileTree(webClientDir.resolve("src")),
        fileTree(webClientDir.resolve("public")),
        webClientDir.resolve("index.html"),
        webClientDir.resolve("package.json"),
        webClientDir.resolve("package-lock.json"),
        webClientDir.resolve("vite.config.js")
    )
    outputs.dir(webAssetsDir)
    commandLine(npmCommand, "run", "build")
    onlyIf {
        if (npmExecutable != null) {
            true
        } else if (webAssetsIndex.exists()) {
            logger.lifecycle("npm not found in PATH; using prebuilt web assets at ${webAssetsDir.path}.")
            false
        } else {
            throw GradleException(
                "npm not found in PATH and no prebuilt web assets were found at ${webAssetsDir.path}. " +
                    "Install Node.js/npm, then run the build again."
            )
        }
    }
}

tasks.named("preBuild") {
    dependsOn(buildWebClient)
}
