package com.example.camstream

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import kotlin.math.abs

object CameraSizes {
    /** Усі розміри кадру (YUV), які підтримує основна задня камера, від найбільшого. */
    fun backYuvSizes(ctx: Context): List<Size> = try {
        val mgr = ctx.getSystemService(CameraManager::class.java)
        val id = mgr.cameraIdList.firstOrNull {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }
        val map = id?.let {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        }
        map?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
            .filter { it.width >= 1280 && isCommonRatio(it) }
            .sortedByDescending { it.width.toLong() * it.height }
    } catch (_: Throwable) { emptyList() }

    fun isCommonRatio(s: Size): Boolean {
        val r = s.width.toFloat() / s.height
        return abs(r - 4f / 3) < 0.02f || abs(r - 16f / 9) < 0.02f
    }

    fun label(s: Size) = "${s.width}x${s.height} (${"%.1f".format(s.width * s.height / 1e6)} МП)"
}
