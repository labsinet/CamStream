package com.example.camstream

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {

    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var etPort: EditText
    private lateinit var spRes: Spinner
    private lateinit var spQ: Spinner
    private lateinit var spRot: Spinner
    private lateinit var btn: Button
    private lateinit var tvUrl: TextView

    private var sizes: List<Size> = emptyList()
    private val qualities = intArrayOf(70, 85, 95)
    private val rotations = intArrayOf(Surface.ROTATION_90, Surface.ROTATION_0, Surface.ROTATION_270)

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.CAMERA] == true) startStream()
        else Toast.makeText(this, "Потрібен дозвіл на камеру", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val dp = resources.displayMetrics.density
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        fun field(hint: String, value: String, type: Int) = EditText(this).apply {
            this.hint = hint; setText(value); inputType = type
        }
        fun spinner(items: List<String>, sel: Int) = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items)
            setSelection(sel.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
        }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, (12 * dp).toInt(), 0, 0) }

        etUser = field("Логін", prefs.getString("user", "admin")!!, InputType.TYPE_CLASS_TEXT)
        etPass = field("Пароль (мін. 6 символів)", prefs.getString("pass", "")!!,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        etPort = field("Порт", prefs.getInt("port", 8443).toString(), InputType.TYPE_CLASS_NUMBER)

        sizes = CameraSizes.backYuvSizes(this).ifEmpty { listOf(Size(1920, 1080), Size(1280, 720)) }
        spRes = spinner(sizes.map { CameraSizes.label(it) }, prefs.getInt("res", 0))
        spQ = spinner(qualities.map { "$it %" }, prefs.getInt("q", 1))
        spRot = spinner(listOf("Альбомна", "Портретна", "Альбомна (перевернута)"), prefs.getInt("rot", 0))

        btn = Button(this).apply { setOnClickListener { toggle() } }
        tvUrl = TextView(this).apply { textSize = 16f; setTextIsSelectable(true); gravity = Gravity.CENTER }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (48 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
            addView(TextView(context).apply { text = "CamStream — HTTPS-сервер відео"; textSize = 22f })
            addView(etUser, match, wrap)
            addView(etPass, match, wrap)
            addView(etPort, match, wrap)
            addView(label("Роздільна здатність відео (чим більша, тим менше кадрів/с)"))
            addView(spRes, match, wrap)
            addView(label("Якість JPEG"))
            addView(spQ, match, wrap)
            addView(label("Орієнтація"))
            addView(spRot, match, wrap)
            addView(btn, match, wrap)
            addView(tvUrl, match, wrap)
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun toggle() {
        if (StreamService.running) {
            stopService(Intent(this, StreamService::class.java))
            btn.postDelayed({ refresh() }, 300)
            return
        }
        if (etPass.text.length < 6) {
            Toast.makeText(this, "Пароль має містити щонайменше 6 символів", Toast.LENGTH_LONG).show()
            return
        }
        val req = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) req += Manifest.permission.POST_NOTIFICATIONS
        perms.launch(req.toTypedArray())
    }

    private fun startStream() {
        val port = etPort.text.toString().toIntOrNull()?.takeIf { it in 1024..65535 } ?: 8443
        val size = sizes[spRes.selectedItemPosition.coerceIn(0, sizes.size - 1)]
        getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
            .putString("user", etUser.text.toString())
            .putString("pass", etPass.text.toString())
            .putInt("port", port)
            .putInt("res", spRes.selectedItemPosition)
            .putInt("q", spQ.selectedItemPosition)
            .putInt("rot", spRot.selectedItemPosition)
            .apply()

        val i = Intent(this, StreamService::class.java)
            .putExtra(StreamService.EXTRA_USER, etUser.text.toString())
            .putExtra(StreamService.EXTRA_PASS, etPass.text.toString())
            .putExtra(StreamService.EXTRA_PORT, port)
            .putExtra(StreamService.EXTRA_W, size.width)
            .putExtra(StreamService.EXTRA_H, size.height)
            .putExtra(StreamService.EXTRA_QUALITY, qualities[spQ.selectedItemPosition])
            .putExtra(StreamService.EXTRA_ROTATION, rotations[spRot.selectedItemPosition])
        startForegroundService(i)
        btn.postDelayed({ refresh() }, 1200)
    }

    private fun refresh() {
        val on = StreamService.running
        btn.text = if (on) "Зупинити трансляцію" else "Запустити трансляцію"
        tvUrl.text = if (on) {
            val ip = localIp()
            val fp = try { TlsHelper.fingerprint(this) } catch (_: Throwable) { null }
            if (ip == null) "Wi-Fi не підключено"
            else "Відкрийте у браузері ПК:\nhttps://$ip:${etPort.text}\n\n" +
                "Браузер покаже попередження про самопідписаний сертифікат — це нормально. " +
                "За бажанням звірте відбиток SHA-256:\n${fp ?: "—"}"
        } else ""
    }

    private fun localIp(): String? =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
}
