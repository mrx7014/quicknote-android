package com.quicknote.app

import java.util.Locale

internal object TagNormalizer {
    private const val MAX_TAGS = 8
    private const val MAX_TAG_LENGTH = 32

    fun normalize(raw: String): String = raw
        .split(',', ';', '\n')
        .map { it.trim().removePrefix("#").trim().take(MAX_TAG_LENGTH) }
        .filter(String::isNotBlank)
        .distinctBy { it.lowercase(Locale.ROOT) }
        .take(MAX_TAGS)
        .joinToString(", ")
}
