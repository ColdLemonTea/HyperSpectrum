// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "HyperSpectrum"
include(":app")

// The optional settings UI needs the local miuix composite build.
if (!providers.gradleProperty("noSettingsUi").orElse("false").get().toBoolean()) {
    val localMiuix = file("../miuix")
    require(localMiuix.resolve("settings.gradle.kts").isFile) {
        "Missing local miuix checkout: ${localMiuix.absolutePath} (or build with -PnoSettingsUi=true)"
    }
    includeBuild(localMiuix) {
        dependencySubstitution {
            listOf("core", "ui", "shader", "blur", "preference", "icons", "squircle", "glass", "glass-icons")
                .forEach { component ->
                    substitute(module("top.yukonga.miuix.kmp:miuix-$component-android"))
                        .using(project(":miuix-$component"))
                }
        }
    }
}
