package com.studylock.tv.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import com.studylock.tv.config.PraiseMode
import com.studylock.tv.model.Question
import java.util.Locale

class TtsAndSoundManager(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ttsReady = false
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 85)

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val res = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
            ttsReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
            tts?.setSpeechRate(0.95f)
        }
    }

    fun speakText(text: String) {
        if (!ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_${System.currentTimeMillis()}")
    }

    fun speakQuestion(q: Question) {
        if (!ttsReady) return
        val labels = listOf("A", "B", "C", "D")
        val optionsText = q.options.mapIndexed { idx, opt ->
            "${labels.getOrElse(idx) { "" }}，$opt"
        }.joinToString("。")
        speakText("请听题。${q.prompt}。选项是：$optionsText")
    }

    fun speakExplanation(q: Question) {
        if (!ttsReady) return
        val labels = listOf("A", "B", "C", "D")
        val ansLabel = labels.getOrElse(q.correctIndex) { "" }
        val ansText = q.options.getOrElse(q.correctIndex) { "" }
        speakText("答错了。正确答案是 $ansLabel，$ansText。解题思路：${q.explanation}")
    }

    fun playPraise(mode: PraiseMode) {
        when (mode) {
            PraiseMode.OFF -> Unit
            PraiseMode.SFX_ONLY -> toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 240)
            PraiseMode.SFX_AND_VOICE -> {
                toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 200)
                speakText("答对了，真棒！继续保持哦！")
            }
        }
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        toneGenerator.release()
    }
}
