package com.framenest.feature.browser

/**
 * Grid column count for browse: phone ≈2, tablet ≈3–4.
 * Breakpoints align with Material width buckets (600 / 840 dp).
 */
fun browseGridColumnCount(widthDp: Float): Int = when {
    widthDp >= 840f -> 4
    widthDp >= 600f -> 3
    else -> 2
}
