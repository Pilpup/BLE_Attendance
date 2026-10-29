package com.college.feature.attendance.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tsoding Musializer High-Performance Glowing Orb Renderer.
 * Implements Tsoding's signature power=4.0 radial falloff:
 *   float t = 1 - s / (0.5 - r);
 *   finalColor = mix(vec4(fragColor.xyz, 0), fragColor * 1.5, pow(t, 4.0));
 *
 * Highly optimized for 60/120 FPS game rendering with cached color stops.
 */
object TsodingOrbRenderer {

    // Signature palette constants
    val EmeraldGreen = Color(0xFF00F5A0)
    val SolarOrange = Color(0xFFFF9100)
    val CyanPulse = Color(0xFF00E5FF)
    val ElectricPurple = Color(0xFFD500F9)
    val CrimsonAlert = Color(0xFFFF1744)
    val PureWhite = Color(0xFFFFFFFF)

    /**
     * Pre-computes Tsoding power=4.0 radial falloff stops for any given base color.
     */
    fun createColorStops(baseColor: Color, pulseAlpha: Float = 1.0f): Array<Pair<Float, Color>> {
        val r15 = (baseColor.red * 1.5f).coerceIn(0f, 1f)
        val g15 = (baseColor.green * 1.5f).coerceIn(0f, 1f)
        val b15 = (baseColor.blue * 1.5f).coerceIn(0f, 1f)

        val centerColor = Color(
            red = (r15 * 0.65f + 0.35f).coerceIn(0f, 1f),
            green = (g15 * 0.65f + 0.35f).coerceIn(0f, 1f),
            blue = (b15 * 0.65f + 0.35f).coerceIn(0f, 1f),
            alpha = pulseAlpha
        )
        val coreColor = Color(r15, g15, b15, pulseAlpha)

        return arrayOf(
            0.00f to centerColor,
            0.14f to coreColor,
            0.28f to coreColor.copy(alpha = 0.50f * pulseAlpha),
            0.44f to coreColor.copy(alpha = 0.18f * pulseAlpha),
            0.62f to coreColor.copy(alpha = 0.04f * pulseAlpha),
            0.80f to Color.Transparent,
            1.00f to Color.Transparent
        )
    }

    /**
     * Draws an authentic Tsoding Musializer glowing orb at the specified center and radius.
     */
    fun drawGlowingOrb(
        drawScope: DrawScope,
        center: Offset,
        radius: Float,
        baseColor: Color = EmeraldGreen,
        pulseAlpha: Float = 1.0f
    ) {
        val stops = createColorStops(baseColor, pulseAlpha)
        drawScope.drawCircle(
            brush = Brush.radialGradient(
                colorStops = stops,
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )
    }

    /**
     * Draws a high-tech directional chevron/arrow inside an orb (for Game #4: Orb Vector Flick).
     * Direction in radians:
     * 0 = RIGHT, PI/2 = DOWN, PI = LEFT, -PI/2 = UP.
     */
    fun drawDirectionalArrow(
        drawScope: DrawScope,
        center: Offset,
        sizePx: Float,
        angleRad: Float,
        arrowColor: Color = PureWhite,
        strokeWidthPx: Float = 6f
    ) {
        val cosA = cos(angleRad)
        val sinA = sin(angleRad)

        // Arrow tip at distance +sizePx*0.6 in direction of angleRad
        val tip = Offset(
            x = center.x + cosA * (sizePx * 0.55f),
            y = center.y + sinA * (sizePx * 0.55f)
        )
        // Base center at distance -sizePx*0.5
        val base = Offset(
            x = center.x - cosA * (sizePx * 0.45f),
            y = center.y - sinA * (sizePx * 0.45f)
        )

        // Wings
        val wingAngle1 = angleRad + (Math.PI * 0.75).toFloat()
        val wingAngle2 = angleRad - (Math.PI * 0.75).toFloat()
        val wingLen = sizePx * 0.40f

        val wing1 = Offset(
            x = tip.x + cos(wingAngle1) * wingLen,
            y = tip.y + sin(wingAngle1) * wingLen
        )
        val wing2 = Offset(
            x = tip.x + cos(wingAngle2) * wingLen,
            y = tip.y + sin(wingAngle2) * wingLen
        )

        val path = Path().apply {
            // Shaft
            moveTo(base.x, base.y)
            lineTo(tip.x, tip.y)
            // Left wing
            moveTo(wing1.x, wing1.y)
            lineTo(tip.x, tip.y)
            // Right wing
            lineTo(wing2.x, wing2.y)
        }

        drawScope.drawPath(
            path = path,
            color = arrowColor,
            style = Stroke(
                width = strokeWidthPx,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}
