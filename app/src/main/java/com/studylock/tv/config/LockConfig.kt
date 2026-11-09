package com.studylock.tv.config

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class PraiseMode { OFF, SFX_ONLY, SFX_AND_VOICE }

class LockConfig(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("study_lock_prefs", Context.MODE_PRIVATE)

    var guardEnabled: Boolean
        get() = prefs.getBoolean("guard_enabled", false)
        set(value) = prefs.edit().putBoolean("guard_enabled", value).apply()

    var watchMinutes: Int
        get() = prefs.getInt("watch_minutes", 15).coerceIn(1, 600)
        set(value) = prefs.edit().putInt("watch_minutes", value.coerceIn(1, 600)).apply()

    var questionsPerRound: Int
        get() = prefs.getInt("questions_per_round", 2).coerceIn(1, 10)
        set(value) = prefs.edit().putInt("questions_per_round", value.coerceIn(1, 10)).apply()

    var wrongWaitSeconds: Int
        get() = prefs.getInt("wrong_wait_seconds", 60).coerceIn(0, 300)
        set(value) = prefs.edit().putInt("wrong_wait_seconds", value.coerceIn(0, 300)).apply()

    var praiseMode: PraiseMode
        get() = PraiseMode.values().getOrElse(prefs.getInt("praise_mode", 1)) { PraiseMode.SFX_ONLY }
        set(value) = prefs.edit().putInt("praise_mode", value.ordinal).apply()

    var pinyinEnabled: Boolean
        get() = prefs.getBoolean("pinyin_enabled", true)
        set(value) = prefs.edit().putBoolean("pinyin_enabled", value).apply()

    var pauseMediaOnQuiz: Boolean
        get() = prefs.getBoolean("pause_media_on_quiz", true)
        set(value) = prefs.edit().putBoolean("pause_media_on_quiz", value).apply()

    var readAloudGrade12: Boolean
        get() = prefs.getBoolean("read_aloud_grade12", true)
        set(value) = prefs.edit().putBoolean("read_aloud_grade12", value).apply()

    var explainAloudOnWrong: Boolean
        get() = prefs.getBoolean("explain_aloud_on_wrong", true)
        set(value) = prefs.edit().putBoolean("explain_aloud_on_wrong", value).apply()

    var strictMode: Boolean
        get() = prefs.getBoolean("strict_mode", true)
        set(value) = prefs.edit().putBoolean("strict_mode", value).apply()

    var scheduleAllDay: Boolean
        get() = prefs.getBoolean("schedule_all_day", true)
        set(value) = prefs.edit().putBoolean("schedule_all_day", value).apply()

    var scheduleStartMinutes: Int
        get() = prefs.getInt("schedule_start_min", 18 * 60)
        set(value) = prefs.edit().putInt("schedule_start_min", ((value % 1440) + 1440) % 1440).apply()

    var scheduleEndMinutes: Int
        get() = prefs.getInt("schedule_end_min", 21 * 60)
        set(value) = prefs.edit().putInt("schedule_end_min", ((value % 1440) + 1440) % 1440).apply()

    var tempPauseUntilMillis: Long
        get() = prefs.getLong("temp_pause_until", 0L)
        set(value) = prefs.edit().putLong("temp_pause_until", value).apply()

    var enabledSubjects: Set<String>
        get() = prefs.getStringSet("enabled_subjects", setOf("语文", "数学")) ?: setOf("语文", "数学")
        set(value) = prefs.edit().putStringSet("enabled_subjects", value).apply()

    var enabledGrades: Set<String>
        get() = prefs.getStringSet("enabled_grades", setOf("1", "2", "3", "4", "5", "6")) ?: setOf("1", "2", "3", "4", "5", "6")
        set(value) = prefs.edit().putStringSet("enabled_grades", value).apply()

    var enabledVolumes: Set<String>
        get() = prefs.getStringSet("enabled_volumes", setOf("上册", "下册", "通用", "全册")) ?: setOf("上册", "下册", "通用", "全册")
        set(value) = prefs.edit().putStringSet("enabled_volumes", value).apply()

    var parentalPin: String
        get() = prefs.getString("parental_pin", "0000") ?: "0000"
        set(value) = prefs.edit().putString("parental_pin", value).apply()

    fun addLog(msg: String) {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val existing = prefs.getString("run_logs", "") ?: ""
        val lines = (listOf("[$ts] $msg") + existing.lines().filter { it.isNotBlank() }).take(50)
        prefs.edit().putString("run_logs", lines.joinToString("\n")).apply()
    }

    fun getLogs(): List<String> {
        val raw = prefs.getString("run_logs", "") ?: ""
        return raw.lines().filter { it.isNotBlank() }
    }
}
