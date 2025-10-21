package com.github.kr328.clash.design.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.annotation.AttrRes
import com.github.kr328.clash.design.R
import com.google.android.material.color.MaterialColors
import kotlin.math.max

class TrafficTrendView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val samples = ArrayList<Float>()
    private val linePath = Path()
    private val fillPath = Path()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.getDimension(R.dimen.main_traffic_chart_stroke_width)
        color = MaterialColors.getColor(
            this@TrafficTrendView,
            com.google.android.material.R.attr.colorPrimary,
            0xFF1E88E5.toInt()
        )
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = linePaint.color
        alpha = 56
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.getDimension(R.dimen.divider_size)
        color = MaterialColors.getColor(
            this@TrafficTrendView,
            com.google.android.material.R.attr.colorOnSurface,
            0x33000000
        )
        alpha = 80
    }

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = linePaint.color
    }

    private val pointRadius = resources.getDimension(R.dimen.main_traffic_chart_point_radius)

    fun setSamples(values: List<Float>) {
        samples.clear()
        samples.addAll(values)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val availableWidth = width - paddingStart - paddingEnd
        val availableHeight = height - paddingTop - paddingBottom

        if (availableWidth <= 0 || availableHeight <= 0) {
            return
        }

        val left = paddingStart.toFloat()
        val top = paddingTop.toFloat()
        val bottom = top + availableHeight
        val right = left + availableWidth

        // draw baseline
        canvas.drawLine(left, bottom, right, bottom, gridPaint)

        // draw horizontal guidelines
        val guidelineCount = 3
        for (index in 1 until guidelineCount) {
            val ratio = index / guidelineCount.toFloat()
            val y = bottom - availableHeight * ratio
            canvas.drawLine(left, y, right, y, gridPaint)
        }

        if (samples.isEmpty()) {
            return
        }

        val maxValue = max(samples.maxOrNull() ?: 0f, 0.01f)
        val heightScale = availableHeight / maxValue

        if (samples.size == 1) {
            val sample = samples.first().coerceAtLeast(0f)
            val x = left
            val y = bottom - sample * heightScale
            canvas.drawLine(x, bottom, x, y, linePaint)
            canvas.drawCircle(x, y, pointRadius, pointPaint)
            return
        }

        val widthStep = availableWidth / (samples.size - 1).toFloat()

        linePath.reset()
        fillPath.reset()

        samples.forEachIndexed { index, rawSample ->
            val sample = rawSample.coerceAtLeast(0f)
            val x = left + widthStep * index
            val y = bottom - sample * heightScale

            if (index == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, bottom)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        fillPath.lineTo(right, bottom)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)

        val lastSample = samples.last().coerceAtLeast(0f)
        val lastX = left + widthStep * (samples.size - 1)
        val lastY = bottom - lastSample * heightScale
        canvas.drawCircle(lastX, lastY, pointRadius, pointPaint)
    }
}
