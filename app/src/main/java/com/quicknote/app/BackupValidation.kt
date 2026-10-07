package com.quicknote.app

internal object BackupValidation {
    private val mediaName = Regex("media/[A-Za-z0-9_.-]{1,100}")

    fun isSafeZipName(name: String): Boolean {
        if (name.isBlank() || name.startsWith('/') || name.contains('\\') || name.contains(':')) return false
        val segments = name.split('/')
        if (segments.any { it == ".." || it == "." }) return false
        if (segments.dropLast(1).any(String::isBlank)) return false
        return true
    }

    fun isSafeMediaName(name: String): Boolean {
        if (!mediaName.matches(name)) return false
        val filename = name.removePrefix("media/")
        return filename != "." && filename != ".."
    }
}
