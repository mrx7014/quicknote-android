package com.quicknote.app

import android.content.Context

data class AppPalette(
    val dark: Boolean,
    val page: Int,
    val surface: Int,
    val field: Int,
    val ink: Int,
    val muted: Int,
    val line: Int,
    val teal: Int,
    val soft: Int,
    val badgeNote: Int,
    val badgeTask: Int,
    val badgeVoice: Int,
    val statNote: Int,
    val statTask: Int,
    val statVoice: Int,
    val hint: Int,
    val panelTint: Int,
    val warning: Int
) {
    companion object {
        fun from(context: Context): AppPalette = if (AppPreferences.darkMode(context)) AppPalette(
            dark = true,
            page = 0xFF101713.toInt(), surface = 0xFF1C2721.toInt(), field = 0xFF27352E.toInt(),
            ink = 0xFFE4EEE7.toInt(), muted = 0xFFB5C2B9.toInt(), line = 0xFF45564C.toInt(),
            teal = 0xFF5CC6A7.toInt(), soft = 0xFF293930.toInt(), badgeNote = 0xFF263B31.toInt(),
            badgeTask = 0xFF403725.toInt(), badgeVoice = 0xFF342D43.toInt(), statNote = 0xFF24372E.toInt(),
            statTask = 0xFF3A3425.toInt(), statVoice = 0xFF302A3D.toInt(), hint = 0xFFB5C2B9.toInt(),
            panelTint = 0xFF20342B.toInt(), warning = 0xFFFFCF77.toInt()
        ) else AppPalette(
            dark = false,
            page = 0xFFF4F7F3.toInt(), surface = 0xFFFFFFFF.toInt(), field = 0xFFF9FBF8.toInt(),
            ink = 0xFF24332E.toInt(), muted = 0xFF5B675F.toInt(), line = 0xFFE2EAE4.toInt(),
            teal = 0xFF176B5B.toInt(), soft = 0xFFEFF5F0.toInt(), badgeNote = 0xFFE6F4ED.toInt(),
            badgeTask = 0xFFF9F3E1.toInt(), badgeVoice = 0xFFEDEAF8.toInt(), statNote = 0xFFE6F4ED.toInt(),
            statTask = 0xFFF5EFDD.toInt(), statVoice = 0xFFECE9F7.toInt(), hint = 0xFF59665F.toInt(),
            panelTint = 0xFFE6F4ED.toInt(), warning = 0xFF8A5D00.toInt()
        )
    }
}
