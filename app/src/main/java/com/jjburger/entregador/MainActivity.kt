package com.jjburger.entregador

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class Delivery(
    val id:String,
    val number:String,
    val lat:Double?,
    val lon:Double?,
    val status:String,
    val position:Int
)

class MainActivity : AppCompatActivity(), LocationListener {
    private val API = "https://jxpiazntgqasvonvrtpv.supabase.co/functions/v1/jjburger-driver"
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createChannel()
        askPermissions()
        token = prefs.getString("token","") ?: ""
        driverName = prefs.getString("name","") ?: ""
        if (token.isBlank()) showLogin() else showRoute()
    }

    private fun base(): LinearLayout {
        val v=LinearLayout(this)
        v.orientation=LinearLayout.VERTICAL
        v.setPadding(28,28,28,28)
        v.setBackgroundColor(Color.rgb(13,15,19))
        return v
    }
    private fun text(s:String,size:Float=18f,bold:Boolean=false):TextView {
        return TextView(this).apply {
            text=s; textSize=size; setTextColor(Color.WHITE)
            if(bold) setTypeface(typeface,1)
            setPadding(0,10,0,10)
        }
    }
    private fun button(s:String, on:()->Unit):Button {
        return Button(this).apply {
            text=s; textSize=17f; isAllCaps=false
            setOnClickListener{on()}
        }
    }

    private fun showLogin() {
        root=base()
        root.addView(text("🍔 JJ Entregador",28f,true))
        root.addView(text("Digite seu código de acesso uma única vez.",16f))
        val code=EditText(this).apply {
            hint="Código de 6 dígitos"; inputType=2
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        val msg=text("",15f)
        root.addView(code)
        root.addView(button("ENTRAR") {
            val c=code.text.toString().trim()
            if(c.length<4){ msg.text="Informe o código."; return@button }
            msg.text="Entrando..."
            io.execute {
                try {
                    val x=request("login","POST",JSONObject().put("code",c),null)
                    val d=x.getJSONObject("driver")
                    token=x.getString("token"); driverName=d.getString("name")
                    prefs.edit().putString("token",token).putString("name",driverName).apply()
                    runOnUiThread { showRoute() }
                } catch(e:Exception) { runOnUiThread{msg.text=e.message ?: "Erro"} }
            }
        })
        root.addView(msg)
        setContentView(root)
    }

    private fun showRoute() {
        root=base()
        setContentView(ScrollView(this).apply{addView(root)})
        startLocation()
        poll()
    }

    private fun render() {
        root.removeAllViews()
        root.addView(text("Olá, $driverName 👋",27f,true))
        root.addView(text("${deliveries.size} entrega(s) na rota",16f))
        if(deliveries.isEmpty()) {
            root.addView(text("✅ Nenhuma entrega pendente.",20f,true))
            root.addView(text("O app atualizará automaticamente quando uma nova entrega for atribuída.",15f))
        } else {
            val next=deliveries.first()
            val card=LinearLayout(this).apply{
                orientation=LinearLayout.VERTICAL
                setPadding(22,22,22,22)
                setBackgroundColor(Color.rgb(28,32,39))
            }
            card.addView(text("PRÓXIMA ENTREGA",14f,true))
            card.addView(text("#${next.number}",30f,true))
            card.addView(text("Posição ${next.position} da rota",15f))
            val nav=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            nav.addView(button("WAZE"){ navigate(next,true) }, LinearLayout.LayoutParams(0,-2,1f))
            nav.addView(button("MAPS"){ navigate(next,false) }, LinearLayout.LayoutParams(0,-2,1f))
            card.addView(nav)
            card.addView(button("✅ ENTREGA CONCLUÍDA"){ complete(next) })
            root.addView(card)
            if(deliveries.size>1) {
                root.addView(text("Depois",20f,true))
                deliveries.drop(1).forEach {
                    root.addView(text("${it.position}. Pedido #${it.number}",17f,true))
                }
            }
        }
        root.addView(button("Atualizar agora"){ fetchRoute() })
        root.addView(button("Sair deste entregador"){
            prefs.edit().clear().apply(); token=""; stopLocation(); showLogin()
        })
    }

    private fun poll() {
        handler.removeCallbacksAndMessages(null)
        fetchRoute()
        handler.postDelayed(object:Runnable{
            override fun run(){ fetchRoute(); handler.postDelayed(this,3000) }
        },3000)
    }

    private fun fetchRoute() {
        if(token.isBlank()) return
        io.execute {
            try {
                val x=request("route","GET",null,token)
                driverName=x.getJSONObject("driver").getString("name")
                val a=x.getJSONArray("orders")
                val list=ArrayList<Delivery>()
                for(i in 0 until a.length()){
                    val o=a.getJSONObject(i)
                    list.add(Delivery(
                        o.getString("id"),
                        o.getString("order_number"),
                        if(o.isNull("latitude")) null else o.getDouble("latitude"),
                        if(o.isNull("longitude")) null else o.getDouble("longitude"),
                        o.getString("status"),
                        if(o.isNull("route_position")) i+1 else o.getInt("route_position")
                    ))
                }
                val newIds=list.map{it.id}.toSet() - known
                if(!firstLoad && newIds.isNotEmpty()) notifyNew(list.firstOrNull{it.id in newIds})
                known.clear(); known.addAll(list.map{it.id})
                firstLoad=false
                deliveries=list
                runOnUiThread{render()}
            } catch(_:Exception) {}
        }
    }

    private fun navigate(d:Delivery, waze:Boolean) {
        if(d.lat==null || d.lon==null){ Toast.makeText(this,"Localização do cliente não recebida.",Toast.LENGTH_LONG).show(); return }
        io.execute { try { request("start","POST",JSONObject().put("order_id",d.id),token) } catch(_:Exception){} }
        val uri = if(waze) Uri.parse("waze://?ll=${d.lat},${d.lon}&navigate=yes")
                  else Uri.parse("google.navigation:q=${d.lat},${d.lon}&mode=d")
        val intent=Intent(Intent.ACTION_VIEW,uri)
        try { startActivity(intent) } catch(e:Exception) {
            startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${d.lat},${d.lon}")))
        }
    }

    private fun complete(d:Delivery) {
        io.execute {
            try {
                request("complete","POST",JSONObject().put("order_id",d.id),token)
                runOnUiThread{Toast.makeText(this,"Entrega concluída",Toast.LENGTH_SHORT).show(); fetchRoute()}
            } catch(e:Exception){runOnUiThread{Toast.makeText(this,e.message,Toast.LENGTH_LONG).show()}}
        }
    }

    private fun request(action:String, method:String, body:JSONObject?, auth:String?):JSONObject {
        val c=(URL("$API?action=$action").openConnection() as HttpURLConnection)
        c.requestMethod=method; c.connectTimeout=8000; c.readTimeout=8000
        c.setRequestProperty("Content-Type","application/json")
        if(!auth.isNullOrBlank()) c.setRequestProperty("x-driver-token",auth)
        if(body!=null){c.doOutput=true;c.outputStream.use{it.write(body.toString().toByteArray())}}
        val stream=if(c.responseCode in 200..299)c.inputStream else c.errorStream
        val txt=stream.bufferedReader().use{it.readText()}
        val x=JSONObject(txt)
        if(c.responseCode !in 200..299) throw Exception(x.optString("error","Erro ${c.responseCode}"))
        return x
    }

    private fun createChannel() {
        if(Build.VERSION.SDK_INT>=26){
            val ch=NotificationChannel("new_delivery","Novas entregas",NotificationManager.IMPORTANCE_HIGH).apply{
                description="Avisos de novas entregas"
                enableVibration(true)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }
    private fun notifyNew(d:Delivery?) {
        val n=NotificationCompat.Builder(this,"new_delivery")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("🍔 Nova entrega")
            .setContentText(if(d!=null)"Pedido #${d.number} foi adicionado à sua rota." else "Nova entrega adicionada.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true).build()
        if(ActivityCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT<33)
            getSystemService(NotificationManager::class.java).notify((System.currentTimeMillis()%100000).toInt(),n)
    }

    private fun askPermissions() {
        val req=mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if(Build.VERSION.SDK_INT>=33) req.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing=req.filter{ActivityCompat.checkSelfPermission(this,it)!=PackageManager.PERMISSION_GRANTED}
        if(missing.isNotEmpty()) ActivityCompat.requestPermissions(this,missing.toTypedArray(),55)
    }
    private fun startLocation() {
        if(ActivityCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return
        locationManager=getSystemService(LOCATION_SERVICE) as LocationManager
        try { locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER,10000L,30f,this) } catch(_:Exception){}
        try { locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,15000L,50f,this) } catch(_:Exception){}
    }
    private fun stopLocation(){try{locationManager?.removeUpdates(this)}catch(_:Exception){}}
    override fun onLocationChanged(l:Location) {
        if(token.isBlank())return
        io.execute { try { request("position","POST",JSONObject().put("latitude",l.latitude).put("longitude",l.longitude),token) } catch(_:Exception){} }
    }
    override fun onDestroy(){super.onDestroy();stopLocation();handler.removeCallbacksAndMessages(null);io.shutdownNow()}
}
