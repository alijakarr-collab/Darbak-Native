package iq.darbak.nativegps

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
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
import android.widget.ImageView
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
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import com.mapbox.geojson.Feature
import com.mapbox.geojson.Point
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
    private var userSource: GeoJsonSource? = null
    private var userLayer: SymbolLayer? = null
    private val vehicleSpriteId = "darbak-live-vehicle-v4"
    private val vehicleSourceId = "darbak-live-location-v4"
    private val vehicleLayerId = "darbak-live-symbol-v4"
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
    private fun imageButton(drawableId: Int, description: String, action: () -> Unit): ImageView {
        return ImageView(this).apply {
            setImageResource(drawableId)
            imageTintList = ColorStateList.valueOf(foreground)
            contentDescription = description
            setPadding(dp(13), dp(13), dp(13), dp(13))
            background = box(panel, 14)
            setOnClickListener { action() }
        }
    }
    private fun updateGaugeStyle() {
        if (!::speed.isInitialized) return
        speed.visibility = if (appearance.speedVisible) View.VISIBLE else View.GONE
        speed.textSize = if (appearance.speedStyle == 1) 23f else 20f
        speed.background = box(ink, if (appearance.speedStyle == 1) 50 else 21)
        speed.setTextColor(if (appearance.speedStyle == 1) teal else foreground)
        speed.contentDescription = "عداد سرعة GPS، كم في الساعة"
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
        val brand = txt("دَرْبَك 4.0", 20f, true)
        status = txt("GPS قيد التحديد", 12f, false, muted)
        head.addView(button("☰") { menu() }, LinearLayout.LayoutParams(dp(49), dp(49)))
        head.addView(imageButton(R.drawable.ic_darbak_settings, "الإعدادات والثيمات") {
            appearance.show(::applyAppearance)
        }, LinearLayout.LayoutParams(dp(49), dp(49)).apply {
            leftMargin = dp(5)
        })
        head.addView(status, LinearLayout.LayoutParams(0, dp(49), 1f))
        head.addView(brand, LinearLayout.LayoutParams(dp(123), dp(49)))
        add(root, head, Gravity.TOP or Gravity.CENTER_HORIZONTAL, -1, 61, 12, 12)

        val tools = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun tool(text: String, task: () -> Unit) {
            tools.addView(button(text, task), LinearLayout.LayoutParams(dp(50), dp(49)))
            tools.addView(View(this), LinearLayout.LayoutParams(dp(1), dp(9)))
        }
        tool("◎") { recenter() }
        tool("+") { map?.animateCamera(CameraUpdateFactory.zoomIn(), 230) }
        tool("−") { map?.animateCamera(CameraUpdateFactory.zoomOut(), 230) }
        tools.addView(imageButton(R.drawable.ic_darbak_gauge,"إعدادات العدادات") {
            appearance.show(::applyAppearance,2)
        },LinearLayout.LayoutParams(dp(50),dp(49)))
        tools.addView(View(this),LinearLayout.LayoutParams(dp(1),dp(9)))
        tool("🛣") { routeController.showChoicesOrCalculate() }
        tools.addView(imageButton(R.drawable.ic_darbak_volume,"إعادة تعليمات الملاحة") {
            routeController.repeatVoice()
        },LinearLayout.LayoutParams(dp(50),dp(49)))
        add(root, tools, Gravity.END or Gravity.CENTER_VERTICAL, 50, -2, 13)

        speed = txt("0\nكم/س", 21f, true).apply { background = box(ink, 22) }
        updateGaugeStyle()
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
        bottomPanel = bottom
        add(root, bottomPanel, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, -1, -2, 0)

        // Turn-by-turn navigation HUD, visible ONLY after the user starts a trip.
        drivePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box(ink, 23)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val bannerHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val turnEmblem = txt("➤", 32f, true, teal).apply { background = box(panel, 16) }
        bannerHeader.addView(turnEmblem, LinearLayout.LayoutParams(dp(56), dp(61)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10),0,0,0)
        }
        driveTitle = txt("اتبع المسار المختار", 19f, true).apply {
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            maxLines = 2
        }
        driveDetail = txt("GPS فعّال • انتبه للطريق", 12f, false, muted).apply {
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            maxLines = 2
        }
        texts.addView(driveTitle,LinearLayout.LayoutParams(-1,dp(39)))
        texts.addView(driveDetail,LinearLayout.LayoutParams(-1,dp(26)))
        bannerHeader.addView(texts,LinearLayout.LayoutParams(0,dp(65),1f))
        drivePanel.addView(bannerHeader,LinearLayout.LayoutParams(-1,dp(65)))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun action(label: String, click: () -> Unit) {
            val b = button(label,click).apply { textSize = 14f }
            actions.addView(b,LinearLayout.LayoutParams(0,dp(42),1f).apply {
                setMargins(dp(2),dp(4),dp(2),0)
            })
        }
        action("⏹ إنهاء") { routeController.endNavigation() }
        action("🔊 كرر") { routeController.repeatVoice() }
        action("⚙ الإعدادات") { appearance.show(::applyAppearance) }
        drivePanel.addView(actions,LinearLayout.LayoutParams(-1,dp(48)))
        drivePanel.visibility = View.GONE
        add(root,drivePanel,Gravity.TOP or Gravity.CENTER_HORIZONTAL,-1,-2,12,86)
        setDrivingVisibility()
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
            userSource = null
            userLayer = null
            setupVehicleLayer(m)
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
    // MapLibre annotation Markers are deprecated and can fail to refresh when changing
    // custom icons. Use a native style SymbolLayer with an updating GeoJSON source instead.
    private fun setupVehicleLayer(m: MapLibreMap) {
        val style = m.style ?: return
        val initial = current?.let { routeController.snappedPosition(it) ?: LatLng(it.latitude,it.longitude) }
            ?: LatLng(32.615,44.027)
        try {
            style.addImage(vehicleSpriteId, vehicleIcons.bitmap(appearance.vehicle,teal))
            val source = GeoJsonSource(vehicleSourceId,
                Feature.fromGeometry(Point.fromLngLat(initial.longitude,initial.latitude)))
            style.addSource(source)
            val layer = SymbolLayer(vehicleLayerId,vehicleSourceId).withProperties(
                PropertyFactory.iconImage(vehicleSpriteId),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconSize(0.86f),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
                PropertyFactory.iconRotate(0f)
            )
            style.addLayer(layer)
            userSource = source
            userLayer = layer
            // Without GPS permission, do not show a false blue dot at an example location.
            if (current == null) layer.setProperties(PropertyFactory.visibility(Property.NONE))
        } catch (e: Exception) {
            android.util.Log.e("DarbakGPS","Vehicle style icon setup failed",e)
        }
    }
    private fun refreshVehicleSymbol() {
        val style = map?.style ?: return
        try {
            style.addImage(vehicleSpriteId,vehicleIcons.bitmap(appearance.vehicle,teal))
            userLayer?.setProperties(PropertyFactory.iconImage(vehicleSpriteId))
            current?.let { updateUserPin(it) }
        } catch (e: Exception) {
            android.util.Log.e("DarbakGPS","Failed to change car / motorcycle icon",e)
        }
    }
    private fun updateUserPin(loc: Location) {
        val m = map ?: return
        if (userSource == null || userLayer == null) setupVehicleLayer(m)
        val point = routeController.snappedPosition(loc) ?: LatLng(loc.latitude,loc.longitude)
        val feature = Feature.fromGeometry(Point.fromLngLat(point.longitude,point.latitude))
        userSource?.setGeoJson(feature)
        userLayer?.setProperties(
            PropertyFactory.visibility(Property.VISIBLE),
            PropertyFactory.iconRotate(lastHeading.toFloat())
        )
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
            refreshVehicleSymbol()
        }
        if (kind == "camera") {
            follow = appearance.follow
            if (navigationalMode && appearance.follow) current?.let { followCamera(it,true) }
        }
        if (kind == "keep_screen") {
            if (navigationalMode && appearance.keepScreen) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (kind == "speed" || kind == "speed_style") updateGaugeStyle()
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
        status.text = current?.let { "● GPS ±"+it.accuracy.roundToInt()+"م" } ?: "GPS قيد التحديد"
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
        if (loc.hasAccuracy() && loc.accuracy > (if (navigationalMode) 100f else 160f)) return
        val previous = current
        if (previous != null && loc.time + 1000L < previous.time) return
        current = loc
        status.text = if (loc.hasAccuracy()) "● GPS ±" + loc.accuracy.roundToInt() + "م" else "● GPS متصل"
        val kph = (if (loc.hasSpeed()) loc.speed * 3.6f else 0f).roundToInt().coerceAtLeast(0)
        speed.text = kph.toString() + "\nكم/س"
        if (loc.hasBearing() && (!loc.hasSpeed() || loc.speed > 1.8f)) {
            lastHeading = smoothBearing(lastHeading, loc.bearing.toDouble())
        } else if (previous != null && previous.distanceTo(loc) > 15f) {
            lastHeading = smoothBearing(lastHeading, previous.bearingTo(loc).toDouble())
        }
        routeController.onLocationChanged(loc)
        updateUserPin(loc)
        if (follow && System.currentTimeMillis() - lastCameraAt > 1100L) {
            lastCameraAt = System.currentTimeMillis()
            followCamera(loc)
        }
    }
    private fun recenter() {
        follow = true
        val loc = current ?: run {
            Toast.makeText(this, "بانتظار GPS… شغّل الموقع الدقيق", Toast.LENGTH_SHORT).show()
            if (hasPermission()) startGps()
            return
        }
        followCamera(loc,true)
    }
    private fun menu() {
        val options = arrayOf(
            "⚙ الإعدادات والثيمات",
            "◉ لوحة العدادات",
            "🧭 تتبّع مركبتي",
            "🛣 اختيار مسار الرحلة",
            "🔊 إعادة التعليمات الصوتية",
            "🎙 اختبار الصوت العراقي",
            "🔇 تشغيل / كتم الصوت",
            "🚧 تفضيل تجنب الطرق الترابية",
            "⏹ إنهاء القيادة",
            "🗑 مسح الوجهة"
        )
        AlertDialog.Builder(this).setTitle("دَرْبَك 3.0 — القائمة")
            .setItems(options) { _, index ->
                when (index) {
                    0 -> appearance.show(::applyAppearance)
                    1 -> appearance.show(::applyAppearance,2)
                    2 -> recenter()
                    3 -> routeController.showChoicesOrCalculate()
                    4 -> routeController.repeatVoice()
                    5 -> voice.test()
                    6 -> {
                        appearance.setVoice(!appearance.voice)
                        applyAppearance("voice")
                        Toast.makeText(this, if (voice.enabled) "الصوت شغال" else "الصوت مكتوم", Toast.LENGTH_SHORT).show()
                    }
                    6 -> routeController.toggleAvoidUnpaved()
                    7 -> routeController.endNavigation()
                    8 -> {
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