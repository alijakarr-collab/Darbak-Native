package iq.darbak.nativegps

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

@Suppress("DEPRECATION")
class RouteController(
    private val activity: Activity,
    private val voice: IraqiVoiceGuide,
    private val showMessage: (String) -> Unit
) {
    private val teal = Color.rgb(17, 204, 169)
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val outlines = ArrayList<Polyline>()
    private var map: MapLibreMap? = null
    private var location: Location? = null
    private var destination: LatLng? = null
    private var routes: List<DarbakRoute> = emptyList()
    private var chosenIndex = -1
    private var cursor = 0
    private var navigating = false
    private var requestVersion = 0
    private var calculating = false

    fun onMapStyleLoaded(mapLibre: MapLibreMap) {
        map = mapLibre
        outlines.clear() // MapLibre cleared the old annotations on setStyle
        renderRoutes()
    }
    fun onLocationChanged(loc: Location) {
        location = loc
        if (navigating) checkTurn(loc)
    }
    fun selectDestination(dest: LatLng) {
        destination = dest
        navigating = false
        routes = emptyList()
        chosenIndex = -1
        for (p in outlines) map?.removePolyline(p)
        outlines.clear()
        if (location == null) {
            showMessage("حدد موقعك أولاً، وبعدها اختار الطريق")
        } else calculate()
    }
    fun clear() {
        requestVersion++
        destination = null
        navigating = false
        routes = emptyList()
        chosenIndex = -1
        for (p in outlines) map?.removePolyline(p)
        outlines.clear()
        showMessage("اضغط على الخريطة لاختيار وجهتك")
    }
    fun showChoicesOrCalculate() {
        if (routes.isEmpty()) calculate() else chooseRoute()
    }
    fun calculate() {
        val from = location
        val dest = destination
        if (from == null || dest == null) {
            Toast.makeText(activity, "حدد الموقع والوجهة أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        if (calculating) return
        calculating = true
        val version = ++requestVersion
        showMessage("جاري البحث عن أسرع طريق والطرق البديلة…")
        executor.execute {
            try {
                val url = "https://router.project-osrm.org/route/v1/driving/" +
                    from.longitude + "," + from.latitude + ";" +
                    dest.longitude + "," + dest.latitude +
                    "?alternatives=2&steps=true&overview=full&geometries=geojson"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 12000
                conn.setRequestProperty("User-Agent", "DarbakNative/1.2 (Android route demo)")
                try {
                    if (conn.responseCode !in 200..299) throw Exception("HTTP " + conn.responseCode)
                    val response = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    if (response.optString("code") != "Ok") throw Exception("No route")
                    val candidates = ArrayList<DarbakRoute>()
                    val arr = response.getJSONArray("routes")
                    for (i in 0 until minOf(arr.length(), 3)) {
                        val item = arr.getJSONObject(i)
                        val pointsJson = item.getJSONObject("geometry").getJSONArray("coordinates")
                        val points = ArrayList<LatLng>()
                        for (j in 0 until pointsJson.length()) {
                            val pair = pointsJson.getJSONArray(j)
                            points.add(LatLng(pair.getDouble(1), pair.getDouble(0)))
                        }
                        if (points.size < 2) continue
                        val turns = ArrayList<DarbakTurn>()
                        val legs = item.optJSONArray("legs")
                        if (legs != null) for (l in 0 until legs.length()) {
                            val leg = legs.getJSONObject(l)
                            val steps = leg.optJSONArray("steps") ?: continue
                            for (j in 0 until steps.length()) {
                                val step = steps.getJSONObject(j)
                                val maneuver = step.optJSONObject("maneuver") ?: continue
                                val type = maneuver.optString("type", "continue")
                                if (type == "depart") continue
                                val position = maneuver.optJSONArray("location") ?: continue
                                turns.add(DarbakTurn(
                                    LatLng(position.getDouble(1), position.getDouble(0)),
                                    type,
                                    maneuver.optString("modifier", ""),
                                    step.optString("name", ""),
                                    step.optDouble("distance", 0.0)
                                ))
                            }
                        }
                        candidates.add(DarbakRoute(
                            points,
                            item.getDouble("distance") / 1000.0,
                            item.getDouble("duration"),
                            turns,
                            ""
                        ))
                    }
                    if (candidates.isEmpty()) throw Exception("No routes")
                    handler.post {
                        calculating = false
                        if (version != requestVersion) return@post
                        routes = candidates.sortedBy { it.seconds }
                        chosenIndex = 0
                        renderRoutes()
                        chooseRoute()
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                handler.post {
                    calculating = false
                    if (version == requestVersion) showMessage("تعذّر حساب الطريق. جرّب ثانية من القائمة.")
                }
            }
        }
    }
    private fun renderRoutes() {
        val m = map ?: return
        for (p in outlines) m.removePolyline(p)
        outlines.clear()
        for (i in routes.indices) {
            if (i == chosenIndex) continue
            outlines.add(m.addPolyline(PolylineOptions().addAll(routes[i].points)
                .width(5f).color(Color.argb(210, 170, 175, 192))))
        }
        if (chosenIndex in routes.indices) {
            outlines.add(m.addPolyline(PolylineOptions().addAll(routes[chosenIndex].points)
                .width(9f).color(teal)))
        }
    }
    private fun chooseRoute() {
        if (routes.isEmpty()) { calculate(); return }
        val shortestIndex = routes.indices.minByOrNull { routes[it].kilometers } ?: 0
        val labels = routes.mapIndexed { i, route ->
            val label = when {
                i == 0 -> "⚡ الأسرع"
                i == shortestIndex -> "📍 الأقصر"
                else -> "🛣 بديل " + (i + 1)
            }
            val km = String.format(Locale.US, "%.1f", route.kilometers)
            label + " — " + km + " كم • " + route.minutes + " دقيقة"
        }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("اختار الطريق اللي يناسبك")
            .setSingleChoiceItems(labels, chosenIndex.coerceIn(0, routes.lastIndex)) { _, i ->
                chosenIndex = i
                renderRoutes()
            }
            .setPositiveButton("ابدأ الملاحة") { _, _ -> begin() }
            .setNegativeButton("رجوع", null)
            .show()
    }
    private fun begin() {
        if (chosenIndex !in routes.indices) return
        navigating = true
        cursor = 0
        for (t in routes[chosenIndex].turns) t.warned = false
        val selected = routes[chosenIndex]
        val km = String.format(Locale.US, "%.1f", selected.kilometers)
        showMessage("المسار المختار: " + km + " كم • " + selected.minutes + " دقيقة. اضغط لتغييره")
        voice.say("تم اختيار الطريق. يلا على بركة الله، تابع التعليمات")
        renderRoutes()
    }
    private fun checkTurn(loc: Location) {
        if (chosenIndex !in routes.indices) return
        val turns = routes[chosenIndex].turns
        if (cursor >= turns.size) return
        val upcoming = turns[cursor]
        val distance = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, upcoming.at.latitude, upcoming.at.longitude, distance)
        if (!upcoming.warned && distance[0] <= 160f) {
            upcoming.warned = true
            voice.announceTurn(upcoming)
            val label = when {
                upcoming.type == "arrive" -> "قربت من الوجهة"
                upcoming.modifier.contains("left") -> "اللفة الجاية يسار"
                upcoming.modifier.contains("right") -> "اللفة الجاية يمين"
                else -> "تابع الطريق المحدد"
            }
            showMessage(label)
        }
        if (distance[0] < 35f) {
            cursor++
            if (upcoming.type == "arrive") {
                navigating = false
                showMessage("وصلت لوجهتك")
            }
        }
    }
    fun release() { requestVersion++; executor.shutdownNow() }
}
