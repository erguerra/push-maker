package com.pushmaker.adb

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object AdbLocator {
    fun detectAdbExecutable(): String? {
        env("ADB")?.let { direct ->
            val expanded = expandTilde(direct)
            if (isExecutable(expanded)) return expanded
        }

        val sdkRoots = buildList {
            env("ANDROID_HOME")?.let(::add)
            env("ANDROID_SDK_ROOT")?.let(::add)
            addAll(defaultSdkGuesses())
        }

        val osBinaryName = if (isWindows()) "adb.exe" else "adb"
        for (root in sdkRoots) {
            candidatePaths(root, osBinaryName).forEach { candidate ->
                if (isExecutable(candidate)) return candidate
            }
        }
        return null
    }

    fun isExecutable(path: String): Boolean {
        val target = Paths.get(expandTilde(path))
        return Files.exists(target) && Files.isRegularFile(target) && Files.isExecutable(target)
    }

    private fun candidatePaths(sdkRoot: String, binaryName: String): List<String> {
        val expanded = expandTilde(sdkRoot)
        val base = Paths.get(expanded)
        val candidates = mutableListOf<Path>()
        candidates.add(base.resolve("platform-tools").resolve(binaryName))
        candidates.add(base.resolve(binaryName))
        return candidates.map(Path::toString)
    }

    private fun expandTilde(path: String): String {
        return if (path.startsWith("~")) {
            path.replaceFirst("~", System.getProperty("user.home"))
        } else path
    }

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private fun defaultSdkGuesses(): List<String> {
        val home = System.getProperty("user.home")
        val guesses = mutableListOf<String>()
        guesses += "$home/Library/Android/sdk"
        guesses += "$home/Android/Sdk"
        guesses += "$home/Android/sdk"
        val localAppData = System.getenv("LOCALAPPDATA") ?: "$home/AppData/Local"
        guesses += "$localAppData/Android/Sdk"
        val programFiles = System.getenv("ProgramFiles")
        if (programFiles != null) {
            guesses += "$programFiles/Android/Android Studio"
        }
        return guesses
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")
}
