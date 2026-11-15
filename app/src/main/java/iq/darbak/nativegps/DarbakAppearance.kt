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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
 * Darbak 4.0 premium native preferences. The mapping from theme to OpenFreeMap
 * is genuine, not a simulated overlay. The five accent colors are independent.
 */
class DarbakAppearance(private val activity: Activity) {
    private val pref = activity.getSharedPreferences("darbak_v3_settings",Context.MODE_PRIVATE)
    val themes = listOf(
        DarbakTheme("NEON / ليل النيون","خريطة داكنة عالية التباين","dark",true,
            0xFF0B1624.toInt(),0xFF16273C.toInt(),Color.WHITE),
        DarbakTheme("CRYSTAL / كريستال","مدينة مضيئة وتفاصيل واضحة","bright",false,
            0xFFF2F7FF.toInt(),0xFFFFFFFF.toInt(),0xFF13243A.toInt()),
        DarbakTheme("SAND / الصحراء","طابع رملي بألوان دافئة","liberty",false,
            0xFFF7EFE3.toInt(),0xFFFFFCF4.toInt(),0xFF2E2930.toInt()),
        DarbakTheme("OBSIDIAN / أوبسيديان","خريطة ليلية بدرجات محيطية","fiord",true,
            0xFF101C2D.toInt(),0xFF1B3145.toInt(),Color.WHITE),
        DarbakTheme("ZEN / مينيمل","هادئ وخفيف على العين","positron",false,
            0xFFF5F6F7.toInt(),0xFFFFFFFF.toInt(),0xFF232933.toInt())
    )
    val accents = listOf(
        DarbakAccent("فيروزي",0xFF21DEB4.toInt()),
        DarbakAccent("أزرق",0xFF4A91FF.toInt()),
        DarbakAccent("بنفسجي",0xFF9F79FF.toInt()),
        DarbakAccent("ذهبي",0xFFFFC45A.toInt()),
        DarbakAccent("مرجاني",0xFFFF6F86.toInt())
    )
    val themeIndex: Int get() = pref.getInt("theme",0).coerceIn(0,4)
    val accentIndex: Int get() = pref.getInt("accent",0).coerceIn(0,4)
    val vehicle: Int get() = pref.getInt("vehicle",0).coerceIn(0,2)
    val follow: Boolean get() = pref.getBoolean("follow",true)
    val perspective: Boolean get() = pref.getBoolean("perspective",true)
    val voice: Boolean get() = pref.getBoolean("voice",true)
    val reroute: Boolean get() = pref.getBoolean("reroute",true)
    val keepScreen: Boolean get() = pref.getBoolean("keep_screen",true)
    val speedVisible: Boolean get() = pref.getBoolean("speed_visible",false)
    val speedStyle: Int get() = pref.getInt("speed_style",0).coerceIn(0,1)
    val theme: DarbakTheme get() = themes[themeIndex]
    val accent: DarbakAccent get() = accents[accentIndex]
    val mapStyle: String get() = "https://tiles.openfreemap.org/styles/" + theme.style
    val vehicleLabel: String get() = listOf("سهم الملاحة","سيارة","دراجة نارية")[vehicle]
    private fun setNumber(key:String,value:Int) {pref.edit().putInt(key,value).apply()}
    private fun setBool(key:String,value:Boolean) {pref.edit().putBoolean(key,value).apply()}
    fun chooseTheme(i:Int) = setNumber("theme",i.coerceIn(0,4))
    fun chooseAccent(i:Int) = setNumber("accent",i.coerceIn(0,4))
    fun chooseVehicle(i:Int) = setNumber("vehicle",i.coerceIn(0,2))
    fun setFollow(b:Boolean) = setBool("follow",b)
    fun setPerspective(b:Boolean) = setBool("perspective",b)
    fun setVoice(b:Boolean) = setBool("voice",b)
    fun setReroute(b:Boolean) = setBool("reroute",b)
    fun setKeepScreen(b:Boolean) = setBool("keep_screen",b)
    fun setSpeedVisible(b:Boolean) = setBool("speed_visible",b)
    fun setSpeedStyle(i:Int) = setNumber("speed_style",i.coerceIn(0,1))
    private fun dp(n:Int) = (n*activity.resources.displayMetrics.density).roundToInt()
    private fun bg(fill:Int,radius:Int=16,stroke:Int=0,thick:Int=1):GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius=dp(radius).toFloat()
            if(stroke!=0)setStroke(dp(thick),stroke)
        }
    private fun gradient(start:Int,end:Int,radius:Int=20):GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(start,end)).apply {
            cornerRadius=dp(radius).toFloat()
        }
    private fun text(value:String,size:Float,bold:Boolean=false,color:Int=Color.WHITE):TextView =
        TextView(activity).apply {
            this.text=value
            textSize=size
            typeface=Typeface.create("sans-serif", if(bold) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(color)
            gravity=Gravity.CENTER
            textDirection=View.TEXT_DIRECTION_RTL
            includeFontPadding=false
        }
    /**
     * Native three-tab settings with live preview.
     * Tabs: appearance, navigation, gauges. Only the user can enable speed overlay.
     */
    fun show(onChange:(String)->Unit, initialTab:Int=0) {
        val dialog=Dialog(activity)
        val dark=0xFF0E1726.toInt()
        val surface=0xFF1C2B40.toInt()
        val stroke=0xFF34445C.toInt()
        val subdued=0xFFAABBD0.toInt()
        val host=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(15),dp(12),dp(15),dp(13))
            background=bg(dark,26)
        }
        val hero=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(17),dp(13),dp(17),dp(13))
            background=gradient(0xFF193346.toInt(),0xFF203357.toInt(),20)
        }
        val header=LinearLayout(activity).apply{
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
        }
        val dismiss=text("تم ✓",15f,true,accent.color).apply {
            background=bg(0xFF233749.toInt(),12)
            setOnClickListener{ dialog.dismiss() }
        }
        header.addView(dismiss,LinearLayout.LayoutParams(dp(65),dp(41)))
        val identity=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
        identity.addView(text("دَرْبَك 4.0  ✦",22f,true).apply{gravity=Gravity.RIGHT or Gravity.CENTER_VERTICAL},
            LinearLayout.LayoutParams(-1,dp(33)))
        identity.addView(text("خصص رحلتك على ذوقك",12f,false,subdued).apply {
            gravity=Gravity.RIGHT or Gravity.CENTER_VERTICAL
        },LinearLayout.LayoutParams(-1,dp(22)))
        header.addView(identity,LinearLayout.LayoutParams(0,dp(59),1f))
        hero.addView(header)
        val themePreview=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
        val previewName=text("",14f,true).apply{gravity=Gravity.RIGHT or Gravity.CENTER_VERTICAL}
        val previewDetail=text("",11f,false,subdued).apply{gravity=Gravity.RIGHT or Gravity.CENTER_VERTICAL}
        themePreview.addView(previewName,LinearLayout.LayoutParams(-1,dp(27)))
        themePreview.addView(previewDetail,LinearLayout.LayoutParams(-1,dp(22)))
        hero.addView(themePreview,LinearLayout.LayoutParams(-1,dp(49)))
        host.addView(hero,LinearLayout.LayoutParams(-1,-2))
        val tabBar=LinearLayout(activity).apply{
            orientation=LinearLayout.HORIZONTAL
            setPadding(dp(3),dp(9),dp(3),dp(9))
        }
        val tabTitles=listOf("🎨  المظهر","🧭  الملاحة","◉  العدادات")
        val tabViews=ArrayList<TextView>()
        val scroll=ScrollView(activity).apply {
            isVerticalScrollBarEnabled=false
            isFillViewport=false
        }
        val body=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(3),dp(1),dp(3),dp(25))
        }
        scroll.addView(body)
        fun refreshHero(){
            previewName.text=theme.name+"    ·    "+accent.name
            previewDetail.text="نمط الخريطة: "+theme.style+"     |     "+vehicleLabel
            previewName.setTextColor(accent.color)
        }
        refreshHero()
        fun title(name:String,desc:String){
            body.addView(text(name,18f,true).apply{
                gravity=Gravity.RIGHT or Gravity.CENTER_VERTICAL
                setPadding(dp(5),dp(9),dp(5),0)
            },LinearLayout.LayoutParams(-1,dp(49)))
            body.addView(text(desc,11.5f,false,subdued).apply{
                gravity=Gravity.RIGHT or Gravity.CENTER_VERTICAL
                setPadding(dp(4),0,dp(4),0)
            },LinearLayout.LayoutParams(-1,dp(25)))
        }
        fun spacer(height:Int=10){body.addView(View(activity),LinearLayout.LayoutParams(-1,dp(height)))}
        lateinit var render:(Int)->Unit
        fun choice(textValue:String,detail:String,checked:Boolean,click:()->Unit):LinearLayout {
            val card=LinearLayout(activity).apply{
                orientation=LinearLayout.VERTICAL
                setPadding(dp(13),dp(9),dp(13),dp(9))
                background=bg(if(checked) 0xFF243F4A.toInt() else surface,14,
                    if(checked) accent.color else stroke,if(checked) 2 else 1)
                setOnClickListener{click()}
                isClickable=true
            }
            val main=text(textValue+(if(checked) "   ✓" else ""),14f,true).apply{
                gravity=Gravity.RIGHT or Gravity.CENTER_VERTICAL
                setTextColor(if(checked) accent.color else Color.WHITE)
            }
            card.addView(main,LinearLayout.LayoutParams(-1,dp(29)))
            card.addView(text(detail,11.5f,false,subdued).apply{
                gravity=Gravity.RIGHT or Gravity.CENTER_VERTICAL
            },LinearLayout.LayoutParams(-1,dp(26)))
            return card
        }
        fun toggle(label:String,description:String,on:Boolean,kind:String,change:(Boolean)->Unit) {
            val row=LinearLayout(activity).apply{
                orientation=LinearLayout.HORIZONTAL
                gravity=Gravity.CENTER_VERTICAL
                setPadding(dp(11),dp(8),dp(11),dp(8))
                background=bg(surface,15,stroke)
            }
            val indicator=text(if(on)"✓" else "○",20f,true,
                if(on) 0xFF071B1F.toInt() else subdued).apply {
                background=bg(if(on)accent.color else 0xFF27354B.toInt(),12)
            }
            row.addView(indicator,LinearLayout.LayoutParams(dp(40),dp(40)))
            val captions=LinearLayout(activity).apply{
                orientation=LinearLayout.VERTICAL
                setPadding(dp(8),0,0,0)
            }
            captions.addView(text(label,14.5f,true).apply{
                gravity=Gravity.RIGHT or Gravity.CENTER_VERTICAL
            },LinearLayout.LayoutParams(-1,dp(28)))
            captions.addView(text(description,11.5f,false,subdued).apply{
                gravity=Gravity.RIGHT or Gravity.CENTER_VERTICAL
            },LinearLayout.LayoutParams(-1,dp(29)))
            row.addView(captions,LinearLayout.LayoutParams(0,dp(59),1f))
            row.setOnClickListener{
                change(!on)
                onChange(kind)
                render(if(kind=="speed" || kind=="speed_style")2 else 1)
            }
            body.addView(row,LinearLayout.LayoutParams(-1,dp(75)).apply{bottomMargin=dp(8)})
        }
        render={tab->
            body.removeAllViews()
            for(i in tabViews.indices){
                tabViews[i].background=bg(if(tab==i)accent.color else surface,12)
                tabViews[i].setTextColor(if(tab==i)0xFF10212A.toInt() else subdued)
            }
            refreshHero()
            when(tab){
                0->{
                    title("خرائط بتصميمك","5 ثيمات خرائط متجهية مع لوحة ألوان مختلفة")
                    for(base in 0 until themes.size step 2){
                        val strip=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL}
                        for(k in base until minOf(base+2,themes.size)){
                            val t=themes[k]
                            val selected=themeIndex==k
                            val tile=LinearLayout(activity).apply{
                                orientation=LinearLayout.VERTICAL
                                setPadding(dp(8),dp(8),dp(8),dp(10))
                                background=bg(if(selected)0xFF243F4A.toInt() else surface,17,
                                    if(selected)accent.color else stroke,if(selected)2 else 1)
                            }
                            val sample=LinearLayout(activity).apply{
                                orientation=LinearLayout.VERTICAL
                                setPadding(dp(12),dp(10),dp(12),dp(7))
                                background=gradient(t.panel,t.panelSecondary,13)
                            }
                            val roads=TextView(activity).apply{
                                text="┌──────────┐\n└──┬───────┘"
                                textSize=14f
                                setTextColor(if(t.dark)accent.color else 0xFF7C97AF.toInt())
                                gravity=Gravity.CENTER
                                letterSpacing=0.07f
                            }
                            sample.addView(roads,LinearLayout.LayoutParams(-1,dp(57)))
                            tile.addView(sample,LinearLayout.LayoutParams(-1,dp(74)))
                            tile.addView(text(t.name,12.5f,true,if(selected)accent.color else Color.WHITE),
                                LinearLayout.LayoutParams(-1,dp(36)))
                            tile.addView(text(if(selected)"● مفعل" else t.detail,10.5f,false,subdued),
                                LinearLayout.LayoutParams(-1,dp(25)))
                            tile.setOnClickListener{
                                chooseTheme(k);onChange("map");render(0)
                            }
                            strip.addView(tile,LinearLayout.LayoutParams(0,dp(165),1f).apply{
                                leftMargin=dp(3);rightMargin=dp(3)
                            })
                        }
                        if(base+1>=themes.size){
                            strip.addView(View(activity),LinearLayout.LayoutParams(0,dp(165),1f))
                        }
                        body.addView(strip,LinearLayout.LayoutParams(-1,dp(171)).apply{bottomMargin=dp(6)})
                    }
                    title("ألوان التوجيه","لون المسار الرئيسي ومؤشر سيارتك والأزرار")
                    val colorsRow=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL}
                    for(i in accents.indices){
                        val x=accents[i]
                        val swatch=text(if(accentIndex==i)"✓" else "",22f,true,
                            if(i==3)0xFF30200B.toInt() else Color.WHITE).apply{
                            background=bg(x.color,17,if(i==accentIndex)Color.WHITE else stroke,
                                if(i==accentIndex)3 else 1)
                            setOnClickListener{chooseAccent(i);onChange("color");render(0)}
                        }
                        colorsRow.addView(swatch,LinearLayout.LayoutParams(0,dp(54),1f).apply{
                            leftMargin=dp(3);rightMargin=dp(3)
                        })
                    }
                    body.addView(colorsRow,LinearLayout.LayoutParams(-1,dp(56)))
                    title("شكل مركبتك","سهم ملاحة أو سيارة أو دراجة — معاينة حقيقية")
                    val shapes=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL}
                    val names=listOf("سهم","سيارة","دراجة")
                    val bitmapMaker=DarbakVehicleIcons(activity)
                    for(i in 0..2){
                        val selected=vehicle==i
                        val tile=LinearLayout(activity).apply{
                            orientation=LinearLayout.VERTICAL
                            gravity=Gravity.CENTER
                            background=bg(if(selected)0xFF243F4A.toInt() else surface,16,
                                if(selected)accent.color else stroke,if(selected)2 else 1)
                            setOnClickListener{chooseVehicle(i);onChange("vehicle");render(0)}
                        }
                        val preview=ImageView(activity).apply {
                            setImageBitmap(bitmapMaker.bitmap(i,accent.color))
                            scaleType=ImageView.ScaleType.FIT_CENTER
                        }
                        tile.addView(preview,LinearLayout.LayoutParams(dp(65),dp(65)))
                        tile.addView(text(names[i]+(if(selected)" ✓" else ""),13f,true,
                            if(selected)accent.color else Color.WHITE),LinearLayout.LayoutParams(-1,dp(25)))
                        shapes.addView(tile,LinearLayout.LayoutParams(0,dp(106),1f).apply{
                            leftMargin=dp(3);rightMargin=dp(3)
                        })
                    }
                    body.addView(shapes,LinearLayout.LayoutParams(-1,dp(110)))
                }
                1->{
                    title("القيادة والتوجيه","تحكم بتتبع GPS وصوت الرحلة بشكل مستقل")
                    toggle("اتبع موقعي","ارجع تتبع المسار بعد تحريك الخريطة",follow,"camera",::setFollow)
                    toggle("منظور القيادة 3D","الخريطة تميل وتدور مع اتجاه المركبة",perspective,"camera",::setPerspective)
                    toggle("التوجيه الصوتي العراقي","صوتيات داخل التطبيق والنطق العربي",voice,"voice",::setVoice)
                    toggle("حساب مسار جديد","عند الانحراف عن الطريق مع توفر الشبكة",reroute,"reroute",::setReroute)
                    toggle("الشاشة تبقى مضيئة","أثناء الرحلة فقط والتطبيق ظاهر",keepScreen,"keep_screen",::setKeepScreen)
                    spacer()
                    val note=text("ملاحظة: دَرْبَك نسخة تجريبية. تحقّق من الطريق والإشارات، ولا تتفاعل مع الشاشة أثناء القيادة.",
                        12f,false,subdued).apply{setPadding(dp(14),dp(12),dp(14),dp(12));background=bg(surface,14)}
                    body.addView(note)
                }
                else->{
                    title("لوحة عداداتك","المستخدم يختار شنو يشوف على الخريطة")
                    toggle("عرض عداد السرعة","مخفي افتراضياً — يظهر بس من تفعّله",speedVisible,"speed",::setSpeedVisible)
                    title("مظهر عداد السرعة","يمكن تغيير شكل العداد حتى وهو مخفي")
                    body.addView(choice("01  |  رقمي بسيط","عرض السرعة الرقمية كم/س",speedStyle==0){
                        setSpeedStyle(0);onChange("speed_style");render(2)
                    },LinearLayout.LayoutParams(-1,dp(79)).apply{bottomMargin=dp(9)})
                    body.addView(choice("02  |  حلقي رياضي","مؤشر سرعة داخل دائرة واضحة",speedStyle==1){
                        setSpeedStyle(1);onChange("speed_style");render(2)
                    },LinearLayout.LayoutParams(-1,dp(79)).apply{bottomMargin=dp(9)})
                    spacer(12)
                    val box=text("العداد يعتمد سرعة GPS الفعلية عند توفر إشارة جيدة. مو قراءة عداد السيارة، وقد يتأخر أو يختلف عنه.",
                        12f,false,subdued).apply {
                        setPadding(dp(15),dp(12),dp(15),dp(12))
                        background=bg(surface,14)
                    }
                    body.addView(box,LinearLayout.LayoutParams(-1,dp(83)))
                }
            }
        }
        for(i in tabTitles.indices){
            val index=i
            val tab=text(tabTitles[i],13f,true).apply{
                setOnClickListener{render(index)}
            }
            tabViews.add(tab)
            tabBar.addView(tab,LinearLayout.LayoutParams(0,dp(47),1f).apply{
                leftMargin=dp(3);rightMargin=dp(3)
            })
        }
        host.addView(tabBar)
        host.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        render(initialTab.coerceIn(0,2))
        dialog.setContentView(host)
        dialog.show()
        dialog.window?.apply{
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((activity.resources.displayMetrics.widthPixels*0.96f).roundToInt(),
                (activity.resources.displayMetrics.heightPixels*0.91f).roundToInt())
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }
}
