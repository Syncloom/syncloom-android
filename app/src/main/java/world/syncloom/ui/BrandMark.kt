package world.syncloom.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The Syncloom icon: two woven rings on a gradient squircle. */
@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 1024f
        drawRoundRect(
            Brush.linearGradient(listOf(Color(0xFF6A5CFF), Color(0xFF12B5A6)), Offset(140 * k, 0f), Offset(884 * k, 1024 * k)),
            size = Size(1024 * k, 1024 * k), cornerRadius = CornerRadius(232 * k)
        )
        val w = 76 * k
        fun ring(cx: Float, brush: Brush) = drawCircle(brush, 176 * k, Offset(cx * k, 512 * k), style = Stroke(w, cap = StrokeCap.Round))
        ring(396f, Brush.linearGradient(listOf(Color.White, Color.White)))
        ring(628f, Brush.linearGradient(listOf(Color(0xFFC9F6FF), Color(0xFF8BE9F2))))
        val over = Path().apply {
            var first = true
            var d = -70.0
            while (d <= -22.0) {
                val a = d * PI / 180
                val x = (396 + 176 * cos(a)).toFloat() * k
                val y = (512 + 176 * sin(a)).toFloat() * k
                if (first) { moveTo(x, y); first = false } else lineTo(x, y)
                d += 1.0
            }
        }
        drawPath(over, Color.White, style = Stroke(w, cap = StrokeCap.Butt))
    }
}
