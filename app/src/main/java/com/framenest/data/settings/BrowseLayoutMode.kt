package com.framenest.data.settings

/**
 * Browse directory presentation: single-column list or multi-column grid.
 * Default is [LIST] (MVP behavior).
 */
enum class BrowseLayoutMode {
    LIST,
    GRID,
    ;

    fun storageValue(): String = name.lowercase()

    companion object {
        fun fromStorage(value: String?): BrowseLayoutMode =
            when (value?.trim()?.lowercase()) {
                "grid" -> GRID
                else -> LIST
            }
    }
}
