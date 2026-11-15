package iq.darbak.nativegps

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

data class DarbakTheme(
    val name: String,
    val detail: String,
    val style: String,
    val dark: Boolean,
    val panel: Int,
    val panelSecondary: Int,
    val label: Int
)

data class DarbakAccent(val name: String, val color: Int)

/**
 * Fully native appearance and navigation preferences, saved on-device.
 * Five actual MapLibre/OpenFreeMap vector map styles and five independent accents.
 */
class DarbakAppearance(private val activity: Activity) {
    private val preferences = activity.getSharedPreferences("darbak_v3_settings", Context.MODE_PRIVATE)
    val themes = listOf(
        DarbakTheme("منتصف الليل", "داكن وراقي", "dark", true, 0xFF131B2A.toInt(), 0xFF202B40.toInt(), Color.WHITE),
        DarbakTheme("نهاري مشرق", "شوارع وأماكن واضحة", "bright", false, 0xFFF5F7FA.toInt(), 0xFFFFFFFF.toInt(), 0xFF172033.toInt()),
        DarbakTheme("كلاسيكي", "خريطة مدينة متوازنة", "liberty", false, 0xFFEEEFF2.toInt(), 0xFFFFFFFF.toInt(), 0xFF182C37.toInt()),
        DarbakTheme("مينيمل", "هادئ للتركيز", "positron", false, 0xFFF6F6F6.toInt(), 0xFFFFFFFF.toInt(), 0xFF21242C.toInt()),
        DarbakTheme("المحيط", "درجات أزرق داكن", "fiord", true, 0xFF132333.toInt(), 0xFF213B4E.toInt(), Color.WHITE)
    )
    val accents = listOf(
        DarbakAccent("فيروزي", 0xFF21DEB4.toInt()),
        DarbakAccent("أزرق", 0xFF4A91FF.toInt()),
        DarbakAccent("بنفسجي", 0xFF9F79FF.toInt()),
        DarbakAccent("ذهبي", 0xFFFFC45A.toInt()),
        DarbakAccent("مرجاني", 0xFFFF6F86.toInt())
    )
    val themeIndex: Int get() = preferences.getInt("theme", 0).coerceIn(0, themes.lastIndex)
    val accentIndex: Int get() = preferences.getInt("accent", 0).coerceIn(0, accents.lastIndex)
    val vehicle: Int get() = preferences.getInt("vehicle", 0).coerceIn(0, 2)
    val follow: Boolean get() = preferences.getBoolean("follow", true)
    val perspective: Boolean get() = preferences.getBoolean("perspective", true)
    val voice: Boolean get() = preferences.getBoolean("voice", true)
    val reroute: Boolean get() = preferences.getBoolean("reroute", true)
    val keepScreen: Boolean get() = preferences.getBoolean("keep_screen", true)
    val theme: DarbakTheme get() = themes[themeIndex]
    val accent: DarbakAccent get() = accents[accentIndex]
    val mapStyle: String get() = "https://tiles.openfreemap.org/styles/" + theme.style
    val vehicleLabel: String get() = listOf("سهم الملاحة", "سيارة", "دراجة نارية")[vehicle]

    private fun storeInt(key: String, value: Int) = preferences.edit().putInt(key, value).apply()
    private fun storeBoolean(key: String, value: Boolean) = preferences.edit().putBoolean(key, value).apply()
    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).roundToInt()
    private fun rounded(fill: Int, radius: Int = 14, stroke: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            if (stroke != 0) setStroke(dp(1), stroke)
        }
    }
    fun chooseTheme(index: Int) { storeInt("theme", index.coerceIn(0, 4)) }
    fun chooseAccent(index: Int) { storeInt("accent", index.coerceIn(0, 4)) }
    fun chooseVehicle(index: Int) { storeInt("vehicle", index.coerceIn(0, 2)) }
    fun setFollow(value: Boolean) { storeBoolean("follow", value) }
    fun setPerspective(value: Boolean) { storeBoolean("perspective", value) }
    fun setVoice(value: Boolean) { storeBoolean("voice", value) }
    fun setReroute(value: Boolean) { storeBoolean("reroute", value) }
    fun setKeepScreen(value: Boolean) { storeBoolean("keep_screen", value) }

    /**
     * Explicit self-contained settings sheet with immediately applied choices.
     * The callback specifies the category: map, color, vehicle, camera, voice, reroute, keep_screen.
     */
    fun show(onChange: (String) -> Unit) {
        val dialog = Dialog(activity)
        val darkPanel = 0xFF111A29.toInt()
        val cardColor = 0xFF1E2A3D.toInt()
        val muted = 0xFFAABAD1.toInt()
        val border = 0xFF34465E.toInt()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(darkPanel, 24)
            setPadding(dp(18), dp(14), dp(18), dp(16))
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun label(text: String, size: Float, bold: Boolean = false, color: Int = Color.WHITE): TextView {
            return TextView(activity).apply {
                this.text = text
                textSize = size
                setTextColor(color)
                typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
                textDirection = View.TEXT_DIRECTION_RTL
                gravity = Gravity.CENTER
            }
        }
        val done = label("تم ✓", 16f, true, accent.color).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(done, LinearLayout.LayoutParams(dp(65), dp(45)))
        val title = label("إعدادات دَرْبَك 3.0", 21f, true).apply {
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(header)

        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(5))
        }
        fun section(name: String, subtitle: String) {
            content.addView(label(name, 18f, true).apply {
                gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(17), dp(2), 0)
            }, LinearLayout.LayoutParams(-1, dp(48)))
            content.addView(label(subtitle, 12f, false, muted).apply {
                gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(-1, dp(24)))
        }
        section("🎨 ثيم الخريطة", "5 ثيمات حقيقية: غيّر خريطة الشوارع فوراً")
        val themeRows = ArrayList<TextView>()
        for (i in themes.indices) {
            val t = themes[i]
            val row = label(t.name + "    •    " + t.detail + (if (themeIndex == i) "   ✓" else ""), 14.5f, true).apply {
                gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(15), 0)
                background = rounded(if (themeIndex == i) 0xFF214946.toInt() else cardColor,
                    13, if (themeIndex == i) accent.color else border)
                setOnClickListener {
                    chooseTheme(i)
                    for (j in themeRows.indices) {
                        val selected = i == j
                        themeRows[j].background = rounded(if (selected) 0xFF214946.toInt() else cardColor,
                            13, if (selected) accent.color else border)
                        themeRows[j].text = themes[j].name + "    •    " + themes[j].detail +
                            (if (selected) "   ✓" else "")
                    }
                    onChange("map")
                }
            }
            themeRows.add(row)
            content.addView(row, LinearLayout.LayoutParams(-1, dp(45)).apply { bottomMargin = dp(7) })
        }
        section("🌈 لون التطبيق والمسار", "خمسة ألوان تغيّر خطوط الطريق والأزرار")
        val colorRow = LinearLayout(activity).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        val swatches = ArrayList<TextView>()
        for (i in accents.indices) {
            val choice = accents[i]
            val chip = label(if (accentIndex == i) "✓" else "●", 25f, true,
                if (accentIndex == i) Color.WHITE else choice.color).apply {
                background = rounded(if (accentIndex == i) choice.color else cardColor, 15,
                    if (accentIndex == i) Color.WHITE else choice.color)
                setOnClickListener {
                    chooseAccent(i)
                    for (j in swatches.indices) {
                        swatches[j].text = if (j == i) "✓" else "●"
                        swatches[j].setTextColor(if (j == i) Color.WHITE else accents[j].color)
                        swatches[j].background = rounded(if (j == i) accents[j].color else cardColor, 15,
                            if (j == i) Color.WHITE else accents[j].color)
                    }
                    onChange("color")
                }
            }
            swatches.add(chip)
            colorRow.addView(chip, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
                leftMargin = dp(3)
                rightMargin = dp(3)
            })
        }
        content.addView(colorRow, LinearLayout.LayoutParams(-1, dp(56)))
        section("🚘 شكْل موقعك على الطريق", "اختار سهم أو سيارة أو دراجة نارية")
        val vehicles = listOf("➤\nسهم" , "🚘\nسيارة", "🏍\nدراجة")
        val vehicleRow = LinearLayout(activity).apply { gravity = Gravity.CENTER }
        val vehicleChips = ArrayList<TextView>()
        for (i in vehicles.indices) {
            val chip = label(vehicles[i], 16f, true).apply {
                background = rounded(if (i == vehicle) 0xFF214946.toInt() else cardColor, 14,
                    if (i == vehicle) accent.color else border)
                setOnClickListener {
                    chooseVehicle(i)
                    for (j in vehicleChips.indices) {
                        vehicleChips[j].background = rounded(if (j == i) 0xFF214946.toInt() else cardColor,
                            14, if (j == i) accent.color else border)
                    }
                    onChange("vehicle")
                }
            }
            vehicleChips.add(chip)
            vehicleRow.addView(chip, LinearLayout.LayoutParams(0, dp(72), 1f).apply {
                rightMargin = dp(5)
            })
        }
        content.addView(vehicleRow, LinearLayout.LayoutParams(-1, dp(74)))
        section("🧭 وضع القيادة الحقيقي", "تتبع بالكاميرا مع تعليمات GPS")
        fun toggle(name: String, explanation: String, key: String, getter: () -> Boolean, setter: (Boolean) -> Unit) {
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(cardColor, 13, border)
                setPadding(dp(12), dp(6), dp(12), dp(6))
            }
            val primary = label("", 15f, true).apply {
                gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            }
            val secondary = label(explanation, 12f, false, muted).apply {
                gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            }
            fun update() {
                primary.text = (if (getter()) "✓  " else "○  ") + name
                primary.setTextColor(if (getter()) accent.color else Color.WHITE)
            }
            update()
            card.addView(primary, LinearLayout.LayoutParams(-1, dp(34)))
            card.addView(secondary, LinearLayout.LayoutParams(-1, dp(32)))
            card.setOnClickListener {
                setter(!getter())
                update()
                onChange(key)
            }
            content.addView(card, LinearLayout.LayoutParams(-1, dp(70)).apply { bottomMargin = dp(7) })
        }
        toggle("متابعة GPS", "ارجع تتبع مركبتك بعد تحريك الخريطة", "camera", { follow }, ::setFollow)
        toggle("كاميرا ثلاثية الأبعاد", "منظور قيادة مع ميل الخريطة باتجاه الحركة", "camera", { perspective }, ::setPerspective)
        toggle("التوجيه الصوتي", "تنبيهات عراقية + نطق عربي إذا متوفر", "voice", { voice }, ::setVoice)
        toggle("حساب الطريق عند الانحراف", "بعد عدة قراءات موقع موثوقة", "reroute", { reroute }, ::setReroute)
        toggle("إبقاء الشاشة مضيئة", "فقط أثناء الملاحة والتطبيق مفتوح", "keep_screen", { keepScreen }, ::setKeepScreen)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(root)
        dialog.show()
        val widthPixels = activity.resources.displayMetrics.widthPixels
        val heightPixels = activity.resources.displayMetrics.heightPixels
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((widthPixels * 0.94f).roundToInt(), (heightPixels * 0.86f).roundToInt())
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }
}
