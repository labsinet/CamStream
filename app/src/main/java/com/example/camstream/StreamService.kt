package com.example.camstream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Size
import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class StreamService : LifecycleService(), CameraController {

    companion object {
        const val EXTRA_USER = "user"
        const val EXTRA_PASS = "pass"
        const val EXTRA_PORT = "port"
        const val EXTRA_W = "w"
        const val EXTRA_H = "h"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_ROTATION = "rotation" // Surface.ROTATION_*
        @Volatile var running = false
    }

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val photoExecutor = Executors.newSingleThreadExecutor()
    private val photoLock = Any()
    private var lastPhoto: File? = null

    private var server: MjpegServer? = null
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var minZoom = 1f
    @Volatile private var maxZoom = 1f
    @Volatile private var curZoom = 1f
    private var lastFrameTs = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (running || intent == null) return START_NOT_STICKY

        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("stream", "Трансляція", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = NotificationCompat.Builder(this, "stream")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("CamStream")
            .setContentText("HTTPS-трансляція у локальній мережі активна")
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        ServiceCompat.startForeground(this, 1, n, type)

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "camstream:wl").apply { acquire() }

        val user = intent.getStringExtra(EXTRA_USER) ?: "admin"
        val pass = intent.getStringExtra(EXTRA_PASS) ?: ""
        val port = intent.getIntExtra(EXTRA_PORT, 8443)
        val size = Size(intent.getIntExtra(EXTRA_W, 1920), intent.getIntExtra(EXTRA_H, 1080))
        val quality = intent.getIntExtra(EXTRA_QUALITY, 85)
        val rotation = intent.getIntExtra(EXTRA_ROTATION, Surface.ROTATION_90)

        FrameHub.reset()
        startCamera(size, quality, rotation)
        try {
            server = MjpegServer(this, port, user, pass, this).also {
                it.makeSecure(TlsHelper.sslFactory(this), arrayOf("TLSv1.2"))
                it.start(10000, false)
            }
        } catch (e: Throwable) {
            android.util.Log.e("CamStream", "Не вдалося запустити HTTPS-сервер", e)
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        return START_NOT_STICKY
    }

    private fun startCamera(size: Size, quality: Int, rotation: Int) {
        val ratio = if (Math.abs(size.width.toFloat() / size.height - 4f / 3) < 0.02f)
            AspectRatio.RATIO_4_3 else AspectRatio.RATIO_16_9
        val ratioStrategy = AspectRatioStrategy(ratio, AspectRatioStrategy.FALLBACK_RULE_AUTO)

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            provider.unbindAll()

            val an = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(ratioStrategy)
                        .setResolutionStrategy(
                            ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                        ).build()
                )
                .setTargetRotation(rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            an.setAnalyzer(executor) { img ->
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastFrameTs < 40) return@setAnalyzer
                    lastFrameTs = now
                    var bmp = img.toBitmap()
                    val rot = img.imageInfo.rotationDegrees
                    if (rot != 0) {
                        val m = Matrix().apply { postRotate(rot.toFloat()) }
                        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                    }
                    val out = ByteArrayOutputStream(256 * 1024)
                    bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    FrameHub.push(out.toByteArray())
                } catch (_: Throwable) {
                } finally {
                    img.close()
                }
            }

            // Окремий use case для фото в максимальній роздільності сенсора
            val ic = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(ratioStrategy)
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                        .build()
                )
                .setTargetRotation(rotation)
                .build()

            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            val cam = try {
                imageCapture = ic
                provider.bindToLifecycle(this, selector, an, ic)
            } catch (e: IllegalArgumentException) {
                // Телефон не підтримує таку комбінацію потоків — лишаємо тільки відео
                provider.unbindAll()
                imageCapture = null
                provider.bindToLifecycle(this, selector, an)
            }
            analysis = an
            camera = cam
            cam.cameraInfo.zoomState.observe(this) { z ->
                minZoom = z.minZoomRatio; maxZoom = z.maxZoomRatio; curZoom = z.zoomRatio
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---- CameraController ----
    override fun setZoom(ratio: Float) {
        main.post { camera?.cameraControl?.setZoomRatio(ratio.coerceIn(minZoom, maxZoom)) }
    }

    override fun zoomInfo() = floatArrayOf(minZoom, maxZoom, curZoom)

    override fun focusAt(x: Float, y: Float) {
        main.post {
            val cam = camera ?: return@post
            val an = analysis ?: return@post
            val point = SurfaceOrientedMeteringPointFactory(1f, 1f, an)
                .createPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
            cam.cameraControl.startFocusAndMetering(
                FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .disableAutoCancel() // тримати фокус, поки не натиснуть «Авто»
                    .build()
            )
        }
    }

    override fun autoFocus() {
        main.post { camera?.cameraControl?.cancelFocusAndMetering() }
    }

    override fun takePhoto(): File? = synchronized(photoLock) {
        val ic = imageCapture ?: return null
        lastPhoto?.delete()
        val f = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        main.post {
            ic.takePicture(
                ImageCapture.OutputFileOptions.Builder(f).build(), photoExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(r: ImageCapture.OutputFileResults) { ok.set(true); latch.countDown() }
                    override fun onError(e: ImageCaptureException) { latch.countDown() }
                })
        }
        latch.await(25, TimeUnit.SECONDS)
        if (ok.get()) { lastPhoto = f; f } else null
    }

    override fun onDestroy() {
        running = false
        FrameHub.stop()
        server?.stop()
        try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (_: Throwable) {}
        executor.shutdown()
        photoExecutor.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
