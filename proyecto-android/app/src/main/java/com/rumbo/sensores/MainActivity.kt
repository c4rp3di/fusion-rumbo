package com.rumbo.sensores

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.ConsumerIrManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rumbo: la interfaz es HTML (assets/index.html) y esta actividad le presta los sensores
 * que el navegador no da: brújula con declinación real, detector de pasos, barómetro, luz,
 * NFC, infrarrojos y almacenamiento propio. Todo funciona sin red.
 */
class MainActivity : AppCompatActivity(), SensorEventListener, LocationListener, NfcAdapter.ReaderCallback {

    private companion object {
        const val PERM_LOC = 11
        const val PERM_ACT = 12
        const val PERM_BLE = 13
        const val PERM_BODY = 14
    }

    private lateinit var web: WebView
    private lateinit var sm: SensorManager
    private lateinit var lm: LocationManager
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var wantLoc = false
    @Volatile private var wantHead = false
    @Volatile private var wantSteps = false
    @Volatile private var wantNfc = false
    @Volatile private var wantBle = false
    private val bleLast = HashMap<String, Long>()
    private val streams = HashMap<Int, Long>()
    private val lastPush = HashMap<Int, Long>()
    private var decl = 0f
    private var rotAcc = 3
    private var magAcc = 3
    private var lastHeadMs = 0L
    private val rot = FloatArray(9)
    private val prefs by lazy { getSharedPreferences("rumbo", Context.MODE_PRIVATE) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Rumbo")
        web.loadUrl("file:///android_asset/index.html")
    }

    override fun onPause() {
        super.onPause()
        sm.unregisterListener(this)
        for (t in streams.keys) {
            val s = sm.getDefaultSensor(t)
            if (s != null && s.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT) {
                try { sm.cancelTriggerSensor(trig, s) } catch (e: Exception) { }
            }
        }
        try { lm.removeUpdates(this) } catch (e: Exception) { }
        try { NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this) } catch (e: Exception) { }
        endBle()
    }

    override fun onResume() {
        super.onResume()
        if (wantHead) { reg(Sensor.TYPE_ROTATION_VECTOR, false); reg(Sensor.TYPE_MAGNETIC_FIELD, false) }
        if (wantSteps && (Build.VERSION.SDK_INT < 29 || has(Manifest.permission.ACTIVITY_RECOGNITION))) {
            reg(Sensor.TYPE_STEP_DETECTOR, true)
        }
        for ((t, ms) in streams) if (!needsBody(t) || has(Manifest.permission.BODY_SENSORS)) reg(t, ms <= 70L)
        if (wantLoc && has(Manifest.permission.ACCESS_FINE_LOCATION)) beginLoc()
        if (wantNfc) enableNfc()
        if (wantBle && blePermsOk()) beginBle()
    }

    // ---------- utilidades ----------

    private fun has(p: String): Boolean =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /** Llama a window.<fn>(<args>) en la página. */
    private fun js(fn: String, args: String) {
        main.post { web.evaluateJavascript("window.$fn&&window.$fn($args)", null) }
    }

    private fun finite(f: Float): Double = if (f.isNaN() || f.isInfinite()) 0.0 else f.toDouble()

    /** Los sensores de evento único (movimiento significativo, gestos…) se piden con otro método y hay que re-armarlos. */
    private val trig = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            val t = event.sensor.type
            val vals = JSONArray()
            for (i in 0 until Math.min(event.values.size, 6)) vals.put(finite(event.values[i]))
            js("onSensor", "$t,$vals,0")
            if (streams.containsKey(t)) {
                try { sm.requestTriggerSensor(this, event.sensor) } catch (e: Exception) { }
            }
        }
    }

    private fun reg(t: Int, fast: Boolean) {
        val s = sm.getDefaultSensor(t) ?: return
        if (s.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT) {
            try { sm.requestTriggerSensor(trig, s) } catch (e: Exception) {
                js("onNative", "{\"k\":\"sensor\",\"type\":$t,\"err\":\"fail\"}")
            }
            return
        }
        val rate = if (fast) SensorManager.SENSOR_DELAY_GAME else SensorManager.SENSOR_DELAY_UI
        try {
            sm.registerListener(this, s, rate, main)
        } catch (e: SecurityException) {
            js("onNative", "{\"k\":\"sensor\",\"type\":$t,\"err\":\"perm\"}")
        } catch (e: Exception) {
            js("onNative", "{\"k\":\"sensor\",\"type\":$t,\"err\":\"fail\"}")
        }
    }

    /** Los sensores de pulso (21) y latido (31) exigen el permiso de sensores corporales. */
    private fun needsBody(t: Int): Boolean = t == 21 || t == 31

    private fun needed(t: Int): Boolean =
        (wantHead && (t == Sensor.TYPE_ROTATION_VECTOR || t == Sensor.TYPE_MAGNETIC_FIELD)) ||
            (wantSteps && t == Sensor.TYPE_STEP_DETECTOR) || streams.containsKey(t)

    private fun unreg(t: Int) {
        if (needed(t)) return
        val s = sm.getDefaultSensor(t) ?: return
        if (s.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT) {
            try { sm.cancelTriggerSensor(trig, s) } catch (e: Exception) { }
            return
        }
        sm.unregisterListener(this, s)
    }

    // ---------- GPS ----------

    private fun askLoc() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            PERM_LOC
        )
    }

    @SuppressLint("MissingPermission")
    private fun beginLoc() {
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (last != null) updateDecl(last)
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) js("onNative", "{\"k\":\"gps\",\"on\":false}")
        } catch (e: Exception) {
            js("onNative", "{\"k\":\"perm\",\"what\":\"loc\",\"ok\":false}")
        }
    }

    private fun updateDecl(l: Location) {
        val alt = if (l.hasAltitude()) l.altitude else 0.0
        decl = GeomagneticField(l.latitude.toFloat(), l.longitude.toFloat(), alt.toFloat(), System.currentTimeMillis()).declination
    }

    override fun onLocationChanged(location: Location) {
        updateDecl(location)
        val o = JSONObject()
        o.put("lat", location.latitude)
        o.put("lon", location.longitude)
        o.put("acc", if (location.hasAccuracy()) location.accuracy.toDouble() else 99.0)
        o.put("alt", if (location.hasAltitude()) location.altitude else JSONObject.NULL)
        o.put("speed", if (location.hasSpeed()) location.speed.toDouble() else JSONObject.NULL)
        o.put("bearing", if (location.hasBearing()) location.bearing.toDouble() else JSONObject.NULL)
        js("onLoc", o.toString())
    }

    override fun onProviderEnabled(provider: String) { js("onNative", "{\"k\":\"gps\",\"on\":true}") }
    override fun onProviderDisabled(provider: String) { js("onNative", "{\"k\":\"gps\",\"on\":false}") }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (requestCode == PERM_LOC) {
            val fine = has(Manifest.permission.ACCESS_FINE_LOCATION)
            js("onNative", "{\"k\":\"perm\",\"what\":\"loc\",\"ok\":$fine}")
            if (fine && wantLoc) beginLoc()
        }
        if (requestCode == PERM_BLE) {
            val all = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            js("onNative", "{\"k\":\"perm\",\"what\":\"ble\",\"ok\":$all}")
            if (all && wantBle) beginBle()
        }
        if (requestCode == PERM_BODY) {
            js("onNative", "{\"k\":\"perm\",\"what\":\"body\",\"ok\":$ok}")
            if (ok) for ((t, ms) in streams) if (needsBody(t)) reg(t, ms <= 70L)
        }
        if (requestCode == PERM_ACT) {
            js("onNative", "{\"k\":\"perm\",\"what\":\"steps\",\"ok\":$ok}")
            if (ok && wantSteps) reg(Sensor.TYPE_STEP_DETECTOR, true)
        }
    }

    // ---------- sensores ----------

    override fun onSensorChanged(e: SensorEvent) {
        val t = e.sensor.type
        if (t == Sensor.TYPE_ROTATION_VECTOR && wantHead) heading(e)
        if (t == Sensor.TYPE_STEP_DETECTOR && wantSteps) js("onStep", "{}")
        val gap = streams[t] ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - (lastPush[t] ?: 0L) < gap) return
        lastPush[t] = now
        val vals = JSONArray()
        for (i in 0 until Math.min(e.values.size, 6)) {
            val v = e.values[i].toDouble()
            vals.put(if (v.isNaN() || v.isInfinite()) 0.0 else v)
        }
        js("onSensor", "$t,$vals,${e.accuracy}")
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_ROTATION_VECTOR) rotAcc = accuracy
        if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) magAcc = accuracy
    }

    /**
     * Rumbo respecto al norte geográfico. Con el móvil plano mira hacia donde apunta la parte de
     * arriba; en vertical, hacia donde mira la cámara trasera. Suma la declinación magnética real.
     */
    private fun heading(e: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastHeadMs < 50L) return
        lastHeadMs = now
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        val te = rot[1].toDouble()
        val tn = rot[4].toDouble()
        val be = -rot[2].toDouble()
        val bn = -rot[5].toDouble()
        val east: Double
        val north: Double
        if (Math.hypot(te, tn) >= Math.hypot(be, bn)) { east = te; north = tn } else { east = be; north = bn }
        var h = Math.toDegrees(Math.atan2(east, north)) + decl
        h = ((h % 360.0) + 360.0) % 360.0
        val hr = Math.round(h * 10.0) / 10.0
        val dr = Math.round(decl * 10f) / 10f
        val acc = Math.min(rotAcc, magAcc)
        js("onHead", "{\"h\":$hr,\"acc\":$acc,\"d\":$dr}")
    }

    // ---------- Bluetooth LE y Wi-Fi (rastreo) ----------

    private fun blePerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun blePermsOk(): Boolean = blePerms().all { has(it) }

    private val bleCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val a = result.device.address
            val now = SystemClock.elapsedRealtime()
            if (now - (bleLast[a] ?: 0L) < 250L) return
            bleLast[a] = now
            val rec = result.scanRecord
            var man = -1
            val md = rec?.manufacturerSpecificData
            if (md != null && md.size() > 0) man = md.keyAt(0)
            val tx = rec?.txPowerLevel ?: Int.MIN_VALUE
            val o = JSONObject()
            o.put("a", a)
            o.put("n", rec?.deviceName ?: "")
            o.put("r", result.rssi)
            o.put("m", man)
            o.put("t", if (tx == Int.MIN_VALUE) JSONObject.NULL else tx)
            js("onBle", o.toString())
        }

        override fun onScanFailed(errorCode: Int) {
            js("onNative", "{\"k\":\"ble\",\"err\":$errorCode}")
        }
    }

    /** "ok", "off" (Bluetooth apagado) o "nobt" (sin Bluetooth LE). */
    @SuppressLint("MissingPermission")
    private fun beginBle(): String {
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return "nobt"
        val ad = bm.adapter ?: return "nobt"
        if (!ad.isEnabled) { js("onNative", "{\"k\":\"bt\",\"on\":false}"); return "off" }
        val sc = ad.bluetoothLeScanner ?: return "off"
        return try {
            sc.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), bleCb)
            "ok"
        } catch (e: Exception) { "err" }
    }

    @SuppressLint("MissingPermission")
    private fun endBle() {
        try {
            val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bm?.adapter?.bluetoothLeScanner?.stopScan(bleCb)
        } catch (e: Exception) { }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun pushWifi(w: WifiManager) {
        try {
            val arr = JSONArray()
            for (r in w.scanResults) {
                val x = JSONObject()
                x.put("s", r.SSID ?: "")
                x.put("b", r.BSSID ?: "")
                x.put("l", r.level)
                x.put("f", r.frequency)
                x.put("c", r.capabilities ?: "")
                arr.put(x)
            }
            js("onWifi", arr.toString())
        } catch (e: Exception) { }
    }

    // ---------- NFC ----------

    private fun enableNfc() {
        val a = NfcAdapter.getDefaultAdapter(this) ?: return
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V
        try { a.enableReaderMode(this, this, flags, null) } catch (e: Exception) { }
    }

    override fun onTagDiscovered(tag: Tag) {
        val uid = tag.id.joinToString("") { "%02X".format(it) }
        val sb = StringBuilder()
        try {
            val nd = Ndef.get(tag)
            if (nd != null) {
                nd.connect()
                val msg = nd.ndefMessage
                if (msg != null) {
                    for (r in msg.records) sb.append(recText(r)).append("\n")
                }
                nd.close()
            }
        } catch (e: Exception) { }
        val o = JSONObject()
        o.put("uid", uid)
        o.put("tech", tag.techList.joinToString(",") { it.substringAfterLast('.') })
        o.put("text", sb.toString().trim())
        js("onNfc", o.toString())
    }

    private fun recText(r: NdefRecord): String {
        try {
            if (r.tnf == NdefRecord.TNF_WELL_KNOWN && java.util.Arrays.equals(r.type, NdefRecord.RTD_TEXT)) {
                val p = r.payload
                val lang = p[0].toInt() and 0x3F
                val cs = if ((p[0].toInt() and 0x80) != 0) Charsets.UTF_16 else Charsets.UTF_8
                return String(p, 1 + lang, p.size - 1 - lang, cs)
            }
            val u = r.toUri()
            if (u != null) return u.toString()
            return String(r.payload, Charsets.UTF_8)
        } catch (e: Exception) {
            return ""
        }
    }

    // ---------- puente JS (window.Rumbo) ----------

    inner class Bridge {

        @JavascriptInterface
        fun startLoc() {
            wantLoc = true
            runOnUiThread { if (has(Manifest.permission.ACCESS_FINE_LOCATION)) beginLoc() else askLoc() }
        }

        @JavascriptInterface
        fun stopLoc() {
            wantLoc = false
            runOnUiThread { try { lm.removeUpdates(this@MainActivity) } catch (e: Exception) { } }
        }

        @JavascriptInterface
        fun startHead() {
            wantHead = true
            runOnUiThread { reg(Sensor.TYPE_ROTATION_VECTOR, false); reg(Sensor.TYPE_MAGNETIC_FIELD, false) }
        }

        @JavascriptInterface
        fun stopHead() {
            wantHead = false
            runOnUiThread { unreg(Sensor.TYPE_ROTATION_VECTOR); unreg(Sensor.TYPE_MAGNETIC_FIELD) }
        }

        /** "ok", "noperm" (pidiendo permiso; avisará por onNative) o "nosensor" (usar acelerómetro). */
        @JavascriptInterface
        fun startSteps(): String {
            if (sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) == null) return "nosensor"
            wantSteps = true
            if (Build.VERSION.SDK_INT >= 29 && !has(Manifest.permission.ACTIVITY_RECOGNITION)) {
                runOnUiThread {
                    ActivityCompat.requestPermissions(
                        this@MainActivity, arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), PERM_ACT
                    )
                }
                return "noperm"
            }
            runOnUiThread { reg(Sensor.TYPE_STEP_DETECTOR, true) }
            return "ok"
        }

        @JavascriptInterface
        fun stopSteps() {
            wantSteps = false
            runOnUiThread { unreg(Sensor.TYPE_STEP_DETECTOR) }
        }

        @JavascriptInterface
        fun startSensor(type: Int, hz: Int) {
            runOnUiThread {
                streams[type] = 1000L / Math.max(1, hz)
                lastPush.remove(type)
                if (needsBody(type) && !has(Manifest.permission.BODY_SENSORS)) {
                    ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.BODY_SENSORS), PERM_BODY)
                } else {
                    reg(type, hz > 15)
                }
            }
        }

        @JavascriptInterface
        fun stopSensor(type: Int) {
            runOnUiThread {
                streams.remove(type)
                unreg(type)
            }
        }

        @JavascriptInterface
        fun info(): String {
            val o = JSONObject()
            o.put("sdk", Build.VERSION.SDK_INT)
            o.put("model", Build.MODEL)
            val ir = this@MainActivity.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
            o.put("ir", ir?.hasIrEmitter() == true)
            val na = NfcAdapter.getDefaultAdapter(this@MainActivity)
            o.put("nfc", if (na == null) 0 else if (na.isEnabled) 2 else 1)
            val arr = JSONArray()
            for (s in sm.getSensorList(Sensor.TYPE_ALL)) {
                val x = JSONObject()
                x.put("type", s.type)
                x.put("name", s.name)
                x.put("vendor", s.vendor)
                x.put("max", finite(s.maximumRange))
                x.put("power", finite(s.power))
                x.put("mode", s.reportingMode)
                x.put("wake", s.isWakeUpSensor)
                arr.put(x)
            }
            o.put("sensors", arr)
            return o.toString()
        }

        @JavascriptInterface
        fun nfcStart() {
            wantNfc = true
            runOnUiThread { enableNfc() }
        }

        @JavascriptInterface
        fun nfcStop() {
            wantNfc = false
            runOnUiThread {
                try { NfcAdapter.getDefaultAdapter(this@MainActivity)?.disableReaderMode(this@MainActivity) } catch (e: Exception) { }
            }
        }

        /** Datos del emisor infrarrojo: si existe y qué rangos de portadora admite (Hz). */
        @JavascriptInterface
        fun irInfo(): String {
            val o = JSONObject()
            val ir = this@MainActivity.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
            val has = ir?.hasIrEmitter() == true
            o.put("has", has)
            val arr = JSONArray()
            if (has) {
                val rs = ir?.carrierFrequencies
                if (rs != null) for (r in rs) arr.put(JSONArray().put(r.minFrequency).put(r.maxFrequency))
            }
            o.put("ranges", arr)
            return o.toString()
        }

        /** Emite un patrón infrarrojo: frecuencia en Hz y duraciones en µs separadas por comas. */
        @JavascriptInterface
        fun irSend(freq: Int, pattern: String): String {
            val ir = this@MainActivity.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager ?: return "no"
            if (!ir.hasIrEmitter()) return "no"
            val rs = ir.carrierFrequencies
            if (rs != null && rs.isNotEmpty() && rs.none { freq >= it.minFrequency && freq <= it.maxFrequency }) return "freq"
            val p = pattern.split(",").mapNotNull { it.trim().toIntOrNull() }.toIntArray()
            if (p.isEmpty()) return "bad"
            return try { ir.transmit(freq, p); "ok" } catch (e: Exception) { "err" }
        }

        /** "ok", "noperm" (pidiendo permisos; avisará por onNative), "off" o "nobt". */
        @JavascriptInterface
        fun startBle(): String {
            wantBle = true
            if (!blePermsOk()) {
                runOnUiThread { ActivityCompat.requestPermissions(this@MainActivity, blePerms(), PERM_BLE) }
                return "noperm"
            }
            return beginBle()
        }

        @JavascriptInterface
        fun stopBle() {
            wantBle = false
            endBle()
        }

        /** Pide un escaneo Wi-Fi y devuelve la lista por window.onWifi (ahora y 4 s después). */
        @Suppress("DEPRECATION")
        @JavascriptInterface
        fun wifiScan(): String {
            if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
                runOnUiThread { askLoc() }
                return "noperm"
            }
            val w = this@MainActivity.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return "nowifi"
            if (!w.isWifiEnabled) return "off"
            try { w.startScan() } catch (e: Exception) { }
            pushWifi(w)
            main.postDelayed({ pushWifi(w) }, 4000L)
            return "ok"
        }

        @JavascriptInterface
        fun save(k: String, v: String) {
            prefs.edit().putString(k, v).apply()
        }

        @JavascriptInterface
        fun load(k: String): String = prefs.getString(k, "") ?: ""

        @JavascriptInterface
        fun keepAwake(on: Boolean) {
            runOnUiThread {
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        @JavascriptInterface
        fun dim(on: Boolean) {
            runOnUiThread {
                val p = window.attributes
                p.screenBrightness = if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = p
            }
        }
    }
}
