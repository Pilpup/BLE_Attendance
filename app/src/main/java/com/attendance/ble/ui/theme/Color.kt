package com.attendance.ble.ui.theme

import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────────────
// Persona 3 Reload–Inspired Palette & Color Theory
// Dominant: Deep abyssal blues & midnight oceans (calm, focused, dark hour).
// Accents: Luminous electric cyan & hyper-vivid cerulean (aquatic laser energy).
// Complementary Pop: Luminous gold & chartreuse (warm high-contrast focal points).
// ─────────────────────────────────────────────────────────────────────────────

// Abyssal Backgrounds & Glass Surfaces
val P3VoidBlack = Color(0xFF030712)
val P3MidnightNavy = Color(0xFF07132B)
val P3OceanDepth = Color(0xFF0C1F4D)
val P3CardGlass = Color(0xE00C1B38)
val P3CardBorder = Color(0x3800E5FF)
val P3CardBorderGlow = Color(0x9900E5FF)

// Luminous Oceanic Accents
val P3ElectricCyan = Color(0xFF00E5FF)
val P3HyperBlue = Color(0xFF0077FE)
val P3SkyGlaze = Color(0xFF38BDF8)
val P3IceWhite = Color(0xFFF0F8FF)
val P3SlateMuted = Color(0xFF7699C2)
val P3DeepSlate = Color(0xFF3B5373)

// High-Energy Complementary & Role Hues
val P3LuminousGold = Color(0xFFFFD000)
val P3GoldBg = Color(0x2BFFD000)
val P3NeonViolet = Color(0xFFB04DFF)
val P3VioletBg = Color(0x2BB04DFF)
val P3EmeraldAqua = Color(0xFF00F5A0)
val P3EmeraldBg = Color(0x2800F5A0)
val P3AlertRose = Color(0xFFFF3366)
val P3AlertRoseBg = Color(0x26FF3366)

// ─────────────────────────────────────────────────────────────────────────────
// Backward-Compatible Semantic Mappings (Replaced with P3 Aesthetic)
// ─────────────────────────────────────────────────────────────────────────────
val appBg = P3VoidBlack
val cardBg = P3CardGlass
val cardBorder = P3CardBorder
val textPrimary = P3IceWhite
val textSecondary = P3SlateMuted
val textMuted = P3DeepSlate

val primaryBlue = P3HyperBlue
val primaryBlueBg = Color(0x280077FE)

val successGreen = P3EmeraldAqua
val successGreenBg = P3EmeraldBg

val alertRed = P3AlertRose
val alertRedBg = P3AlertRoseBg

val childPurple = P3NeonViolet
val childPurpleBg = P3VioletBg

val warningOrange = P3LuminousGold
val warningOrangeBg = P3GoldBg

val logBg = Color(0xFF061024)
