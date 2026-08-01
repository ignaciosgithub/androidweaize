package com.weaize.app

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Text-to-speech voice alerts in the user-selected language. */
class VoiceAlerts(context: Context) {
  private var ready = false
  private var pendingLanguage: String = Prefs.language(context)
  private val tts: TextToSpeech =
      TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
          ready = true
          applyLanguage(pendingLanguage)
        }
      }

  private var lastSpeedWarningMs = 0L

  fun applyLanguage(languageTag: String) {
    pendingLanguage = languageTag
    if (!ready) return
    tts.language = Locale.forLanguageTag(languageTag)
  }

  /** Resolves warning strings in the selected TTS language rather than the system locale. */
  private fun localized(context: Context): Context {
    val config = android.content.res.Configuration(context.resources.configuration)
    config.setLocale(Locale.forLanguageTag(pendingLanguage))
    return context.createConfigurationContext(config)
  }

  fun speak(text: String) {
    if (!ready) return
    tts.speak(text, TextToSpeech.QUEUE_ADD, null, "weaize-${System.currentTimeMillis()}")
  }

  fun speedWarning(context: Context, speedKmh: Float, limitKmh: Float) {
    val now = System.currentTimeMillis()
    if (now - lastSpeedWarningMs < 15_000) return
    lastSpeedWarningMs = now
    applyLanguage(Prefs.language(context))
    speak(localized(context).getString(R.string.tts_speed_warning, speedKmh.toInt(), limitKmh.toInt()))
  }

  fun sharpTurnWarning(context: Context) {
    applyLanguage(Prefs.language(context))
    speak(localized(context).getString(R.string.tts_sharp_turn))
  }

  fun gpsLostWarning(context: Context) {
    applyLanguage(Prefs.language(context))
    speak(localized(context).getString(R.string.tts_gps_lost))
  }

  fun shutdown() {
    tts.stop()
    tts.shutdown()
  }
}
