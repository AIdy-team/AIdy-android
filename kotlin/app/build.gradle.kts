plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.aidy.petrobot"
    compileSdk = 35

    defaultConfig {
        // Unity 版 (com.aidy.petrobot) と同じ端末に並べて入れられるよう別の ID にする
        applicationId = "com.aidy.petrobot.kotlin"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // minSdk 24 で java.time (LocalDate) を使う
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    androidResources {
        // モデル (.obj / .stl / .3mf) は圧縮せずに読む
        noCompress += listOf("obj", "stl", "3mf", "json")
    }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/aidyAssets"))
}

// ---------------------------------------------------------------- モデルの取り込み
// Unity 版と同じファイルを使う: Modules/ (元ファイル) → Assets/Resources/AIDY/ の順に探し、
// assets/models/ に ASCII の名前でコピーする。実行時の読み込み順は stl → obj → 3mf (Unity 版と同じ)。

val unityRoot = rootProject.projectDir.parentFile
val modulesDir = File(unityRoot, "Modules")
val resourcesDir = File(unityRoot, "Assets/Resources/AIDY")

val moduleModels = mapOf(
    "耳" to "ear",
    "tail" to "tail",
    "tail2" to "tail2",
    "手_短い" to "hand",
    "手_短い_懐中電灯" to "hand_light",
    "足v1" to "legs",
)
val fixedAssets = mapOf(
    "本体v2_モデル.obj" to "models/body.obj",
    "本体v2_顔あり.obj" to "models/body_face.obj",
    "body_v2_layout.json" to "models/body_v2_layout.json",
    "aidy_logo_dark.png" to "aidy_logo_dark.png",
    "aidy_logo_light.png" to "aidy_logo_light.png",
)

val syncAidyAssets by tasks.registering {
    val out = layout.buildDirectory.dir("generated/aidyAssets")
    inputs.dir(resourcesDir)
    if (modulesDir.exists()) inputs.dir(modulesDir)
    outputs.dir(out)
    doLast {
        val dst = out.get().asFile
        dst.deleteRecursively()
        File(dst, "models").mkdirs()
        for ((src, name) in fixedAssets) {
            val f = File(resourcesDir, src)
            if (f.exists()) f.copyTo(File(dst, name), overwrite = true)
            else logger.warn("[AIDY] $src が見つかりません")
        }
        for ((base, name) in moduleModels) {
            var found = false
            for (ext in listOf("stl", "obj", "3mf")) {
                val candidates = listOf(
                    File(modulesDir, "$base.$ext"),
                    File(resourcesDir, "${base}_$ext.bytes"),
                    File(resourcesDir, "$base.$ext"),
                )
                val f = candidates.firstOrNull { it.exists() } ?: continue
                f.copyTo(File(dst, "models/$name.$ext"), overwrite = true)
                found = true
            }
            if (!found) logger.warn("[AIDY] モジュールのモデルがありません: $base (.stl / .obj)")
        }
    }
}
tasks.named("preBuild") { dependsOn(syncAidyAssets) }

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 3D・AR (Filament + ARCore)
    implementation("io.github.sceneview:sceneview:2.3.0")
    implementation("io.github.sceneview:arsceneview:2.3.0")

    // ARCore 非対応端末のカメラ映像
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
}
