package kiesel

import android.content.Context
import android.icu.text.MeasureFormat
import android.icu.text.NumberFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.kiesel.R
import java.util.Locale
import kotlin.math.roundToInt

val Context.locale: Locale get() = resources.configuration.locales[0]

fun Context.instruction(maneuver: Maneuver): String {
    val action = when (maneuver.turn) {
        Turn.Roundabout -> getString(R.string.roundabout, maneuver.exit)
        else -> getString(phraseOf(maneuver.turn))
    }
    val street = maneuver.street ?: return action
    return getString(R.string.onto, action, street)
}

fun Context.spoken(announcement: Announcement): String {
    val maneuver = announcement.maneuver
    val meters = announcement.meters
    if (maneuver.turn == Turn.Arrive && meters == null) return getString(R.string.arrived)
    val action = instruction(maneuver)
    val first = if (meters == null) action else inDistance(meters, action)
    val then = announcement.then
        ?.let { getString(R.string.and_then, first, instruction(it)) }
    return capitalized(then ?: first)
}

fun Context.capitalized(text: String) = text.replaceFirstChar { it.titlecase(locale) }

fun Context.distanceText(meters: Double): String {
    val kilometers = meters >= 1000
    val digits = NumberFormat.getInstance(locale).apply {
        maximumFractionDigits = if (kilometers && meters < 10_000) 1 else 0
    }
    val width = MeasureFormat.FormatWidth.SHORT
    val format = MeasureFormat.getInstance(locale, width, digits)
    val measure = when {
        kilometers -> Measure(meters / 1000, MeasureUnit.KILOMETER)
        meters < 100 -> Measure(meters.toInt() / 5 * 5, MeasureUnit.METER)
        else -> Measure(meters.toInt() / 10 * 10, MeasureUnit.METER)
    }
    return format.format(measure)
}

fun Context.durationText(seconds: Double): String {
    val minutes = maxOf(1, (seconds / 60).roundToInt())
    val format = MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT)
    if (minutes < 60) return format.format(Measure(minutes, MeasureUnit.MINUTE))
    val narrow = MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.NARROW)
    return narrow.formatMeasures(
        Measure(minutes / 60, MeasureUnit.HOUR),
        Measure(minutes % 60, MeasureUnit.MINUTE),
    )
}

private fun Context.inDistance(meters: Double, action: String): String {
    val rounded = when {
        meters < 100 -> (meters / 10).roundToInt() * 10
        meters < 1000 -> (meters / 50).roundToInt() * 50
        else -> (meters / 100).roundToInt() * 100
    }.coerceAtLeast(10)
    if (rounded < 1000) {
        return resources.getQuantityString(R.plurals.in_meters, rounded, rounded, action)
    }
    val kilometers = rounded / 1000.0
    val text = NumberFormat.getInstance(locale).apply { maximumFractionDigits = 1 }
        .format(kilometers)
    val quantity = if (kilometers % 1 == 0.0) kilometers.toInt() else 2
    return resources.getQuantityString(R.plurals.in_kilometers, quantity, text, action)
}

private fun phraseOf(turn: Turn) = when (turn) {
    Turn.SharpLeft -> R.string.turn_sharp_left
    Turn.Left -> R.string.turn_left
    Turn.SlightLeft -> R.string.turn_slight_left
    Turn.KeepLeft -> R.string.keep_left
    Turn.KeepRight -> R.string.keep_right
    Turn.SlightRight -> R.string.turn_slight_right
    Turn.Right -> R.string.turn_right
    Turn.SharpRight -> R.string.turn_sharp_right
    Turn.Roundabout -> R.string.roundabout
    Turn.Arrive -> R.string.arrive
}

/** On-device speech in the app language, using the most natural installed voice. */
class Voice(private val context: Context) {
    var muted = false
    private var ready = false
    private var language: Locale? = null
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
    private val engine = TextToSpeech(context) { ready = it == TextToSpeech.SUCCESS }

    init {
        engine.setAudioAttributes(attributes)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = release()
            @Deprecated("Required override")
            override fun onError(utteranceId: String?) = release()
        })
    }

    fun speak(text: String) {
        if (muted || !ready) return
        useLanguage(context.locale)
        audio.requestAudioFocus(focus)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, text)
    }

    fun stop() {
        engine.stop()
        release()
    }

    private fun release() {
        audio.abandonAudioFocusRequest(focus)
    }

    private fun useLanguage(locale: Locale) {
        if (locale == language) return
        language = locale
        val best = engine.voices.orEmpty()
            .filter { it.locale.language == locale.language && isOffline(it) }
            .maxWithOrNull(
                compareBy(
                    { it.locale.country == locale.country },
                    { it.quality },
                    { -it.latency },
                ),
            )
        if (best != null) engine.voice = best else engine.language = locale
    }

    private fun isOffline(voice: android.speech.tts.Voice) =
        !voice.isNetworkConnectionRequired &&
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features
}
