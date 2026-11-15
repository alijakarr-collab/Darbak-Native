package iq.darbak.nativegps

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

@Suppress("DEPRECATION")
class MainActivity : Activity(), LocationListener {
    private val ink = Color.rgb(20, 24, 34)
    private val panel = Color.rgb(30, 35, 48)
    private val teal = Color.rgb(17, 204, 169)
    private val violet = Color.rgb(140, 112, 255)
    private val muted = Color.rgb(165, 175, 193)
    private lateinit var mapView: MapView
    private lateinit var locationManager: LocationManager
    private lateinit var status: TextView
    private lateinit var speed: TextView
    private lateinit var routeInfo: TextView
    private var map: MapLibreMap? = null
    private var current: Location? = null
    private var target: LatLng? = null
    private var userMarker: Marker? = null
    private var targetMarker: Marker? = null
    private var routeLine: Polyline? = null
    private var routePoints: List<LatLng>? = null
    private var follow = true
    private var night = true
    private var loadingStyle = false
    private var lastCameraAt = 0L
    private val executor = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val userIcon by lazy { mapIcon(violet) }
    private val targetIcon by lazy { mapIcon(teal) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(applicationContext)
        window.statusBarColor = ink
        window.navigationBarColor = ink
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        root.addView(mapView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        makeOverlay(root)
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isCompassEnabled = true
            m.uiSettings.isAttributionEnabled = true
            m.uiSettings.isLogoEnabled = true
            m.cameraPosition = CameraPosition.Builder().target(LatLng(32.615, 44.027)).zoom(13.4).build()
            m.addOnMapClickListener { placeTarget(it); true }
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) follow = false
            }
            loadStyle()
        }
        if (hasPermission()) startGps() else requestPermissions(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 11)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun box(color: Int, radius: Int = 18): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), Color.rgb(58, 66, 81))
    }
    private fun txt(value: String, size: Float, bold: Boolean = false, color: Int = Color.WHITE): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_RTL
        }
    }
    private fun button(value: String, action: () -> Unit): TextView {
        return txt(value, 19f, true).apply {
            background = box(panel, 15)
            setOnClickListener { action() }
        }
    }
    private fun add(root: FrameLayout, view: View, gravity: Int, w: Int, h: Int, margin: Int = 12, vertical: Int = 0) {
        val lp = FrameLayout.LayoutParams(if (w == -1) -1 else dp(w), if (h == -2) -2 else dp(h), gravity)
        lp.setMargins(dp(margin), dp(vertical), dp(margin), dp(vertical))
        root.addView(view, lp)
    }
    private fun makeOverlay(root: FrameLayout) {
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = box(ink, 20)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = txt("دَرْبَك ◈", 22f, true)
        status = txt("GPS قيد التحديد", 12f, false, muted)
        head.addView(button("☰") { menu() }, LinearLayout.LayoutParams(dp(50), dp(49)))
        head.addView(status, LinearLayout.LayoutParams(0, dp(49), 1f))
        head.addView(brand, LinearLayout.LayoutParams(dp(116), dp(49)))
        add(root, head, Gravity.TOP or Gravity.CENTER_HORIZONTAL, -1, 61, 12, 12)

        val tools = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun tool(text: String, task: () -> Unit) {
            tools.addView(button(text, task), LinearLayout.LayoutParams(dp(50), dp(49)))
            tools.addView(View(this), LinearLayout.LayoutParams(dp(1), dp(9)))
        }
        tool("◎") { recenter() }
        tool("+") { map?.animateCamera(CameraUpdateFactory.zoomIn(), 230) }
        tool("−") { map?.animateCamera(CameraUpdateFactory.zoomOut(), 230) }
        tool("☾") { night = !night; loadStyle() }
        add(root, tools, Gravity.END or Gravity.CENTER_VERTICAL, 50, -2, 13)

        speed = txt("0\nكم/س", 21f, true).apply { background = box(ink, 22) }
        add(root, speed, Gravity.START or Gravity.BOTTOM, 90, 90, 14, 170)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(12), dp(15), dp(17))
            background = box(ink, 26)
        }
        bottom.addView(txt("وين رايح اليوم؟", 22f, true).apply {
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(-1, dp(41)))
        val search = button("⌕     ابحث عن مكان أو منطقة") { promptSearch() }.apply {
            textSize = 15f
            setTextColor(muted)
            gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            setPadding(dp(15), 0, dp(15), 0)
        }
        bottom.addView(search, LinearLayout.LayoutParams(-1, dp(48)))
        val quick = LinearLayout(this).apply { gravity = Gravity.CENTER }
        for (pair in listOf("⛽ وقود" to "محطة وقود كربلاء", "✚ مستشفى" to "مستشفى كربلاء", "♧ مسجد" to "مسجد كربلاء")) {
            val b = button(pair.first) { queryPlace(pair.second) }.apply { textSize = 13f }
            quick.addView(b, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                setMargins(dp(3), dp(10), dp(3), 0)
            })
        }
        bottom.addView(quick, LinearLayout.LayoutParams(-1, dp(58)))
        routeInfo = txt("اضغط على الخريطة لاختيار وجهتك", 13f, false, muted)
        bottom.addView(routeInfo, LinearLayout.LayoutParams(-1, dp(41)))
        add(root, bottom, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, -1, -2, 0)
    }
    private fun mapIcon(color: Int): Icon {
        val bmp = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        c.drawCircle(36f, 36f, 22f, paint)
        paint.color = color
        c.drawCircle(36f, 36f, 16f, paint)
        return IconFactory.getInstance(this).fromBitmap(bmp)
    }
    private fun loadStyle() {
        val m = map ?: return
        if (loadingStyle) return
        loadingStyle = true
        status.text = "جاري تحميل الخريطة…"
        val styleUrl = if (night) "https://tiles.openfreemap.org/styles/dark" else "https://tiles.openfreemap.org/styles/liberty"
        m.setStyle(Style.Builder().fromUri(styleUrl)) {
            loadingStyle = false
            status.text = if (current != null) "● GPS متصل" else "GPS قيد التحديد"
            targetMarker = null
            userMarker = null
            routeLine = null
            current?.let { updateUserPin(it) }
            target?.let { targetMarker = m.addMarker(MarkerOptions().position(it).icon(targetIcon)) }
            routePoints?.let { points ->
                if (points.size >= 2) routeLine = m.addPolyline(PolylineOptions().addAll(points).width(7f).color(teal))
            }
        }
        ui.postDelayed({
            if (loadingStyle) {
                loadingStyle = false
                status.text = "الخريطة تتأخر — جرّب تبديل الوضع"
            }
        }, 20000)
    }
    private fun placeTarget(p: LatLng) {
        target = p
        val m = map ?: return
        targetMarker?.let { m.removeMarker(it) }
        targetMarker = m.addMarker(MarkerOptions().position(p).icon(targetIcon).title("الوجهة"))
        routeInfo.text = "احسب المسار للوجهة المحددة — اضغط هنا"
        routeInfo.setTextColor(teal)
        routeInfo.setOnClickListener { calcRoute() }
    }
    private fun updateUserPin(loc: Location) {
        val m = map ?: return
        val p = LatLng(loc.latitude, loc.longitude)
        if (userMarker == null) userMarker = m.addMarker(MarkerOptions().position(p).icon(userIcon))
        else { userMarker!!.position = p; m.updateMarker(userMarker!!) }
    }
    private fun hasPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
               checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    private fun startGps() {
        if (!hasPermission()) return
        try {
            locationManager.removeUpdates(this)
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (locationManager.isProviderEnabled(p)) {
                    locationManager.requestLocationUpdates(p, 3000L, 5f, this, Looper.getMainLooper())
                    locationManager.getLastKnownLocation(p)?.let { onLocationChanged(it) }
                }
            }
        } catch (_: SecurityException) {
            status.text = "الرجاء السماح بالموقع"
        }
    }
    override fun onLocationChanged(loc: Location) {
        if (loc.hasAccuracy() && loc.accuracy > 160f) return
        current = loc
        status.text = "● GPS متصل"
        val kph = (if (loc.hasSpeed()) loc.speed * 3.6f else 0f).roundToInt()
        speed.text = kph.toString() + "\nكم/س"
        updateUserPin(loc)
        if (follow && System.currentTimeMillis() - lastCameraAt > 2200) {
            lastCameraAt = System.currentTimeMillis()
            val m = map ?: return
            val bearing = if (loc.hasBearing() && loc.speed > 2f) loc.bearing.toDouble() else m.cameraPosition.bearing
            m.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
                .target(LatLng(loc.latitude, loc.longitude))
                .zoom(m.cameraPosition.zoom.coerceAtLeast(15.2))
                .bearing(bearing).build()), 440)
        }
    }
    private fun recenter() {
        follow = true
        val p = current ?: run {
            Toast.makeText(this, "بانتظار إشارة الموقع", Toast.LENGTH_SHORT).show()
            if (hasPermission()) startGps()
            return
        }
        map?.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
            .target(LatLng(p.latitude, p.longitude)).zoom(16.2).build()), 580)
    }
    private fun menu() {
        val opts = arrayOf("وضع ليلي / نهاري", "تحديد موقعي", "إعادة حساب المسار", "حذف الوجهة")
        AlertDialog.Builder(this).setTitle("دَرْبَك").setItems(opts) { _, index ->
            when (index) {
                0 -> { night = !night; loadStyle() }
                1 -> recenter()
                2 -> calcRoute()
                3 -> {
                    target = null
                    targetMarker?.let { map?.removeMarker(it) }
                    targetMarker = null
                    routeLine?.let { map?.removePolyline(it) }
                    routeLine = null
                    routePoints = null
                    routeInfo.text = "اضغط على الخريطة لاختيار وجهتك"
                    routeInfo.setTextColor(muted)
                    routeInfo.setOnClickListener(null)
                }
            }
        }.show()
    }
    private fun promptSearch() {
        val input = EditText(this).apply {
            hint = "مثلاً: مرقد الإمام الحسين، كربلاء"
            setSingleLine()
        }
        AlertDialog.Builder(this).setTitle("وين رايح اليوم؟").setView(input)
            .setPositiveButton("بحث") { _, _ -> queryPlace(input.text.toString()) }
            .setNegativeButton("إلغاء", null).show()
    }
    private fun queryPlace(query: String) {
        if (query.isBlank()) return
        routeInfo.text = "جاري البحث عن المكان…"
        executor.execute {
            try {
                val url = "https://nominatim.openstreetmap.org/search?format=json&limit=1&countrycodes=iq&q=" +
                    URLEncoder.encode(query, "UTF-8")
                val results = JSONArray(getUrl(url))
                if (results.length() == 0) throw Exception("لم يُعثر على المكان")
                val item = results.getJSONObject(0)
                val p = LatLng(item.getDouble("lat"), item.getDouble("lon"))
                ui.post {
                    placeTarget(p)
                    map?.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
                        .target(p).zoom(15.3).build()), 400)
                }
            } catch (e: Exception) {
                ui.post { routeInfo.text = "تعذّر البحث، تأكد من الإنترنت" }
            }
        }
    }
    private fun calcRoute() {
        val from = current
        val dest = target
        if (from == null || dest == null) {
            Toast.makeText(this, "حدد موقعك والوجهة أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        routeInfo.text = "جاري حساب المسار…"
        executor.execute {
            try {
                val url = "https://router.project-osrm.org/route/v1/driving/" +
                    from.longitude.toString() + "," + from.latitude.toString() + ";" +
                    dest.longitude.toString() + "," + dest.latitude.toString() +
                    "?overview=full&geometries=geojson"
                val obj = JSONObject(getUrl(url)).getJSONArray("routes").getJSONObject(0)
                val coords = obj.getJSONObject("geometry").getJSONArray("coordinates")
                val points = ArrayList<LatLng>()
                for (i in 0 until coords.length()) {
                    val pair = coords.getJSONArray(i)
                    points.add(LatLng(pair.getDouble(1), pair.getDouble(0)))
                }
                val km = obj.getDouble("distance") / 1000.0
                val minutes = (obj.getDouble("duration") / 60.0).roundToInt()
                ui.post {
                    val m = map ?: return@post
                    routeLine?.let { m.removePolyline(it) }
                    routePoints = points
                    routeLine = m.addPolyline(PolylineOptions().addAll(points).width(7f).color(teal))
                    routeInfo.text = String.format(Locale.US, "%.1f كم • %d دقيقة تقريباً", km, minutes)
                    routeInfo.setTextColor(Color.WHITE)
                }
            } catch (_: Exception) {
                ui.post { routeInfo.text = "حساب المسار غير متاح حالياً" }
            }
        }
    }
    private fun getUrl(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "DarbakNavigation/1.0 (native Android beta)")
        conn.setRequestProperty("Accept-Language", "ar,en;q=0.5")
        try {
            if (conn.responseCode !in 200..299) throw Exception("HTTP " + conn.responseCode)
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
    override fun onProviderEnabled(provider: String) { if (hasPermission()) startGps() }
    override fun onProviderDisabled(provider: String) { status.text = "GPS غير متاح" }
    @Deprecated("Compatibility")
    override fun onStatusChanged(provider: String?, state: Int, extras: Bundle?) {}
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 11 && hasPermission()) startGps()
    }
    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume(); if (hasPermission()) startGps() }
    override fun onPause() { locationManager.removeUpdates(this); mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(state: Bundle) { super.onSaveInstanceState(state); mapView.onSaveInstanceState(state) }
    override fun onDestroy() { locationManager.removeUpdates(this); executor.shutdownNow(); mapView.onDestroy(); super.onDestroy() }
}