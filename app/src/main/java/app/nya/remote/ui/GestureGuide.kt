package app.nya.remote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nya.remote.data.ControlMode
import kotlin.math.cos
import kotlin.math.sin

/** Pictogram of a gesture: fingertips plus what they do. */
enum class GestureIcon { TAP, HOLD, PINCH, HOLD_DRAG, SLIDE, THREE_TAP, TWO_PAN, MOVE, TWO_TAP, TWO_SLIDE }

private data class GuideItem(val icon: GestureIcon, val title: String, val hint: String)

private val touchItems = listOf(
    GuideItem(GestureIcon.TAP, "单击", "单指轻按"),
    GuideItem(GestureIcon.HOLD, "右键", "单指长按"),
    GuideItem(GestureIcon.PINCH, "缩放", "双指捏合"),
    GuideItem(GestureIcon.HOLD_DRAG, "拖动", "单指长按拖动"),
    GuideItem(GestureIcon.SLIDE, "滚动", "单指滑动"),
    GuideItem(GestureIcon.THREE_TAP, "唤起键盘", "三指轻按"),
    GuideItem(GestureIcon.TWO_PAN, "缩放后拖动桌面", "双指捏合放大桌面后，双指滑动"),
)

private val mouseItems = listOf(
    GuideItem(GestureIcon.MOVE, "移动光标", "单指滑动"),
    GuideItem(GestureIcon.TAP, "单击", "单指轻按"),
    GuideItem(GestureIcon.TWO_TAP, "右键", "双指轻按或单指长按"),
    GuideItem(GestureIcon.HOLD_DRAG, "拖动", "单指长按后滑动"),
    GuideItem(GestureIcon.TWO_SLIDE, "滚动", "双指滑动"),
    GuideItem(GestureIcon.PINCH, "缩放", "双指捏合，画面跟随光标"),
    GuideItem(GestureIcon.THREE_TAP, "唤起键盘", "三指轻按"),
)

/** The gesture guide over the remote screen, as in the design reference. Tap anywhere to close. */
@Composable
fun GestureGuide(mode: ControlMode, showOnConnect: Boolean, onShowOnConnect: (Boolean) -> Unit, onClose: () -> Unit) {
    val items = if (mode == ControlMode.TOUCH) touchItems else mouseItems
    val none = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(interactionSource = none, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth(0.82f)
                .background(OverlayDark, RoundedCornerShape(20.dp))
                .padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (mode == ControlMode.TOUCH) "触屏模式–手势指引" else "鼠标模式–手势指引",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))
            items.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                    row.forEach { item ->
                        Row(Modifier.weight(1f).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            GestureGlyph(item.icon, Modifier.size(44.dp))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(item.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                Text(item.hint, color = Color(0xFFB8BBC2), fontSize = 13.sp)
                            }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("连接时显示", color = Color.White, fontSize = 15.sp)
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = showOnConnect,
                    onCheckedChange = onShowOnConnect,
                    colors = SwitchDefaults.colors(checkedTrackColor = Accent),
                )
            }
        }
    }
}

@Composable
fun GestureGlyph(icon: GestureIcon, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val tip = w * 0.09f
        val white = Color.White
        val blue = Color(0xFF4C8DFF)
        val stroke = Stroke(width = w * 0.045f, cap = StrokeCap.Round)
        fun finger(x: Float, y: Float) {
            drawCircle(white, tip, Offset(x, y), style = stroke)
            drawLine(white, Offset(x - tip, y), Offset(x - tip, y + w * 0.32f), stroke.width, StrokeCap.Round)
            drawLine(white, Offset(x + tip, y), Offset(x + tip, y + w * 0.32f), stroke.width, StrokeCap.Round)
        }
        fun arrow(from: Offset, to: Offset) = drawArrow(from, to, blue, stroke.width)
        val c = Offset(w * 0.5f, w * 0.42f)
        when (icon) {
            GestureIcon.TAP -> {
                finger(c.x, c.y)
                for (a in listOf(-150.0, -90.0, -30.0)) {
                    val r = Math.toRadians(a)
                    drawLine(blue, Offset(c.x + cos(r).toFloat() * w * 0.16f, c.y + sin(r).toFloat() * w * 0.16f),
                        Offset(c.x + cos(r).toFloat() * w * 0.27f, c.y + sin(r).toFloat() * w * 0.27f), stroke.width, StrokeCap.Round)
                }
            }
            GestureIcon.HOLD -> {
                finger(c.x, c.y)
                drawCircle(blue, w * 0.2f, c, style = stroke)
            }
            GestureIcon.PINCH -> {
                finger(w * 0.38f, w * 0.48f)
                finger(w * 0.66f, w * 0.36f)
                arrow(Offset(w * 0.3f, w * 0.3f), Offset(w * 0.12f, w * 0.12f))
                arrow(Offset(w * 0.74f, w * 0.18f), Offset(w * 0.9f, w * 0.04f))
            }
            GestureIcon.HOLD_DRAG -> {
                finger(c.x, c.y)
                drawCircle(blue, w * 0.17f, c, style = stroke)
                arrow(Offset(w * 0.2f, w * 0.2f), Offset(w * 0.05f, w * 0.35f))
            }
            GestureIcon.SLIDE, GestureIcon.MOVE -> {
                finger(c.x, c.y)
                arrow(Offset(w * 0.2f, w * 0.45f), Offset(w * 0.2f, w * 0.1f))
                arrow(Offset(w * 0.2f, w * 0.45f), Offset(w * 0.2f, w * 0.8f))
            }
            GestureIcon.THREE_TAP -> {
                finger(w * 0.3f, w * 0.42f)
                finger(w * 0.5f, w * 0.34f)
                finger(w * 0.7f, w * 0.42f)
            }
            GestureIcon.TWO_TAP -> {
                finger(w * 0.38f, w * 0.42f)
                finger(w * 0.62f, w * 0.42f)
                drawLine(blue, Offset(w * 0.5f, w * 0.1f), Offset(w * 0.5f, w * 0.22f), stroke.width, StrokeCap.Round)
            }
            GestureIcon.TWO_PAN, GestureIcon.TWO_SLIDE -> {
                finger(w * 0.4f, w * 0.44f)
                finger(w * 0.62f, w * 0.44f)
                arrow(Offset(w * 0.3f, w * 0.18f), Offset(w * 0.08f, w * 0.18f))
                arrow(Offset(w * 0.72f, w * 0.18f), Offset(w * 0.94f, w * 0.18f))
            }
        }
    }
}

private fun DrawScope.drawArrow(from: Offset, to: Offset, color: Color, width: Float) {
    drawLine(color, from, to, width, StrokeCap.Round)
    val angle = kotlin.math.atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())
    val head = size.width * 0.1f
    for (d in listOf(2.5, -2.5)) {
        val a = angle + d
        drawLine(color, to, Offset(to.x + (cos(a) * head).toFloat(), to.y + (sin(a) * head).toFloat()), width, StrokeCap.Round)
    }
}
