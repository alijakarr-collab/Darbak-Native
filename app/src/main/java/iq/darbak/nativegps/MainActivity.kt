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
import android.view.WindowManager
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
    private lateinit var appearance: DarbakAppearance
    private lateinit var vehicleIcons: DarbakVehicleIcons
    private lateinit var uiRoot: FrameLayout
    private lateinit var bottomPanel: LinearLayout
    private lateinit var drivePanel: LinearLayout
    private lateinit var driveTitle: TextView
    private lateinit var driveDetail: TextView
    private var lastMessage = "اضغط على الخريطة لاختيار وجهتك"
    private var lastHeading = 0.0
    private var pendingStyle = false
    private var navigationalMode = false
    private val ink: Int get() = appearance.theme.panel
    private val panel: Int get() = appearance.theme.panelSecondary
    private val teal: Int get() = appearance.accent.color
    private val muted: Int get() = if (appearance.theme.dark) Color.rgb(169, 182, 199) else Color.rgb(82, 96, 110)
    private val foreground: Int get() = appearance.theme.label
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
    private lateinit var voice: IraqiVoiceGuide
    private lateinit var routeController: RouteController
    private var follow = true
    private var loadingStyle = false
    private var lastCameraAt = 0L
    private val executor = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val targetIcon: Icon get() = mapIcon(teal)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appearance = DarbakAppearance(this)
        vehicleIcons = DarbakVehicleIcons(this)
        follow = appearance.follow
        MapLibre.getInstance(applicationContext)
        window.statusBarColor = ink
        window.navigationBarColor = ink
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        uiRoot = FrameLayout(this)
        uiRoot.addView(mapView, FrameLayout.LayoutParams(-1, -1))
        setContentView(uiRoot)
        makeOverlay(uiRoot)
        voice = IraqiVoiceGuide(this)
        voice.enabled = appearance.voice
        routeController = RouteController(this, voice, { message ->
            lastMessage = message
            routeInfo.text = message
            if (navigationalMode) updateDriveMessage(message)
        }, { active -> setDrivingMode(active) })
        routeController.autoReroute = appearance.reroute
        routeController.setAccent(teal)
        routeInfo.setOnClickListener { routeController.showChoicesOrCalculate() }
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
    private fun txt(value: String, size: Float, bold: Boolean = false, color: Int = foreground): TextView {
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
        return txt(value, 19f, true, foreground).apply {
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
        tool("🛣") { routeController.showChoicesOrCalculate() }
        tool("♫") { routeController.repeatVoice() }
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
        if (loadingStyle) { pendingStyle = true; return }
        loadingStyle = true
        status.text = "جاري تحميل الخريطة…"
        val styleUrl = appearance.mapStyle
        m.setStyle(Style.Builder().fromUri(styleUrl)) {
            loadingStyle = false
            status.text = if (current != null) "● GPS متصل" else "GPS قيد التحديد"
            targetMarker = null
            userMarker = null
            current?.let { updateUserPin(it) }
            target?.let { targetMarker = m.addMarker(MarkerOptions().position(it).icon(targetIcon)) }
            routeController.onMapStyleLoaded(m)
            if (pendingStyle) {
                pendingStyle = false
                ui.post { loadStyle() }
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
        routeInfo.text = "الوجهة محددة، جاري البحث عن الطرق…"
        routeInfo.setTextColor(teal)
        routeInfo.setOnClickListener { routeController.showChoicesOrCalculate() }
        routeController.selectDestination(p)
    }
    private fun updateUserPin(loc: Location) {
        val m = map ?: return
        val point = routeController.snappedPosition(loc) ?: LatLng(loc.latitude, loc.longitude)
        val heading = if (navigationalMode && follow && appearance.perspective) 0f
            else (((lastHeading - m.cameraPosition.bearing + 360.0) % 360.0).toFloat())
        val carIcon = vehicleIcons.icon(appearance.vehicle, teal, heading)
        if (userMarker == null) userMarker = m.addMarker(MarkerOptions().position(point).icon(carIcon))
        else {
            userMarker!!.position = point
            userMarker!!.icon = carIcon
            m.updateMarker(userMarker!!)
        }
    }
    private fun smoothBearing(current: Double, desired: Double): Double {
        val delta = ((desired - current + 540.0) % 360.0) - 180.0
        return (current + delta * 0.45 + 360.0) % 360.0
    }
    private fun setDrivingMode(active: Boolean) {
        navigationalMode = active
        follow = if (active) true else appearance.follow
        if (appearance.keepScreen && active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (::bottomPanel.isInitialized) bottomPanel.visibility = if (active) View.GONE else View.VISIBLE
        if (::drivePanel.isInitialized) drivePanel.visibility = if (active) View.VISIBLE else View.GONE
        if (active) {
            updateDriveMessage(lastMessage)
            current?.let { followCamera(it, true) }
        } else {
            map?.animateCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(map?.cameraPosition?.target ?: LatLng(32.615,44.027))
                    .zoom(15.4).bearing(0.0).tilt(0.0)
                    .padding(0.0,0.0,0.0,0.0).build()), 450)
        }
    }
    private fun updateDriveMessage(value: String) {
        if (!::driveTitle.isInitialized || !::driveDetail.isInitialized) return
        val parts = value.split("  •  ")
        driveTitle.text = parts.firstOrNull() ?: "تابع المسار"
        driveDetail.text = if (parts.size >= 2) parts.drop(1).joinToString("  •  ") else
            "الملاحة شغالة • انتبه إلى الطريق"
    }
    private fun applyAppearance(kind: String) {
        window.statusBarColor = ink
        window.navigationBarColor = ink
        voice.enabled = appearance.voice
        routeController.autoReroute = appearance.reroute
        routeController.setAccent(teal)
        if (kind == "vehicle" || kind == "color") {
            vehicleIcons.clear()
            current?.let { updateUserPin(it) }
        }
        if (kind == "camera") {
            follow = appearance.follow
            if (navigationalMode && appearance.follow) current?.let { followCamera(it,true) }
        }
        if (kind == "keep_screen") {
            if (navigationalMode && appearance.keepScreen) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (kind == "map" || kind == "color") {
            refreshOverlay()
            if (kind == "map") loadStyle()
        }
    }
    private fun refreshOverlay() {
        if (!::uiRoot.isInitialized) return
        if (uiRoot.childCount > 1) uiRoot.removeViews(1,uiRoot.childCount - 1)
        makeOverlay(uiRoot)
        routeInfo.text = lastMessage
        routeInfo.setOnClickListener { routeController.showChoicesOrCalculate() }
        setDrivingVisibility()
        current?.let {
            speed.text = (if (it.hasSpeed()) (it.speed*3.6).roundToInt() else 0).toString() + "\nكم/س"
        }
    }
    private fun setDrivingVisibility() {
        if (!::bottomPanel.isInitialized || !::drivePanel.isInitialized) return
        bottomPanel.visibility = if (navigationalMode) View.GONE else View.VISIBLE
        drivePanel.visibility = if (navigationalMode) View.VISIBLE else View.GONE
        if (navigationalMode) updateDriveMessage(lastMessage)
    }
    private fun followCamera(loc: Location, immediate: Boolean = false) {
        val m = map ?: return
        val latlng = routeController.snappedPosition(loc) ?: LatLng(loc.latitude,loc.longitude)
        val active = navigationalMode
        val headingUp = active && appearance.perspective
        val targetBearing = if (headingUp) lastHeading else 0.0
        val camera = CameraPosition.Builder()
            .target(latlng)
            .zoom(if (active) 17.25 else m.cameraPosition.zoom.coerceAtLeast(15.5))
            .bearing(targetBearing)
            .tilt(if (headingUp) 49.0 else 0.0)
            .padding(
                0.0,
                if (active) dp(112).toDouble() else 0.0,
                0.0,
                if (active) dp(205).toDouble() else 0.0
            ).build()
        m.animateCamera(CameraUpdateFactory.newCameraPosition(camera), if (immediate) 580 else 800)
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
        routeController.onLocationChanged(loc)
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
        val opts = arrayOf("وضع ليلي / نهاري", "تحديد موقعي", "تغيير الطريق", "إعادة التعليمات الصوتية", "اختبار الصوت العراقي", "تشغيل / كتم الصوت", "تجنب الشوارع الترابية", "حذف الوجهة")
        AlertDialog.Builder(this).setTitle("دَرْبَك").setItems(opts) { _, index ->
            when (index) {
                0 -> { night = !night; loadStyle() }
                1 -> recenter()
                2 -> routeController.showChoicesOrCalculate()
                3 -> routeController.repeatVoice()
                4 -> voice.test()
                5 -> {
                    voice.enabled = !voice.enabled
                    Toast.makeText(this, if (voice.enabled) "الصوت شغّال" else "الصوت مكتوم", Toast.LENGTH_SHORT).show()
                    if (voice.enabled) voice.test()
                }
                6 -> routeController.toggleAvoidUnpaved()
                7 -> {
                    target = null
                    targetMarker?.let { map?.removeMarker(it) }
                    targetMarker = null
                    routeController.clear()
                    routeInfo.setTextColor(muted)
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
        routeInfo.text = "جاري البحث عن الأماكن…"
        executor.execute {
            try {
                val geo = current?.let { LatLng(it.latitude, it.longitude) } ?: LatLng(32.615, 44.027)
                val url = "https://photon.komoot.io/api/?limit=7&lat=" + geo.latitude +
                    "&lon=" + geo.longitude + "&q=" + URLEncoder.encode(query, "UTF-8")
                val features = JSONObject(getUrl(url)).getJSONArray("features")
                val labels = ArrayList<String>()
                val coords = ArrayList<LatLng>()
                for (i in 0 until features.length()) {
                    val feature = features.getJSONObject(i)
                    val props = feature.getJSONObject("properties")
                    val country = props.optString("countrycode", "").lowercase(Locale.ROOT)
                    if (country.isNotBlank() && country != "iq") continue
                    val pair = feature.getJSONObject("geometry").getJSONArray("coordinates")
                    val place = LatLng(pair.getDouble(1), pair.getDouble(0))
                    val name = props.optString("name", query)
                    val city = props.optString("city", props.optString("state", "العراق"))
                    labels.add(name + " • " + city)
                    coords.add(place)
                }
                if (coords.isEmpty()) throw IllegalStateException("No Photon results")
                ui.post {
                    routeInfo.text = "اختار وجهتك من نتائج البحث"
                    AlertDialog.Builder(this)
                        .setTitle("نتائج البحث — دَرْبَك")
                        .setItems(labels.toTypedArray()) { _, index ->
                            val p = coords[index]
                            placeTarget(p)
                            map?.animateCamera(CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder().target(p).zoom(15.5).build()), 430)
                        }.setNegativeButton("رجوع", null).show()
                }
            } catch (_: Exception) {
                // Manual, single-shot fallback. Nominatim is NOT used as typeahead.
                try {
                    val url = "https://nominatim.openstreetmap.org/search?format=json&limit=1&countrycodes=iq&q=" +
                        URLEncoder.encode(query, "UTF-8")
                    val results = JSONArray(getUrl(url))
                    if (results.length() == 0) throw IllegalStateException("Place not found")
                    val item = results.getJSONObject(0)
                    val p = LatLng(item.getDouble("lat"), item.getDouble("lon"))
                    ui.post {
                        placeTarget(p)
                        map?.animateCamera(CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder().target(p).zoom(15.5).build()), 430)
                    }
                } catch (_: Exception) {
                    ui.post { routeInfo.text = "ما لكيت المكان، جرّب اسم أقرب أو حدده على الخريطة" }
                }
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
    override fun onDestroy() {
        locationManager.removeUpdates(this)
        executor.shutdownNow()
        routeController.release()
        voice.release()
        mapView.onDestroy()
        super.onDestroy()
    }
}