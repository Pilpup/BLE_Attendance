package com.attendance.ble.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * High-artistry background inspired by Persona 3 Reload's dynamic visual language:
 *
 *  - Deep Abyssal Midnight Gradient (Obsidian void meets deep ocean blue).
 *  - Ethereal Moonlit/Caustic Cyan Radial Auras.
 *  - Signature 12° Canted Geometric Graphic Ribbons slicing the viewport.
 *  - Stylized Halftone / Stippled Dot Matrix in the upper quadrant.
 *  - Cybernetic HUD framing corner brackets & laser hairline accents.
 */
@Composable
fun ArtisticPersonaBackground(
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // 1. Deep Abyssal Void Base Gradient
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF061430),
                    P3MidnightNavy,
                    P3VoidBlack,
                    Color(0xFF02040A)
                ),
                startY = 0f,
                endY = h
            )
        )

        // 2. Ambient Moonlit / Water Caustic Radial Auras
        // Top-Right Luminous Aqua Flare
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0x2800E5FF),
                    Color(0x0F0077FE),
                    Color.Transparent
                ),
                center = Offset(w * 0.92f, h * 0.08f),
                radius = w * 0.85f
            ),
            radius = w * 0.85f,
            center = Offset(w * 0.92f, h * 0.08f)
        )

        // Mid-Left Deep Indigo Glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0x220077FE),
                    Color(0x0800E5FF),
                    Color.Transparent
                ),
                center = Offset(w * -0.1f, h * 0.52f),
                radius = w * 0.75f
            ),
            radius = w * 0.75f,
            center = Offset(w * -0.1f, h * 0.52f)
        )

        // 3. Dynamic Canted Graphic Ribbons (-12° Angle)
        // Ribbon 1: Wide primary oceanic blade
        val ribbonPath = Path().apply {
            moveTo(w * 0.30f, 0f)
            lineTo(w * 1.25f, 0f)
            lineTo(w * 0.65f, h)
            lineTo(-w * 0.30f, h)
            close()
        }
        drawPath(
            path = ribbonPath,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0x140077FE),
                    Color(0x0A00E5FF),
                    Color(0x020077FE)
                ),
                start = Offset(0f, 0f),
                end = Offset(w, h)
            )
        )

        // Ribbon 2: Sharp laser edge hairline tracking the diagonal cut
        val laserHairline = Path().apply {
            moveTo(w * 0.30f, 0f)
            lineTo(-w * 0.30f, h)
        }
        drawPath(
            path = laserHairline,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    Color(0x6600E5FF),
                    Color(0x2200E5FF),
                    Color.Transparent
                ),
                start = Offset(w * 0.30f, 0f),
                end = Offset(-w * 0.30f, h)
            ),
            style = Stroke(width = 1.5.dp.toPx())
        )

        // Ribbon 3: Parallel secondary faint guide line
        val parallelGuide = Path().apply {
            moveTo(w * 0.42f, 0f)
            lineTo(-w * 0.18f, h)
        }
        drawPath(
            path = parallelGuide,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0x3300E5FF),
                    Color(0x100077FE),
                    Color.Transparent
                ),
                start = Offset(w * 0.42f, 0f),
                end = Offset(-w * 0.18f, h)
            ),
            style = Stroke(width = 1.dp.toPx())
        )

        // 4. Stylized Halftone / Stippled Dot Matrix in upper-right quadrant
        val dotSpacing = 22.dp.toPx()
        val dotRadius = 1.2.dp.toPx()
        val startX = w * 0.40f
        val endX = w - 16.dp.toPx()
        val startY = 32.dp.toPx()
        val endY = h * 0.35f

        var curY = startY
        while (curY < endY) {
            var curX = startX
            while (curX < endX) {
                // Diagonal cutoff so dots stay inside the aesthetic boundary
                val relativeProg = (curY - startY) / (endY - startY)
                val rowStartX = startX + (relativeProg * w * 0.20f)
                if (curX >= rowStartX) {
                    drawCircle(
                        color = Color(0x1400E5FF),
                        radius = dotRadius,
                        center = Offset(curX, curY)
                    )
                }
                curX += dotSpacing
            }
            curY += dotSpacing
        }

        // 5. Stylized Corner Framing Brackets (HUD Decals)
        val bracketLen = 16.dp.toPx()
        val bracketInset = 16.dp.toPx()
        val bracketColor = Color(0x4000E5FF)
        val bracketStroke = 1.5.dp.toPx()

        // Top-Left Tech Bracket
        drawLine(
            color = bracketColor,
            start = Offset(bracketInset, bracketInset),
            end = Offset(bracketInset + bracketLen, bracketInset),
            strokeWidth = bracketStroke
        )
        drawLine(
            color = bracketColor,
            start = Offset(bracketInset, bracketInset),
            end = Offset(bracketInset, bracketInset + bracketLen),
            strokeWidth = bracketStroke
        )

        // Bottom-Right Tech Bracket
        drawLine(
            color = bracketColor,
            start = Offset(w - bracketInset, h - bracketInset),
            end = Offset(w - bracketInset - bracketLen, h - bracketInset),
            strokeWidth = bracketStroke
        )
        drawLine(
            color = bracketColor,
            start = Offset(w - bracketInset, h - bracketInset),
            end = Offset(w - bracketInset, h - bracketInset - bracketLen),
            strokeWidth = bracketStroke
        )
    }
}
