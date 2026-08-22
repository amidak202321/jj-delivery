package com.jjburger.entregador

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

data class Delivery(
    val id: String,
    val number: String,
    val lat: Double?,
    val lon: Double?,
    val status: String,
    val position: Int
)

class MainActivity : AppCompatActivity(), LocationListener {
    private val api = "https://jxpiazntgqasvonvrtpv.supabase.co/functions/v1/jjburger-driver"
    private val prefs by lazy { getSharedPreferences("jj_driver", MODE_PRIVATE) }
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private var token = ""
    private var driverName = ""
    private var deliveries = emptyList<Delivery>()
    private val known = mutableSetOf<String>()
    private var firstLoad = true
    private var locationManager: LocationManager? = null
    private var lastSyncAt: Long? = null
    private var routeError: String? = null
    private var loginMessage = ""
    private var completingId: String? = null
    private var lastPositionSentAt = 0L
    private val routeLoading = AtomicBoolean(false)

    private val backgroundColor = Color.rgb(13, 15, 19)
    private val surface = Color.rgb(27, 32, 40)
    private val surfaceRaised = Color.rgb(34, 40, 49)
    private val primary = Color.rgb(241, 183, 31)
    private val primaryText = Color.rgb(25, 24, 18)
    private val textMain = Color.rgb(248, 249, 250)
    private val textMuted = Color.rgb(166, 174, 184)
    private val danger = Color.rgb(255, 137, 128)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor
        createChannel()
        askPermissions()
        token = prefs.getString("token", "") ?: ""
        driverName = prefs.getString("name", "") ?: ""
        if (token.isBlank()) showLogin() else showRoute()
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).roundToInt()

    private fun rounded(fill: Int, stroke: Int? = null, radius: Int = 14): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius.dp().toFloat()
            stroke?.let { setStroke(1.dp(), it) }
        }
    }

    private fun params(top: Int = 0, bottom: Int = 0, width: Int = LinearLayout.LayoutParams.MATCH_PARENT): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, top.dp(), 0, bottom.dp())
        }
    }

    private fun add(parent: LinearLayout, child: View, top: Int = 10, bottom: Int = 0) {
        parent.addView(child, params(top, bottom))
    }

    private fun gap(height: Int): Space = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, height.dp())
    }

    private fun base(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 18.dp(), 20.dp(), 30.dp())
            setBackgroundColor(backgroundColor)
        }
    }

    private fun text(value: String, size: Float = 18f, bold: Boolean = false): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(textMain)
            includeFontPadding = false
            setPadding(0, 4.dp(), 0, 4.dp())
            if (bold) setTypeface(typeface, 1)
        }
    }

    private fun input(hintText: String): EditText {
        return EditText(this).apply {
            hint = hintText
            textSize = 16f
            setTextColor(textMain)
            setHintTextColor(Color.rgb(113, 123, 135))
            setSingleLine(true)
            minHeight = 54.dp()
            setPadding(15.dp(), 0, 15.dp(), 0)
            background = rounded(surfaceRaised, Color.rgb(57, 66, 78), 13)
        }
    }

    private fun button(label: String, onClick: () -> Unit, filled: Boolean = true): Button {
        val fill = if (filled) primary else surfaceRaised
        val foreground = if (filled) primaryText else textMain
        return Button(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            minHeight = 46.dp()
            minimumWidth = 0
            setPadding(14.dp(), 0, 14.dp(), 0)
            setTextColor(foreground)
            background = rounded(fill, if (filled) null else Color.rgb(65, 74, 86), 12)
            stateListAnimator = null
            setOnClickListener { onClick() }
        }
    }

    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(17.dp(), 15.dp(), 17.dp(), 15.dp())
            background = rounded(surface, Color.rgb(48, 57, 69), 17)
        }
    }

    private fun showLogin(message: String? = null) {
        handler.removeCallbacksAndMessages(null)
        routeLoading.set(false)
        stopLocation()
        if (message != null) loginMessage = message

        root = base()
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(backgroundColor)
            addView(root)
        }
        setContentView(scroll)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mark = TextView(this).apply {
            text = "JJ"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(primaryText)
            setTypeface(typeface, 1)
            background = rounded(primary, radius = 14)
            layoutParams = LinearLayout.LayoutParams(52.dp(), 52.dp())
        }
        header.addView(mark)
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(13.dp(), 0, 0, 0)
        }
        brand.addView(text("JJ Entregador", 22f, true))
        brand.addView(text("Aplicativo de rota e entregas", 13f).apply { setTextColor(textMuted) })
        header.addView(brand, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        add(root, text("Acesse sua rota com o código entregue pela central.", 15f).apply { setTextColor(textMuted) }, 24)
        val code = input("Código de 6 dígitos").apply {
            gravity = Gravity.CENTER
            textSize = 22f
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setSelectAllOnFocus(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        }
        add(root, code, 9)
        val messageView = text(loginMessage, 13f).apply {
            setTextColor(if (loginMessage.isBlank()) textMuted else danger)
        }
        add(root, messageView, 9)
        lateinit var loginButton: Button
        loginButton = button("Entrar na rota", {
            val accessCode = code.text.toString().trim()
            if (accessCode.length != 6) {
                messageView.setTextColor(danger)
                messageView.text = "Informe o código completo de 6 dígitos."
                return@button
            }
            loginButton.isEnabled = false
            messageView.setTextColor(textMuted)
            messageView.text = "Validando código…"
            io.execute {
                try {
                    val result = request("login", "POST", JSONObject().put("code", accessCode), null)
                    val driver = result.getJSONObject("driver")
                    token = result.getString("token")
                    driverName = driver.getString("name")
                    prefs.edit().putString("token", token).putString("name", driverName).apply()
                    runOnUiThread { loginButton.isEnabled = true; loginMessage = ""; showRoute() }
                } catch (error: Exception) {
                    runOnUiThread {
                        loginButton.isEnabled = true
                        messageView.setTextColor(danger)
                        messageView.text = error.message ?: "Não foi possível entrar."
                    }
                }
            }
        })
        add(root, loginButton, 14)
        add(root, text("A rota é atualizada automaticamente enquanto o aplicativo estiver aberto.", 12f).apply {
            setTextColor(Color.rgb(122, 132, 143))
            gravity = Gravity.CENTER
        }, 20)
    }

    private fun showRoute() {
        routeError = null
        root = base()
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(backgroundColor)
            addView(root)
        }
        setContentView(scroll)
        render()
        startLocation()
        poll()
    }

    private fun render() {
        if (!::root.isInitialized) return
        root.removeAllViews()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title.addView(text("Olá, $driverName", 24f, true))
        title.addView(text("Painel do entregador", 13f).apply { setTextColor(textMuted) })
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(button("Atualizar", { fetchRoute() }, false), params(width = LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(header)

        val syncLabel = when {
            routeLoading.get() -> "Atualizando rota…"
            routeError != null -> "Falha na atualização"
            lastSyncAt == null -> "Conectando à central…"
            else -> "Atualizado às ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(lastSyncAt!!))}"
        }
        add(root, text("●  $syncLabel", 12f).apply {
            setTextColor(if (routeError == null) Color.rgb(120, 206, 135) else danger)
        }, 8)

        routeError?.let { errorMessage ->
            val alert = card()
            add(alert, text("Não foi possível atualizar a rota", 15f, true), 0)
            add(alert, text(errorMessage, 12f).apply { setTextColor(textMuted) }, 4)
            add(alert, button("Tentar novamente", { fetchRoute() }), 10)
            add(root, alert, 14)
        }

        if (deliveries.isEmpty()) {
            val empty = card()
            val titleText = if (lastSyncAt == null) "Conectando à sua rota…" else "Nenhuma entrega pendente"
            add(empty, text(titleText, 19f, true), 0)
            add(empty, text(if (lastSyncAt == null) "Aguarde um instante enquanto buscamos os pedidos." else "Quando a central atribuir um pedido, ele aparecerá aqui automaticamente.", 13f).apply { setTextColor(textMuted) }, 5)
            add(root, empty, 18)
        } else {
            val next = deliveries.first()
            val nextCard = card()
            add(nextCard, text("PRÓXIMA ENTREGA", 12f, true).apply { setTextColor(primary) }, 0)
            add(nextCard, text("Pedido #${next.number}", 29f, true), 8)
            add(nextCard, text("Posição ${next.position} da rota", 13f).apply { setTextColor(textMuted) }, 2)

            val navigation = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val waze = button("Abrir Waze", { navigate(next, true) }, false)
            val maps = button("Abrir Maps", { navigate(next, false) }, false)
            navigation.addView(waze, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 10.dp(), 5.dp(), 0) })
            navigation.addView(maps, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(5.dp(), 10.dp(), 0, 0) })
            nextCard.addView(navigation)

            val complete = button(if (completingId == next.id) "Concluindo…" else "Marcar como concluída", { complete(next) })
            complete.isEnabled = completingId != next.id
            add(nextCard, complete, 10)
            add(root, nextCard, 18)

            if (deliveries.size > 1) {
                add(root, text("Próximas paradas", 17f, true), 22)
                deliveries.drop(1).forEach { delivery ->
                    val item = card()
                    val line = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    line.addView(text("${delivery.position}", 16f, true).apply {
                        gravity = Gravity.CENTER
                        setTextColor(primary)
                        background = rounded(Color.rgb(64, 55, 29), radius = 10)
                        layoutParams = LinearLayout.LayoutParams(36.dp(), 36.dp())
                    })
                    line.addView(text("Pedido #${delivery.number}", 15f, true).apply {
                        setPadding(12.dp(), 4.dp(), 0, 4.dp())
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    item.addView(line)
                    add(root, item, 9)
                }
            }
        }

        add(root, button("Sair deste entregador", {
            prefs.edit().clear().apply()
            token = ""
            driverName = ""
            known.clear()
            deliveries = emptyList()
            showLogin()
        }, false), 26)
    }

    private fun poll() {
        handler.removeCallbacksAndMessages(null)
        fetchRoute()
        handler.postDelayed(object : Runnable {
            override fun run() {
                fetchRoute()
                handler.postDelayed(this, 3000)
            }
        }, 3000)
    }

    private fun fetchRoute() {
        if (token.isBlank() || !routeLoading.compareAndSet(false, true)) return
        runOnUiThread { render() }
        io.execute {
            try {
                val result = request("route", "GET", null, token)
                driverName = result.getJSONObject("driver").getString("name")
                val orders = result.getJSONArray("orders")
                val list = ArrayList<Delivery>()
                for (index in 0 until orders.length()) {
                    val order = orders.getJSONObject(index)
                    list.add(Delivery(
                        order.getString("id"),
                        order.getString("order_number"),
                        if (order.isNull("latitude")) null else order.getDouble("latitude"),
                        if (order.isNull("longitude")) null else order.getDouble("longitude"),
                        order.getString("status"),
                        if (order.isNull("route_position")) index + 1 else order.getInt("route_position")
                    ))
                }
                val newIds = list.map { it.id }.toSet() - known
                if (!firstLoad && newIds.isNotEmpty()) notifyNew(list.firstOrNull { it.id in newIds })
                known.clear()
                known.addAll(list.map { it.id })
                firstLoad = false
                deliveries = list
                routeError = null
                lastSyncAt = System.currentTimeMillis()
                runOnUiThread {
                    routeLoading.set(false)
                    if (token.isNotBlank()) render()
                }
            } catch (error: Exception) {
                val message = error.message ?: "Não foi possível atualizar a rota."
                val invalidSession = message.contains("token", true) || message.contains("sessão", true) || message.contains("autoriz", true)
                runOnUiThread {
                    routeLoading.set(false)
                    if (invalidSession) {
                        prefs.edit().remove("token").apply()
                        token = ""
                        showLogin("A sessão expirou. Informe novamente o código da central.")
                    } else {
                        routeError = message
                        render()
                    }
                }
            }
        }
    }

    private fun navigate(delivery: Delivery, waze: Boolean) {
        if (delivery.lat == null || delivery.lon == null) {
            Toast.makeText(this, "A localização do cliente ainda não foi recebida.", Toast.LENGTH_LONG).show()
            return
        }
        io.execute {
            try { request("start", "POST", JSONObject().put("order_id", delivery.id), token) } catch (_: Exception) { }
        }
        val uri = if (waze) {
            Uri.parse("waze://?ll=${delivery.lat},${delivery.lon}&navigate=yes")
        } else {
            Uri.parse("google.navigation:q=${delivery.lat},${delivery.lon}&mode=d")
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${delivery.lat},${delivery.lon}")))
        }
    }

    private fun complete(delivery: Delivery) {
        if (completingId != null) return
        completingId = delivery.id
        render()
        io.execute {
            try {
                request("complete", "POST", JSONObject().put("order_id", delivery.id), token)
                runOnUiThread {
                    completingId = null
                    Toast.makeText(this, "Entrega concluída", Toast.LENGTH_SHORT).show()
                    fetchRoute()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    completingId = null
                    render()
                    Toast.makeText(this, error.message ?: "Não foi possível concluir.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun request(action: String, method: String, body: JSONObject?, auth: String?): JSONObject {
        val connection = (URL("$api?action=$action").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = method
            connection.connectTimeout = 6000
            connection.readTimeout = 8000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (!auth.isNullOrBlank()) connection.setRequestProperty("x-driver-token", auth)
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
            if (status !in 200..299) {
                val error = json.optString("error").ifBlank { "Erro de comunicação ($status)." }
                throw Exception(error)
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    private fun createChannel() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel("new_delivery", "Novas entregas", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Avisos de novas entregas"
                enableVibration(true)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notifyNew(delivery: Delivery?) {
        val notification = NotificationCompat.Builder(this, "new_delivery")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("Nova entrega")
            .setContentText(if (delivery != null) "Pedido #${delivery.number} foi adicionado à sua rota." else "Nova entrega adicionada.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED || android.os.Build.VERSION.SDK_INT < 33) {
            getSystemService(NotificationManager::class.java).notify((System.currentTimeMillis() % 100000).toInt(), notification)
        }
    }

    private fun askPermissions() {
        val requested = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= 33) requested.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = requested.filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 55)
    }

    private fun startLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        try { locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10000L, 30f, this) } catch (_: Exception) { }
        try { locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 15000L, 50f, this) } catch (_: Exception) { }
    }

    private fun stopLocation() {
        try { locationManager?.removeUpdates(this) } catch (_: Exception) { }
    }

    override fun onLocationChanged(location: Location) {
        if (token.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - lastPositionSentAt < 8000L) return
        lastPositionSentAt = now
        io.execute {
            try {
                request("position", "POST", JSONObject().put("latitude", location.latitude).put("longitude", location.longitude), token)
            } catch (_: Exception) { }
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onResume() {
        super.onResume()
        if (token.isNotBlank() && ::root.isInitialized) {
            startLocation()
            poll()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocation()
        handler.removeCallbacksAndMessages(null)
        io.shutdownNow()
    }
}
