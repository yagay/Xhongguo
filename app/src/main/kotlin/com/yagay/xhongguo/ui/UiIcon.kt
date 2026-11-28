package com.yagay.xhongguo.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

internal class UiIcon(context: Context, private val kind: String, tint: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint; style = Paint.Style.STROKE; strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = minOf(width, height) * .75f / 24f
        canvas.save(); canvas.translate((width - 24*s)/2, (height - 24*s)/2); canvas.scale(s,s)
        when (kind) {
            "tune" -> for ((y,x) in listOf(6f to 8f, 12f to 16f, 18f to 10f)) {
                canvas.drawLine(3f,y,x-3,y,paint); canvas.drawLine(x+3,y,21f,y,paint); canvas.drawCircle(x,y,2.5f,paint)
            }
            "layers" -> {
                canvas.drawRoundRect(3f,3f,18f,18f,3f,3f,paint)
                canvas.drawRoundRect(9f,9f,21f,21f,3f,3f,paint)
            }
            "audio" -> for ((x,y) in listOf(4f to 8f, 8f to 4f, 12f to 11f, 16f to 5f, 20f to 8f))
                canvas.drawLine(x,y,x,24f-y,paint)
            "play" -> {
                canvas.drawCircle(12f,12f,9f,paint)
                canvas.drawPath(Path().apply { moveTo(10f,7f); lineTo(17f,12f); lineTo(10f,17f); close() },paint)
            }
            "display" -> {
                canvas.drawRoundRect(3f,4f,21f,17f,2f,2f,paint)
                canvas.drawLine(12f,17f,12f,21f,paint); canvas.drawLine(7f,21f,17f,21f,paint)
            }
            "grid" -> for (x in listOf(3f,13f)) for (y in listOf(3f,13f))
                canvas.drawRoundRect(x,y,x+8f,y+8f,2f,2f,paint)
            "status" -> {
                canvas.drawRoundRect(3f,3f,21f,21f,3f,3f,paint)
                canvas.drawPath(Path().apply { moveTo(5f,13f); lineTo(9f,13f); lineTo(11f,8f); lineTo(14f,17f); lineTo(17f,12f); lineTo(19f,12f) },paint)
            }
            else -> {
                canvas.drawRoundRect(3f,8f,21f,20f,2f,2f,paint)
                canvas.drawRoundRect(8f,4f,16f,8f,1f,1f,paint)
                canvas.drawLine(3f,13f,21f,13f,paint); canvas.drawLine(10f,13f,14f,13f,paint)
            }
        }
        canvas.restore()
    }
}
