package ai.kimi.code.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * DSH-Go 风格的线条图标，全部用 Canvas 画。
 *
 * 项目不引图标库（不引 androidx、不加依赖），每个图标是 24×24 视口里的
 * 若干条线。`stroke` 决定线宽，圆头圆角，观感与截图里那套细线图标一致。
 */
object DshIcon {

    const val MENU = "menu"
    const val PLUS = "plus"
    const val SEARCH = "search"
    const val CHAT = "chat"
    const val RUN = "run"
    const val SETTINGS = "settings"
    const val BACK = "back"
    const val CHEVRON = "chevron"
    const val MORE = "more"
    const val SWAP = "swap"
    const val FOLDER = "folder"
    const val PLUGIN = "plugin"
    const val PHONE = "phone"
    const val GLOBE = "globe"
    const val IMAGE = "image"
    const val MODEL = "model"
    const val SHIELD = "shield"
    const val PERSON = "person"
    const val TERMINAL = "terminal"
    const val DATA = "data"
    const val CLOSE = "close"
    const val EDIT = "edit"
    const val STOP = "stop"
    const val REFRESH = "refresh"

    /** A view that draws [type] centred, tinted [color]. */
    fun view(context: Context, type: String, color: Int, sizeDp: Int = 22, strokeDp: Float = 1.7f): View =
        IconView(context, type, color, sizeDp, strokeDp)

    private class IconView(
        context: Context,
        private val type: String,
        private val color: Int,
        sizeDp: Int,
        private val strokeDp: Float,
    ) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = this@IconView.color
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = this@IconView.color
        }
        private val path = Path()
        private val rect = RectF()
        private val density = resources.displayMetrics.density
        private val side = (sizeDp * density).toInt()

        init {
            paint.strokeWidth = strokeDp * density
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(side, side)
        }

        override fun onDraw(canvas: Canvas) {
            val s = width.toFloat() / 24f          // one unit of the 24-grid
            canvas.save()
            canvas.scale(s, s)
            path.reset()
            when (type) {
                MENU -> {
                    line(canvas, 4f, 7f, 20f, 7f)
                    line(canvas, 4f, 12f, 20f, 12f)
                    line(canvas, 4f, 17f, 14f, 17f)
                }
                PLUS -> {
                    line(canvas, 12f, 5f, 12f, 19f)
                    line(canvas, 5f, 12f, 19f, 12f)
                }
                SEARCH -> {
                    canvas.drawCircle(11f, 11f, 6.2f, paint)
                    line(canvas, 15.6f, 15.6f, 20f, 20f)
                }
                CHAT -> {
                    rect.set(3.5f, 4.5f, 20.5f, 17.5f)
                    path.addRoundRect(rect, 5f, 5f, Path.Direction.CW)
                    canvas.drawPath(path, paint)
                    line(canvas, 8f, 20.5f, 7.2f, 22.6f)
                    line(canvas, 7.2f, 22.6f, 11.5f, 20.4f)
                }
                RUN -> {
                    canvas.drawCircle(12f, 12f, 8.2f, paint)
                    fill.style = Paint.Style.FILL
                    path.reset()
                    path.moveTo(10f, 8.6f)
                    path.lineTo(16f, 12f)
                    path.lineTo(10f, 15.4f)
                    path.close()
                    canvas.drawPath(path, fill)
                }
                SETTINGS -> {
                    canvas.drawCircle(12f, 12f, 3.1f, paint)
                    for (i in 0 until 8) {
                        val a = Math.toRadians(i * 45.0)
                        val c = Math.cos(a).toFloat()
                        val s2 = Math.sin(a).toFloat()
                        line(
                            canvas,
                            12f + c * 6.0f, 12f + s2 * 6.0f,
                            12f + c * 8.2f, 12f + s2 * 8.2f,
                        )
                    }
                }
                BACK -> {
                    line(canvas, 19f, 12f, 5.5f, 12f)
                    line(canvas, 11f, 5.5f, 4.8f, 12f)
                    line(canvas, 4.8f, 12f, 11f, 18.5f)
                }
                CHEVRON -> {
                    line(canvas, 9.5f, 6f, 15.5f, 12f)
                    line(canvas, 15.5f, 12f, 9.5f, 18f)
                }
                MORE -> {
                    fill.style = Paint.Style.FILL
                    canvas.drawCircle(6f, 12f, 1.5f, fill)
                    canvas.drawCircle(12f, 12f, 1.5f, fill)
                    canvas.drawCircle(18f, 12f, 1.5f, fill)
                }
                SWAP -> {
                    line(canvas, 4f, 9f, 18f, 9f)
                    line(canvas, 14.5f, 5.5f, 18f, 9f)
                    line(canvas, 18f, 9f, 14.5f, 12.5f)
                    line(canvas, 20f, 15f, 6f, 15f)
                    line(canvas, 9.5f, 11.5f, 6f, 15f)
                    line(canvas, 6f, 15f, 9.5f, 18.5f)
                }
                FOLDER -> {
                    rect.set(3.5f, 6.5f, 20.5f, 19f)
                    path.addRoundRect(rect, 3.5f, 3.5f, Path.Direction.CW)
                    canvas.drawPath(path, paint)
                    line(canvas, 3.5f, 11f, 20.5f, 11f)
                }
                PLUGIN -> {
                    line(canvas, 9f, 3.5f, 9f, 7f)
                    line(canvas, 15f, 3.5f, 15f, 7f)
                    rect.set(5f, 7f, 19f, 18f)
                    path.addRoundRect(rect, 4f, 4f, Path.Direction.CW)
                    canvas.drawPath(path, paint)
                    line(canvas, 12f, 18f, 12f, 21f)
                }
                PHONE -> {
                    rect.set(6.5f, 2.8f, 17.5f, 21.2f)
                    path.addRoundRect(rect, 3.6f, 3.6f, Path.Direction.CW)
                    canvas.drawPath(path, paint)
                    line(canvas, 10.5f, 18.4f, 13.5f, 18.4f)
                }
                GLOBE -> {
                    canvas.drawCircle(12f, 12f, 8.4f, paint)
                    canvas.save()
                    canvas.clipRect(3.6f, 3.6f, 20.4f, 20.4f)
                    canvas.drawOval(RectF(8.2f, 3.6f, 15.8f, 20.4f), paint)
                    line(canvas, 3.6f, 12f, 20.4f, 12f)
                    canvas.restore()
                }
                IMAGE -> {
                    rect.set(3.5f, 4.5f, 20.5f, 19.5f)
                    path.addRoundRect(rect, 4f, 4f, Path.Direction.CW)
                    canvas.drawPath(path, paint)
                    canvas.drawCircle(8.6f, 9.4f, 1.6f, fill)
                    line(canvas, 5f, 16.6f, 10.4f, 11.6f)
                    line(canvas, 10.4f, 11.6f, 14.2f, 15.6f)
                    line(canvas, 14.2f, 15.6f, 22f, 11f)
                }
                MODEL -> {
                    path.reset()
                    path.moveTo(12f, 3f)
                    path.lineTo(20f, 7.4f)
                    path.lineTo(20f, 16.6f)
                    path.lineTo(12f, 21f)
                    path.lineTo(4f, 16.6f)
                    path.lineTo(4f, 7.4f)
                    path.close()
                    canvas.drawPath(path, paint)
                    line(canvas, 4f, 7.4f, 12f, 12f)
                    line(canvas, 20f, 7.4f, 12f, 12f)
                    line(canvas, 12f, 12f, 12f, 21f)
                }
                SHIELD -> {
                    path.reset()
                    path.moveTo(12f, 3f)
                    path.lineTo(19.5f, 6f)
                    path.lineTo(19.5f, 11.4f)
                    path.cubicTo(19.5f, 16.3f, 16.4f, 19.6f, 12f, 21.4f)
                    path.cubicTo(7.6f, 19.6f, 4.5f, 16.3f, 4.5f, 11.4f)
                    path.lineTo(4.5f, 6f)
                    path.close()
                    canvas.drawPath(path, paint)
                }
                PERSON -> {
                    canvas.drawCircle(12f, 8.4f, 3.6f, paint)
                    rect.set(4.8f, 14.2f, 19.2f, 24.5f)
                    path.addRoundRect(rect, 7.4f, 7.4f, Path.Direction.CW)
                    canvas.drawPath(path, paint)
                }
                TERMINAL -> {
                    rect.set(3.2f, 4.5f, 20.8f, 19.5f)
                    path.addRoundRect(rect, 4f, 4f, Path.Direction.CW)
                    canvas.drawPath(path, paint)
                    line(canvas, 7.5f, 9.5f, 10.5f, 12f)
                    line(canvas, 10.5f, 12f, 7.5f, 14.5f)
                    line(canvas, 12.5f, 15f, 16.5f, 15f)
                }
                DATA -> {
                    canvas.drawOval(RectF(4.5f, 3.4f, 19.5f, 8.4f), paint)
                    line(canvas, 4.5f, 5.9f, 4.5f, 18.1f)
                    line(canvas, 19.5f, 5.9f, 19.5f, 18.1f)
                    canvas.drawOval(RectF(4.5f, 15.6f, 19.5f, 20.6f), paint)
                    line(canvas, 4.5f, 12f, 19.5f, 12f)
                }
                CLOSE -> {
                    line(canvas, 6f, 6f, 18f, 18f)
                    line(canvas, 18f, 6f, 6f, 18f)
                }
                EDIT -> {
                    line(canvas, 4.5f, 19.5f, 8.6f, 18.5f)
                    line(canvas, 8.6f, 18.5f, 19.4f, 7.7f)
                    line(canvas, 19.4f, 7.7f, 16.3f, 4.6f)
                    line(canvas, 16.3f, 4.6f, 5.5f, 15.4f)
                    line(canvas, 5.5f, 15.4f, 4.5f, 19.5f)
                }
                STOP -> {
                    fill.style = Paint.Style.FILL
                    rect.set(7f, 7f, 17f, 17f)
                    path.addRoundRect(rect, 2.4f, 2.4f, Path.Direction.CW)
                    canvas.drawPath(path, fill)
                }
                REFRESH -> {
                    rect.set(4.5f, 4.5f, 19.5f, 19.5f)
                    canvas.drawArc(rect, -62f, 250f, false, paint)
                    line(canvas, 16.2f, 3.2f, 18.6f, 6.6f)
                    line(canvas, 18.6f, 6.6f, 14.6f, 8.2f)
                }
            }
            canvas.restore()
        }

        private fun line(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
            path.reset()
            path.moveTo(x1, y1)
            path.lineTo(x2, y2)
            canvas.drawPath(path, paint)
        }
    }

    /** Colour for the tinted square behind a settings row icon. */
    fun tint(hue: Int): Pair<Int, Int> = when (hue) {
        0 -> Color.parseColor("#EDE9FE") to Color.parseColor("#7C5CFC")
        1 -> Color.parseColor("#E6F0FF") to Color.parseColor("#3B82F6")
        2 -> Color.parseColor("#FDF2E3") to Color.parseColor("#E39A3B")
        3 -> Color.parseColor("#E8F5EC") to Color.parseColor("#3FA76B")
        4 -> Color.parseColor("#FBE9E7") to Color.parseColor("#E06B4E")
        5 -> Color.parseColor("#F3E8FF") to Color.parseColor("#9B5DE5")
        6 -> Color.parseColor("#E8F0F5") to Color.parseColor("#5B8CA8")
        else -> Color.parseColor("#EFEFF5") to Color.parseColor("#6E6E7A")
    }
}
