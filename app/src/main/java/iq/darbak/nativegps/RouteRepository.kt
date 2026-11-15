package iq.darbak.nativegps

import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Demonstration routing backends; replace public demo hosts before public launch. */
internal class RouteRepository {
    private fun json(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 9000
        c.readTimeout = 14000
        c.setRequestProperty("User-Agent", "DarbakNative/2.0 (Iraqi navigation prototype)")
        c.setRequestProperty("X-Client-Id", "darbak-native-prototype")
        c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP " + c.responseCode)
            return JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally { c.disconnect() }
    }

    fun routes(from: LatLng, dest: LatLng, avoidUnpaved: Boolean): List<DarbakRoute> {
        val request = JSONObject()
            .put("locations", JSONArray()
                .put(JSONObject().put("lat", from.latitude).put("lon", from.longitude))
                .put(JSONObject().put("lat", dest.latitude).put("lon", dest.longitude)))
            .put("costing", "auto")
            .put("format", "osrm")
            .put("shape_format", "geojson")
            .put("alternates", 2)
            .put("directions_type", "instructions")
            .put("costing_options", JSONObject().put("auto",
                JSONObject().put("exclude_unpaved", avoidUnpaved)))
        val valhallaUrl = "https://valhalla1.openstreetmap.de/route?json=" +
            URLEncoder.encode(request.toString(), "UTF-8")
        try {
            val candidates = parseOsrm(json(valhallaUrl), "Valhalla")
            if (candidates.isNotEmpty()) return candidates
        } catch (_: Exception) { }
        // This fallback cannot honor the unpaved-road preference.
        val url = "https://router.project-osrm.org/route/v1/driving/" +
            from.longitude + "," + from.latitude + ";" + dest.longitude + "," + dest.latitude +
            "?alternatives=3&steps=true&overview=full&geometries=geojson"
        val provider = if (avoidUnpaved) "OSRM (تجنب الترابي غير مضمون)" else "OSRM"
        val candidates = parseOsrm(json(url), provider)
        if (candidates.isEmpty()) throw IllegalStateException("No valid driving routes")
        return candidates
    }

    private fun parseOsrm(obj: JSONObject, provider: String): List<DarbakRoute> {
        if (obj.optString("code") != "Ok") throw IllegalStateException("Routing error")
        val a = obj.optJSONArray("routes") ?: throw IllegalStateException("Missing routes")
        val result = ArrayList<DarbakRoute>()
        for (i in 0 until minOf(a.length(), 3)) {
            val item = a.getJSONObject(i)
            val pointsJson = item.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val points = ArrayList<LatLng>(pointsJson.length())
            for (j in 0 until pointsJson.length()) {
                val p = pointsJson.getJSONArray(j)
                points.add(LatLng(p.getDouble(1), p.getDouble(0)))
            }
            if (points.size < 2) continue
            val turns = ArrayList<DarbakTurn>()
            val legs = item.optJSONArray("legs")
            if (legs != null) for (k in 0 until legs.length()) {
                val steps = legs.getJSONObject(k).optJSONArray("steps") ?: continue
                for (j in 0 until steps.length()) {
                    val step = steps.getJSONObject(j)
                    val man = step.optJSONObject("maneuver") ?: continue
                    val type = man.optString("type", "continue")
                    if (type == "depart") continue
                    val coord = man.optJSONArray("location") ?: continue
                    turns.add(DarbakTurn(
                        at = LatLng(coord.getDouble(1), coord.getDouble(0)),
                        type = type,
                        modifier = man.optString("modifier", ""),
                        street = step.optString("name", ""),
                        meters = step.optDouble("distance", 0.0)
                    ))
                }
            }
            result.add(DarbakRoute(
                points = points,
                kilometers = item.getDouble("distance") / 1000.0,
                seconds = item.getDouble("duration"),
                turns = turns,
                summary = provider
            ))
        }
        return result.sortedBy { it.seconds }
    }
}
