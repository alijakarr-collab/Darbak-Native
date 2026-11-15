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
    private val repository = RouteRepository()
    private var segmentIndex = 0
    private var progressMeters = 0.0
    private var cumulative = doubleArrayOf()
    private var turnDistance = doubleArrayOf()
    private var offRouteSamples = 0
    private var lastRerouteMs = 0L
    private var lastInfoMs = 0L
    private val warnedFar = HashSet<Int>()
    private val warnedNear = HashSet<Int>()
    var avoidUnpaved = false
        private set
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
        if (destination != null && routes.isEmpty() && !calculating) calculate()
        if (navigating) checkTurn(loc)
    }
    fun toggleAvoidUnpaved() {
        avoidUnpaved = !avoidUnpaved
        Toast.makeText(activity, if (avoidUnpaved) "تفضيل تجنب الشوارع الترابية مفعّل" else "التفضيل معطّل", Toast.LENGTH_LONG).show()
        if (destination != null && location != null) calculate(navigating)
    }
    fun repeatVoice() {
        val route = routes.getOrNull(chosenIndex)
        if (route != null && cursor in route.turns.indices) {
            val remaining = (turnDistance[cursor] - progressMeters).coerceAtLeast(0.0).toInt()
            voice.say("بعد " + remaining + " متر " + turnLabel(route.turns[cursor]))
        } else voice.test()
    }
    private fun turnLabel(turn: DarbakTurn): String {
        val mod = turn.modifier.lowercase(Locale.ROOT)
        return when {
            turn.type == "arrive" -> "توصل لوجهتك"
            mod.contains("left") -> "لف يسار"
            mod.contains("right") -> "لف يمين"
            mod.contains("uturn") || mod.contains("u-turn") -> "استدر إذا الطريق يسمح"
            turn.type == "roundabout" || turn.type == "rotary" -> "دوار قدامك"
            else -> "واصل على نفس الطريق"
        }
    }
    fun selectDestination(dest: LatLng) {
        destination = dest
        requestVersion++
        calculating = false
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
        calculating = false
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
    fun calculate(resumeAfter: Boolean = false) {
        val from = location
        val dest = destination
        if (from == null || dest == null) {
            Toast.makeText(activity, "حدد موقعك والوجهة أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        if (calculating) return
        calculating = true
        val version = ++requestVersion
        val preference = avoidUnpaved
        showMessage("جاري إيجاد أفضل المسارات والبدائل…")
        val origin = LatLng(from.latitude, from.longitude)
        executor.execute {
            try {
                val candidates = repository.routes(origin, dest, preference)
                handler.post {
                    if (version != requestVersion) return@post
                    calculating = false
                    routes = candidates
                    chosenIndex = 0
                    renderRoutes()
                    if (resumeAfter) {
                        begin()
                        voice.say("طلعت لك مسار جديد. تابع الطريق")
                    } else chooseRoute()
                }
            } catch (_: Exception) {
                handler.post {
                    if (version != requestVersion) return@post
                    calculating = false
                    showMessage("تعذّر حساب الطريق، راجع الإنترنت وجرّب من جديد")
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
        segmentIndex = 0
        progressMeters = 0.0
        offRouteSamples = 0
        warnedFar.clear(); warnedNear.clear()
        val distances = DoubleArray(selected.points.size)
        for (i in 1 until selected.points.size) {
            val a = selected.points[i-1]; val b = selected.points[i]
            val out = FloatArray(1)
            Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, out)
            distances[i] = distances[i-1] + out[0]
        }
        cumulative = distances
        turnDistance = DoubleArray(selected.turns.size) { i ->
            val p = selected.turns[i].at
            val cosLat = kotlin.math.cos(Math.toRadians(p.latitude))
            var bestIndex = 0
            var best = Double.MAX_VALUE
            for (k in selected.points.indices) {
                val q = selected.points[k]
                val dx = (q.longitude-p.longitude)*111320*cosLat
                val dy = (q.latitude-p.latitude)*111320
                val d = dx*dx+dy*dy
                if (d < best) { best=d; bestIndex=k }
            }
            distances[bestIndex]
        }
        val km = String.format(Locale.US, "%.1f", selected.kilometers)
        showMessage("المسار المختار: " + km + " كم • " + selected.minutes + " دقيقة. اضغط لتغييره")
        voice.say("تم اختيار الطريق. يلا على بركة الله، تابع التعليمات")
        renderRoutes()
    }
    private data class Match(val segment: Int, val along: Double, val error: Double, val snapped: LatLng)
    private fun locate(loc: Location, points: List<LatLng>): Match {
        if (points.size < 2) return Match(0, 0.0, Double.MAX_VALUE, LatLng(loc.latitude, loc.longitude))
        val start = (segmentIndex - 12).coerceAtLeast(0)
        val end = (segmentIndex + 550).coerceAtMost(points.size - 2)
        val longitudeScale = 111320.0 * kotlin.math.cos(Math.toRadians(loc.latitude))
        var best = Double.MAX_VALUE
        var bestSegment = start
        var bestFraction = 0.0
        var matched = points[start]
        for (i in start..end) {
            val a = points[i]; val b = points[i + 1]
            val x = (a.longitude - loc.longitude) * longitudeScale
            val y = (a.latitude - loc.latitude) * 111320.0
            val dx = (b.longitude - a.longitude) * longitudeScale
            val dy = (b.latitude - a.latitude) * 111320.0
            val len2 = dx * dx + dy * dy
            val fraction = if (len2 > 0.00001) ((-x * dx - y * dy) / len2).coerceIn(0.0, 1.0) else 0.0
            val px = x + fraction * dx
            val py = y + fraction * dy
            val square = px * px + py * py
            if (square < best) {
                best = square
                bestSegment = i
                bestFraction = fraction
                matched = LatLng(a.latitude + (b.latitude - a.latitude) * fraction,
                    a.longitude + (b.longitude - a.longitude) * fraction)
            }
        }
        val meters = if (bestSegment + 1 < cumulative.size)
            cumulative[bestSegment + 1] - cumulative[bestSegment] else 0.0
        val along = (cumulative.getOrNull(bestSegment) ?: 0.0) + meters * bestFraction
        return Match(bestSegment, along, kotlin.math.sqrt(best), matched)
    }
    fun snappedPosition(loc: Location): LatLng? {
        if (!navigating || chosenIndex !in routes.indices) return null
        if (loc.hasAccuracy() && loc.accuracy > 55f) return null
        val match = locate(loc, routes[chosenIndex].points)
        return if (match.error < 32.0) match.snapped else null
    }
    private fun checkTurn(loc: Location) {
        if (chosenIndex !in routes.indices) return
        if (loc.hasAccuracy() && loc.accuracy > 70f) return
        val route = routes[chosenIndex]
        val match = locate(loc, route.points)
        val threshold = maxOf(65.0, (if (loc.hasAccuracy()) loc.accuracy.toDouble() else 25.0) * 2.4)
        if (match.error > threshold && (!loc.hasSpeed() || loc.speed >= 1.5f)) {
            offRouteSamples++
            if (offRouteSamples >= 4 && !calculating && System.currentTimeMillis() - lastRerouteMs > 22000) {
                lastRerouteMs = System.currentTimeMillis()
                offRouteSamples = 0
                voice.say("طلعت عن الطريق، جاي أحسب مسار جديد")
                showMessage("⟳ جاري إعادة حساب الطريق بعد الانحراف…")
                calculate(true)
            }
            return
        }
        offRouteSamples = 0
        segmentIndex = maxOf(segmentIndex, match.segment)
        progressMeters = maxOf(progressMeters, match.along)
        val turns = route.turns
        if (cursor < turns.size) {
            val turn = turns[cursor]
            val remaining = (turnDistance[cursor] - progressMeters).coerceAtLeast(0.0)
            if (remaining <= 45 && !turn.warned) {
                turn.warned = true
                voice.announceTurn(turn)
                warnedFar.add(cursor); warnedNear.add(cursor)
            } else if (remaining <= 135 && warnedNear.add(cursor) && !turn.warned) {
                warnedFar.add(cursor)
                voice.say("بعد مية متر " + turnLabel(turn))
            } else if (remaining <= 500 && warnedFar.add(cursor) && !turn.warned) {
                voice.say("بعد خمس مية متر " + turnLabel(turn))
            }
            if (progressMeters > turnDistance[cursor] + 30 ||
                (turn.type == "arrive" && remaining <= 28)) {
                cursor++
                if (turn.type == "arrive") {
                    navigating = false
                    showMessage("وصلت لوجهتك، الحمد لله عالسلامة")
                    if (!turn.warned) voice.announceTurn(turn)
                    return
                }
            }
        }
        if (System.currentTimeMillis() - lastInfoMs > 3500L) {
            lastInfoMs = System.currentTimeMillis()
            val total = cumulative.lastOrNull()?.coerceAtLeast(1.0) ?: 1.0
            val left = (total - progressMeters).coerceAtLeast(0.0)
            val time = (route.seconds * left / total / 60).roundToInt().coerceAtLeast(1)
            val next = if (cursor < turns.size)
                turnLabel(turns[cursor]) + " بعد " + (turnDistance[cursor] - progressMeters).coerceAtLeast(0.0).roundToInt() + " م"
                else "تابع إلى وجهتك"
            showMessage(next + "  •  " + String.format(Locale.US, "%.1f", left / 1000.0) + " كم  •  " + time + " د")
        }
    }
    fun release() { requestVersion++; executor.shutdownNow() }
}
