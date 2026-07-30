package jp.co.tenposinfo.register

import androidx.compose.ui.graphics.Color

object ProductButtonPalette {
    fun background(key: String): Color = when (key.uppercase()) {
        "GREEN" -> Color(0xFFEAF5EC)
        "YELLOW" -> Color(0xFFFFF4D9)
        "RED" -> Color(0xFFFFEBEE)
        "ORANGE" -> Color(0xFFFFF0E6)
        "PURPLE" -> Color(0xFFF3EAF8)
        "GRAY" -> Color(0xFFF0F2F4)
        "WHITE" -> Color.White
        else -> Color(0xFFEAF3FA)
    }

    fun foreground(key: String): Color = when (key.uppercase()) {
        "RED" -> Color(0xFF8E1B1B)
        "ORANGE" -> Color(0xFF8A4300)
        "PURPLE" -> Color(0xFF5E2A78)
        else -> Color(0xFF173F6B)
    }
}
