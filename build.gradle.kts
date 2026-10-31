import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.1"
}

group = "com.gsheetplus"
version = "0.1.0"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.foundation)
    implementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
}

kotlin {
    jvmToolchain(17)
}

// The Google sign-in key comes from the environment (GitHub secrets), never from the repository.
val generateSecrets = tasks.register("generateSecrets") {
    val outDir = layout.buildDirectory.dir("generated/secrets")
    val clientId = providers.environmentVariable("GSP_CLIENT_ID").orElse("")
    val clientSecret = providers.environmentVariable("GSP_CLIENT_SECRET").orElse("")
    inputs.property("id", clientId)
    inputs.property("secret", clientSecret)
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile
        dir.mkdirs()
        File(dir, "Secrets.kt").writeText(
            "package gsp\n\nobject Secrets {\n" +
                "    const val CLIENT_ID = \"${clientId.get()}\"\n" +
                "    const val CLIENT_SECRET = \"${clientSecret.get()}\"\n}\n",
        )
    }
}

kotlin.sourceSets.getByName("main").kotlin.srcDir(layout.buildDirectory.dir("generated/secrets"))

tasks.named("compileKotlin") { dependsOn(generateSecrets) }

compose.desktop {
    application {
        mainClass = "gsp.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Gsheet_Plus"
            packageVersion = "1.0.0"
            description = "Gsheet_Plus for Windows"
            includeAllModules = true
            windows {
                menuGroup = "Gsheet_Plus"
                shortcut = true
                dirChooser = true
                upgradeUuid = "6f1b2c1e-8d3a-4f55-9a4e-2b7c0d9e41a7"
            }
        }
    }
}
