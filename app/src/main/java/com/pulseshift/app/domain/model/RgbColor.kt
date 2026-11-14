package com.pulseshift.app.domain.model

data class RgbColor(
    val red: Int = 139,
    val green: Int = 92,
    val blue: Int = 246
) {
    init {
        require(red in 0..255) { "Red must be between 0 and 255" }
        require(green in 0..255) { "Green must be between 0 and 255" }
        require(blue in 0..255) { "Blue must be between 0 and 255" }
    }

    val hexString: String
        get() = String.format("#%02X%02X%02X", red, green, blue)

    fun toHsv(): FloatArray {
        val r = red / 255f
        val g = green / 255f
        val b = blue / 255f

        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min

        var h = 0f
        if (delta != 0f) {
            h = when (max) {
                r -> ((g - b) / delta) % 6f
                g -> ((b - r) / delta) + 2f
                else -> ((r - g) / delta) + 4f
            } * 60f
            if (h < 0f) h += 360f
        }

        val s = if (max == 0f) 0f else delta / max
        val v = max

        return floatArrayOf(h, s, v)
    }

    companion object {
        val BrandViolet = RgbColor(139, 92, 246)
        val PureWhite = RgbColor(255, 255, 255)
        val RacingRed = RgbColor(239, 68, 68)
        val AmberWarning = RgbColor(245, 158, 11)
        val NeonGreen = RgbColor(16, 185, 129)
        val ElectricCyan = RgbColor(6, 182, 212)
        val DeepBlue = RgbColor(59, 130, 246)
        val SunsetOrange = RgbColor(249, 115, 22)

        fun fromHsv(hue: Float, saturation: Float, value: Float): RgbColor {
            val c = value * saturation
            val hPrime = (hue % 360f) / 60f
            val x = c * (1f - kotlin.math.abs((hPrime % 2f) - 1f))
            val m = value - c

            val (r1, g1, b1) = when {
                hPrime < 1f -> Triple(c, x, 0f)
                hPrime < 2f -> Triple(x, c, 0f)
                hPrime < 3f -> Triple(0f, c, x)
                hPrime < 4f -> Triple(0f, x, c)
                hPrime < 5f -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }

            return RgbColor(
                red = ((r1 + m) * 255f).toInt().coerceIn(0, 255),
                green = ((g1 + m) * 255f).toInt().coerceIn(0, 255),
                blue = ((b1 + m) * 255f).toInt().coerceIn(0, 255)
            )
        }

        fun fromHex(hex: String): RgbColor {
            val clean = hex.removePrefix("#")
            return if (clean.length == 6) {
                RgbColor(
                    red = clean.substring(0, 2).toInt(16),
                    green = clean.substring(2, 4).toInt(16),
                    blue = clean.substring(4, 6).toInt(16)
                )
            } else {
                BrandViolet
            }
        }
    }

    fun toHex(): String = "#%02X%02X%02X".format(red, green, blue)
}
