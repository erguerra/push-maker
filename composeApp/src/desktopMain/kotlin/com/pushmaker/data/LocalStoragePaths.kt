package com.pushmaker.data

import java.nio.file.Path
import java.nio.file.Paths

object LocalStoragePaths {
    val baseDirectory: Path by lazy { resolveBaseDirectory() }
    val pushesFile: Path by lazy { baseDirectory.resolve("pushes.json") }
    val settingsFile: Path by lazy { baseDirectory.resolve("settings.json") }

    private fun resolveBaseDirectory(): Path {
        val home = System.getProperty("user.home")
        val os = System.getProperty("os.name").lowercase()
        val directory = when {
            os.contains("win") -> {
                val appData = System.getenv("APPDATA") ?: Paths.get(home, "AppData", "Roaming").toString()
                Paths.get(appData)
            }
            os.contains("mac") -> Paths.get(home, "Library", "Application Support")
            else -> {
                val xdg = System.getenv("XDG_CONFIG_HOME")
                if (xdg.isNullOrBlank()) {
                    Paths.get(home, ".config")
                } else Paths.get(xdg)
            }
        }.resolve("PushMaker")
        return directory
    }
}
