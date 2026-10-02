package com.sertum.player.ui.theme

import androidx.compose.ui.graphics.Color

// PRD 7.10 design tokens: near-pure black + warm gold.
// 2026-10-02 user feedback: the dark theme went fully black.
val PureBlack = Color(0xFF000000)

/**
 * One step off pure black. Reserved for artwork surfaces that need to read as
 * an object against the page (cover placeholder, cover backdrop, cards) -
 * if those were also `#000000` they would dissolve into the background.
 */
val SurfaceBlack = Color(0xFF0A0A0A)

/**
 * One step above [SurfaceBlack]. Settings action tiles use it so a tappable
 * action reads as a different object from a state tile, without spending gold
 * on something that carries no state.
 */
val SurfaceRaised = Color(0xFF141414)
val WarmGold = Color(0xFFC9A96E)
val WarmGoldDim = Color(0x669A7A45)
val TextPrimary = Color(0xFFF2F2F2)
val TextSecondary = Color(0xFF8A8A8A)

/** Divider / separator tone. Carries the structure that black-on-black loses. */
val HairlineDark = Color(0xFF232323)

// Light theme: paper-like.
val PaperWhite = Color(0xFFF5F1E8)
val PaperSurface = Color(0xFFFFFCF5)
val InkPrimary = Color(0xFF17140F)
val InkSecondary = Color(0xFF6E675C)
