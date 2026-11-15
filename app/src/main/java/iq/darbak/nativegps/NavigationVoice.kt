package iq.darbak.nativegps

import android.app.Activity
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import org.maplibre.android.geometry.LatLng
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

data class DarbakTurn(
    val at: LatLng,
    val type: String,
    val modifier: String,
    val street: String,
    val meters: Double,
    var warned: Boolean = false
)
data class DarbakRoute(
    val points: List<LatLng>,
    val kilometers: Double,
    val seconds: Double,
    val turns: List<DarbakTurn>,
    val summary: String
) {
    val minutes: Int get() = (seconds / 60.0).roundToInt().coerceAtLeast(1)
}

class IraqiVoiceGuide(private val activity: Activity) : TextToSpeech.OnInitListener {
    private var engine: TextToSpeech? = null
    private var ready = false
    private var media: MediaPlayer? = null
    var enabled: Boolean = true

    init {
        try {
            engine = TextToSpeech(activity, this)
        } catch (_: Exception) {
            ready = false
        }
    }
    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            ready = false
            return
        }
        try {
            val tts = engine ?: return
            val iraqi = tts.setLanguage(Locale("ar", "IQ"))
            ready = iraqi != TextToSpeech.LANG_MISSING_DATA && iraqi != TextToSpeech.LANG_NOT_SUPPORTED
            if (!ready) {
                val arabic = tts.setLanguage(Locale("ar"))
                ready = arabic != TextToSpeech.LANG_MISSING_DATA &&
                    arabic != TextToSpeech.LANG_NOT_SUPPORTED
            }
            tts.setSpeechRate(0.94f)
            tts.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        } catch (_: Exception) {
            ready = false
        }
    }
    fun say(text: String, recorded: String? = null) {
        if (!enabled) return
        if (recorded != null && playRecorded(recorded)) return
        if (ready) {
            try {
                engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
            } catch (_: Exception) { }
        }
    }
    private fun playRecorded(name: String): Boolean {
        val id = when (name) {
            "left" -> R.raw.turn_left
            "right" -> R.raw.turn_right
            "arrive" -> R.raw.arrived
            else -> return false
        }
        return try {
            engine?.stop()
            media?.release()
            media = null
            val fd = activity.resources.openRawResourceFd(id) ?: return false
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            fd.close()
            mp.prepare()
            media = mp
            mp.setOnCompletionListener { finished ->
                if (media === finished) media = null
                finished.release()
            }
            mp.start()
            true
        } catch (_: Exception) {
            false
        }
    }
    fun announceTurn(turn: DarbakTurn) {
        val m = turn.modifier.lowercase(Locale.ROOT)
        val text: String
        val recorded: String?
        when {
            turn.type == "arrive" -> { text = "وصلت لوجهتك، الحمد لله عالسلامة"; recorded = "arrive" }
            "left" in m -> { text = "بعد شوية لف يسار"; recorded = "left" }
            "right" in m -> { text = "بعد شوية لف يمين"; recorded = "right" }
            "uturn" in m || "u-turn" in m -> { text = "استدر فقط عندما تسمح حركة المرور"; recorded = null }
            turn.type == "roundabout" || turn.type == "rotary" -> { text = "قدامك دوار، اتبع اتجاه الطريق"; recorded = null }
            else -> { text = "واصل على طول واتبع الطريق"; recorded = null }
        }
        say(text, recorded)
    }
    fun test() { say("بعد شوية لف يمين، وانتبه للطريق", "right") }
    fun release() {
        try { media?.release() } catch (_: Exception) {}
        media = null
        try { engine?.stop(); engine?.shutdown() } catch (_: Exception) {}
        engine = null
    }
}
