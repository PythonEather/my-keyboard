package com.expressive.keyboard

// English captions for the plugin screen modes (fullscreen / floating panel). Separate file: the main tables stay untouched.
private val EN_SCREEN: Map<String, String> = hashMapOf(
    "свой экран" to "own screen",
    "показывать свой экран: на весь экран или плавающей панелью поверх приложения" to
        "show its own screen: fullscreen, or a floating panel over the app"
)

internal fun trs(ru: String): String = if (I18n.lang == "ru") ru else EN_SCREEN[ru] ?: tr(ru)
