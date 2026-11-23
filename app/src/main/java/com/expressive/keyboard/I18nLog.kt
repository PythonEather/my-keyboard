package com.expressive.keyboard

// Английские подписи журнала скрипта. Отдельный файл: основные таблицы переводов не трогаем.
private val EN_LOG: Map<String, String> = hashMapOf(
    "скрипт работает слишком долго и остановлен" to "script ran too long and was stopped",
    "Все записи" to "All",
    "Предупреждения" to "Warnings",
    "Ошибки" to "Errors",
    "Обновить сводку" to "Refresh",
    "Показать больше" to "Show more",
    "Показать меньше" to "Show less",
    "Копировать журнал" to "Copy log",
    "Очистить журнал" to "Clear log",
    "Журнал скопирован" to "Log copied"
)

internal fun trl(ru: String): String = if (I18n.lang == "ru") ru else EN_LOG[ru] ?: tr(ru)
