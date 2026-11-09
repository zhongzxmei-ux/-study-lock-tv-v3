package com.studylock.tv.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.studylock.tv.R
import com.studylock.tv.audio.TtsAndSoundManager
import com.studylock.tv.config.LockConfig
import com.studylock.tv.data.QuestionRepository
import com.studylock.tv.model.Question

class QuizOverlayManager(
    private val context: Context,
    private val config: LockConfig,
    private val repo: QuestionRepository,
    private val ttsManager: TtsAndSoundManager,
    private val onQuizPassed: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayView: View? = null
    private var currentQuestion: Question? = null
    private var solvedCount = 0
    private val usedIds = mutableSetOf<String>()
    private var waitTimer: CountDownTimer? = null
    private var isLockedByWrongWait = false

    val isShowing: Boolean
        get() = overlayView != null

    fun showQuiz() {
        mainHandler.post {
            if (overlayView != null) {
                bringToFrontIfNeeded()
                return@post
            }
            solvedCount = 0
            usedIds.clear()
            config.addLog("到达观看时长，弹出全屏答题锁")
            if (config.pauseMediaOnQuiz) {
                pauseBackgroundMedia()
            }
            attachOverlay()
            loadNextQuestion()
        }
    }

    fun bringToFrontIfNeeded() {
        val view = overlayView ?: return
        mainHandler.post {
            try {
                windowManager.removeViewImmediate(view)
                windowManager.addView(view, createLayoutParams())
            } catch (_: Exception) {
            }
        }
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
    }

    private fun attachOverlay() {
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.overlay_quiz, null)
        view.isFocusableInTouchMode = true
        view.setOnKeyListener { _, keyCode, event ->
            event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE)
        }
        windowManager.addView(view, createLayoutParams())
        overlayView = view
    }

    private fun loadNextQuestion() {
        val view = overlayView ?: return
        waitTimer?.cancel()
        isLockedByWrongWait = false

        val q = repo.pickRandomQuestion(config, usedIds) ?: return
        currentQuestion = q
        usedIds.add(q.id)

        val total = config.questionsPerRound
        view.findViewById<TextView>(R.id.tvQuizSubtitle).text =
            "本轮要答对 $total 道题，才能继续观看 ${config.watchMinutes} 分钟"
        view.findViewById<TextView>(R.id.tvQuestionMeta).text =
            "${q.gradeLabel} · ${q.subject} · ${q.volume} · 第 ${solvedCount + 1}/$total 题"

        val promptText = if (config.pinyinEnabled && q.grade <= 2 && !q.promptPinyin.isNullOrBlank()) {
            q.promptPinyin + "\n" + q.prompt
        } else {
            q.prompt
        }
        view.findViewById<TextView>(R.id.tvQuestionPrompt).text = promptText

        view.findViewById<TextView>(R.id.tvBannerStatus).visibility = View.GONE
        view.findViewById<LinearLayout>(R.id.layoutExplanation).visibility = View.GONE

        val btnIds = listOf(R.id.btnOptA, R.id.btnOptB, R.id.btnOptC, R.id.btnOptD)
        val labels = listOf("A", "B", "C", "D")
        btnIds.forEachIndexed { index, id ->
            val btn = view.findViewById<Button>(id)
            val optText = q.options.getOrElse(index) { "" }
            btn.text = "${labels[index]}. $optText"
            btn.setBackgroundResource(R.drawable.bg_option_button)
            btn.isEnabled = true
            btn.setOnClickListener { handleAnswer(index) }
        }

        view.findViewById<Button>(R.id.btnReplayQuestion).setOnClickListener {
            ttsManager.speakQuestion(q)
        }
        view.findViewById<Button>(R.id.btnReplayExplain).setOnClickListener {
            ttsManager.speakExplanation(q)
        }

        view.findViewById<Button>(R.id.btnOptA).requestFocus()

        if (config.readAloudGrade12 && q.grade <= 2) {
            ttsManager.speakQuestion(q)
        }
    }

    private fun handleAnswer(selectedIndex: Int) {
        if (isLockedByWrongWait) return
        val q = currentQuestion ?: return
        val view = overlayView ?: return
        ttsManager.stopSpeaking()

        if (selectedIndex == q.correctIndex) {
            solvedCount++
            ttsManager.playPraise(config.praiseMode)
            val banner = view.findViewById<TextView>(R.id.tvBannerStatus)
            banner.visibility = View.VISIBLE
            banner.setBackgroundColor(Color.parseColor("#1B8A4B"))

            val total = config.questionsPerRound
            if (solvedCount >= total) {
                banner.text = "全部答对，真棒！正在解锁电视..."
                config.addLog("本轮 $total 题全部答对，已解锁电视")
                mainHandler.postDelayed({ dismissAndUnlock() }, 1400)
            } else {
                banner.text = "答对啦！还需答对 ${total - solvedCount} 道题，马上出下一题"
                mainHandler.postDelayed({ loadNextQuestion() }, 1200)
            }
        } else {
            isLockedByWrongWait = true
            config.addLog("答错题目：${q.prompt}")
            val btnIds = listOf(R.id.btnOptA, R.id.btnOptB, R.id.btnOptC, R.id.btnOptD)
            btnIds.forEachIndexed { idx, id ->
                val btn = view.findViewById<Button>(id)
                btn.isEnabled = false
                if (idx == q.correctIndex) {
                    btn.setBackgroundColor(Color.parseColor("#1B8A4B"))
                } else if (idx == selectedIndex) {
                    btn.setBackgroundColor(Color.parseColor("#B83232"))
                }
            }

            val explainBox = view.findViewById<LinearLayout>(R.id.layoutExplanation)
            explainBox.visibility = View.VISIBLE
            val labels = listOf("A", "B", "C", "D")
            view.findViewById<TextView>(R.id.tvCorrectAnswer).text =
                "正确答案：${labels[q.correctIndex]}. ${q.options[q.correctIndex]}"
            view.findViewById<TextView>(R.id.tvExplainBody).text = "解题思路：${q.explanation}"

            if (config.explainAloudOnWrong) {
                ttsManager.speakExplanation(q)
            }

            val waitMs = config.wrongWaitSeconds * 1000L
            val tvWait = view.findViewById<TextView>(R.id.tvWaitCountdown)
            if (waitMs <= 0L) {
                tvWait.text = "即将切换下一题..."
                mainHandler.postDelayed({ loadNextQuestion() }, 900)
            } else {
                waitTimer = object : CountDownTimer(waitMs, 1000) {
                    override fun onTick(millisUntilFinished: Long) {
                        val sec = (millisUntilFinished / 1000L) + 1
                        tvWait.text = "还需等待 ${sec} 秒才能换新题"
                    }
                    override fun onFinish() {
                        loadNextQuestion()
                    }
                }.start()
            }
        }
    }

    private fun dismissAndUnlock() {
        waitTimer?.cancel()
        ttsManager.stopSpeaking()
        overlayView?.let {
            try {
                windowManager.removeViewImmediate(it)
            } catch (_: Exception) {
            }
        }
        overlayView = null
        onQuizPassed()
    }

    private fun pauseBackgroundMedia() {
        try {
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0)
            val up = KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0)
            audioManager.dispatchMediaKeyEvent(down)
            audioManager.dispatchMediaKeyEvent(up)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    ).build()
                audioManager.requestAudioFocus(req)
            }
        } catch (_: Exception) {
        }
    }
}
