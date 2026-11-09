package com.studylock.tv.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.studylock.tv.config.LockConfig
import com.studylock.tv.model.Question
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class QuestionRepository(private val context: Context) {
    private var cachedQuestions: List<Question>? = null
    private val customDownloadedFile = File(context.filesDir, "url_imported_questions.json")

    fun loadAll(): List<Question> {
        cachedQuestions?.let { return it }
        val list = mutableListOf<Question>()
        val gson = Gson()
        val type = object : TypeToken<List<Question>>() {}.type

        // 1. 内置人教版 1-9 年级同步题库
        try {
            val json = context.assets.open("questions_sample.json")
                .bufferedReader().use { it.readText() }
            val builtIn: List<Question>? = gson.fromJson(json, type)
            if (builtIn != null) list.addAll(builtIn)
        } catch (_: Exception) {
        }

        // 2. 通过 URL 在线导入的扩展题库
        try {
            if (customDownloadedFile.exists()) {
                val urlJson = customDownloadedFile.readText(Charsets.UTF_8)
                val urlList: List<Question>? = gson.fromJson(urlJson, type)
                if (urlList != null) list.addAll(urlList)
            }
        } catch (_: Exception) {
        }

        // 3. 通过 U 盘/Download 目录导入的外部题库
        try {
            val externalFile = File("/sdcard/Download/study_lock_questions.json")
            if (externalFile.exists() && externalFile.canRead()) {
                val extJson = externalFile.readText(Charsets.UTF_8)
                val extList: List<Question>? = gson.fromJson(extJson, type)
                if (extList != null) list.addAll(extList)
            }
        } catch (_: Exception) {
        }

        cachedQuestions = list
        return list
    }

    fun reload() {
        cachedQuestions = null
    }

    fun getTotalCount(): Int = loadAll().size

    fun getMatchingCount(config: LockConfig): Int = filterQuestions(config).size

    fun importFromUrl(urlString: String): Result<Int> {
        return try {
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 15000
                requestMethod = "GET"
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val type = object : TypeToken<List<Question>>() {}.type
            val parsed: List<Question> = Gson().fromJson(body, type)
                ?: return Result.failure(IllegalArgumentException("JSON 格式无效"))
            customDownloadedFile.writeText(body, Charsets.UTF_8)
            reload()
            Result.success(parsed.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun filterQuestions(config: LockConfig): List<Question> {
        val all = loadAll()
        val subjects = config.enabledSubjects
        val grades = config.enabledGrades.mapNotNull { it.toIntOrNull() }.toSet()
        val volumes = config.enabledVolumes

        return all.filter { q ->
            (subjects.isEmpty() || q.subject in subjects || (q.subject == "拼音题" && "语文" in subjects)) &&
                (grades.isEmpty() || q.grade in grades) &&
                (volumes.isEmpty() || q.volume in volumes || q.volume == "通用" || q.volume == "全册")
        }
    }

    fun pickRandomQuestion(config: LockConfig, excludeIds: Set<String> = emptySet()): Question? {
        val filtered = filterQuestions(config).filter { it.id !in excludeIds }
        val pool = filtered.ifEmpty {
            filterQuestions(config).ifEmpty {
                loadAll().filter { it.id !in excludeIds }.ifEmpty { loadAll() }
            }
        }
        return pool.randomOrNull()
    }
}
