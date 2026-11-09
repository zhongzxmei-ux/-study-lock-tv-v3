package com.studylock.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.studylock.tv.audio.TtsAndSoundManager
import com.studylock.tv.config.LockConfig
import com.studylock.tv.config.PraiseMode
import com.studylock.tv.data.QuestionRepository
import com.studylock.tv.service.GuardForegroundService
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var config: LockConfig
    private lateinit var repo: QuestionRepository
    private lateinit var ttsManager: TtsAndSoundManager

    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val sec = intent?.getIntExtra(GuardForegroundService.EXTRA_REMAINING_SEC, 0) ?: return
            val mode = intent.getStringExtra(GuardForegroundService.EXTRA_STATUS_MODE) ?: "RUNNING"
            val min = sec / 60
            val rem = sec % 60
            val headline = findViewById<TextView>(R.id.tvStatusHeadline)
            headline.text = when (mode) {
                "TEMP_PAUSE" -> "临时停用中 · 到点自动恢复"
                "OUTSIDE_SCHEDULE" -> "当前不在设定守护时段内"
                else -> String.format("%02d:%02d 后将弹出答题", min, rem)
            }
            refreshStatusPills()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        config = LockConfig(this)
        repo = QuestionRepository(this)
        ttsManager = TtsAndSoundManager(this)

        bindActions()
        refreshUi()
        promptPinIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(GuardForegroundService.BROADCAST_TICK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(tickReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(tickReceiver, filter)
        }
        refreshUi()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(tickReceiver)
        } catch (_: Exception) {
        }
    }

    private fun promptPinIfNeeded() {
        if (!config.guardEnabled) return
        val input = EditText(this).apply { hint = "输入家长密码（默认 0000）" }
        AlertDialog.Builder(this)
            .setTitle("家长验证")
            .setMessage("守护已开启，请输入数字密码进入控制台")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("确认") { _, _ ->
                if (input.text.toString() != config.parentalPin) {
                    Toast.makeText(this, "密码错误，已退出", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton("退出") { _, _ -> finish() }
            .show()
    }

    private fun bindActions() {
        // 1. 顶部守护开关
        findViewById<Button>(R.id.btnToggleGuard).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予「显示在其他应用上层」权限", Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                return@setOnClickListener
            }
            val next = !config.guardEnabled
            config.guardEnabled = next
            val svc = Intent(this, GuardForegroundService::class.java).apply {
                action = if (next) GuardForegroundService.ACTION_START else GuardForegroundService.ACTION_STOP
            }
            if (next) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
                else startService(svc)
            } else {
                startService(svc)
            }
            refreshUi()
        }

        // 2. 每轮观看时长
        mapOf(
            R.id.btnDur15 to 15, R.id.btnDur20 to 20, R.id.btnDur30 to 30,
            R.id.btnDur45 to 45, R.id.btnDur60 to 60
        ).forEach { (id, mins) ->
            findViewById<Button>(id).setOnClickListener {
                config.watchMinutes = mins
                refreshUi()
            }
        }
        findViewById<Button>(R.id.btnDurMinus).setOnClickListener {
            config.watchMinutes = (config.watchMinutes - 1).coerceAtLeast(1)
            refreshUi()
        }
        findViewById<Button>(R.id.btnDurPlus).setOnClickListener {
            config.watchMinutes = (config.watchMinutes + 1).coerceAtMost(600)
            refreshUi()
        }

        // 3. 每次弹窗答题数量
        mapOf(
            R.id.btnQ1 to 1, R.id.btnQ2 to 2, R.id.btnQ3 to 3, R.id.btnQ5 to 5
        ).forEach { (id, cnt) ->
            findViewById<Button>(id).setOnClickListener {
                config.questionsPerRound = cnt
                refreshUi()
            }
        }
        findViewById<Button>(R.id.btnQMinus).setOnClickListener {
            config.questionsPerRound = (config.questionsPerRound - 1).coerceAtLeast(1)
            refreshUi()
        }
        findViewById<Button>(R.id.btnQPlus).setOnClickListener {
            config.questionsPerRound = (config.questionsPerRound + 1).coerceAtMost(10)
            refreshUi()
        }

        // 4. 答错讲解后等待秒数
        mapOf(
            R.id.btnWait0 to 0, R.id.btnWait30 to 30, R.id.btnWait60 to 60,
            R.id.btnWait90 to 90, R.id.btnWait120 to 120
        ).forEach { (id, sec) ->
            findViewById<Button>(id).setOnClickListener {
                config.wrongWaitSeconds = sec
                refreshUi()
            }
        }
        findViewById<Button>(R.id.btnWaitMinus).setOnClickListener {
            config.wrongWaitSeconds = (config.wrongWaitSeconds - 5).coerceAtLeast(0)
            refreshUi()
        }
        findViewById<Button>(R.id.btnWaitPlus).setOnClickListener {
            config.wrongWaitSeconds = (config.wrongWaitSeconds + 5).coerceAtMost(300)
            refreshUi()
        }

        // 5. 答对鼓励
        findViewById<Button>(R.id.btnPraiseOff).setOnClickListener {
            config.praiseMode = PraiseMode.OFF; refreshUi()
        }
        findViewById<Button>(R.id.btnPraiseSfx).setOnClickListener {
            config.praiseMode = PraiseMode.SFX_ONLY; refreshUi()
        }
        findViewById<Button>(R.id.btnPraiseVoice).setOnClickListener {
            config.praiseMode = PraiseMode.SFX_AND_VOICE; refreshUi()
        }
        findViewById<Button>(R.id.btnTestPraise).setOnClickListener {
            ttsManager.playPraise(PraiseMode.SFX_AND_VOICE)
        }

        // 6. 低年级注音 / 朗读 / 暂停媒体 / 严格模式 / 答错讲解开关
        findViewById<Button>(R.id.btnTogglePinyin).setOnClickListener {
            config.pinyinEnabled = !config.pinyinEnabled; refreshUi()
        }
        findViewById<Button>(R.id.btnTogglePauseMedia).setOnClickListener {
            config.pauseMediaOnQuiz = !config.pauseMediaOnQuiz; refreshUi()
        }
        findViewById<Button>(R.id.btnToggleRead12).setOnClickListener {
            config.readAloudGrade12 = !config.readAloudGrade12; refreshUi()
        }
        findViewById<Button>(R.id.btnTestRead12).setOnClickListener {
            ttsManager.speakText("请听题。上的反义词是？选项是：A 果断，B 冷，C 下，D 老实。")
        }
        findViewById<Button>(R.id.btnToggleStrict).setOnClickListener {
            config.strictMode = !config.strictMode; refreshUi()
        }
        findViewById<Button>(R.id.btnToggleExplainAloud).setOnClickListener {
            config.explainAloudOnWrong = !config.explainAloudOnWrong; refreshUi()
        }
        findViewById<Button>(R.id.btnTestExplain).setOnClickListener {
            ttsManager.speakText("正确答案是 C，下。解题思路：上和下是一对反义词，成对记忆。")
        }

        // 7. 定时开启 / 关闭学习锁
        findViewById<Button>(R.id.btnScheduleToggle).setOnClickListener {
            config.scheduleAllDay = !config.scheduleAllDay; refreshUi()
        }
        findViewById<Button>(R.id.btnStartMinus).setOnClickListener {
            config.scheduleStartMinutes -= 30; refreshUi()
        }
        findViewById<Button>(R.id.btnStartPlus).setOnClickListener {
            config.scheduleStartMinutes += 30; refreshUi()
        }
        findViewById<Button>(R.id.btnEndMinus).setOnClickListener {
            config.scheduleEndMinutes -= 30; refreshUi()
        }
        findViewById<Button>(R.id.btnEndPlus).setOnClickListener {
            config.scheduleEndMinutes += 30; refreshUi()
        }

        // 8. 临时停用
        mapOf(
            R.id.btnTemp15 to 15, R.id.btnTemp30 to 30, R.id.btnTemp60 to 60
        ).forEach { (id, mins) ->
            findViewById<Button>(id).setOnClickListener {
                config.tempPauseUntilMillis = System.currentTimeMillis() + mins * 60_000L
                config.addLog("临时停用 $mins 分钟")
                Toast.makeText(this, "已临时停用 $mins 分钟", Toast.LENGTH_SHORT).show()
                refreshUi()
            }
        }
        findViewById<Button>(R.id.btnTempResume).setOnClickListener {
            config.tempPauseUntilMillis = 0L
            config.addLog("已立即恢复守护")
            Toast.makeText(this, "已恢复守护", Toast.LENGTH_SHORT).show()
            refreshUi()
        }

        // 9. 出题科目勾选
        mapOf(
            R.id.btnSubjPinyin to "拼音题",
            R.id.btnSubjCn to "语文",
            R.id.btnSubjMath to "数学",
            R.id.btnSubjEn to "英语",
            R.id.btnSubjPhy to "物理"
        ).forEach { (id, subj) ->
            findViewById<Button>(id).setOnClickListener {
                val cur = config.enabledSubjects.toMutableSet()
                if (subj in cur) {
                    if (cur.size <= 1) {
                        Toast.makeText(this, "至少保留一门科目", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    cur.remove(subj)
                } else {
                    cur.add(subj)
                }
                config.enabledSubjects = cur
                refreshUi()
            }
        }

        // 10. 操作区
        findViewById<Button>(R.id.btnTestQuizNow).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val svc = Intent(this, GuardForegroundService::class.java).apply {
                action = GuardForegroundService.ACTION_TEST_NOW
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
            else startService(svc)
        }

        findViewById<Button>(R.id.btnResetTimer).setOnClickListener {
            startService(Intent(this, GuardForegroundService::class.java).apply {
                action = GuardForegroundService.ACTION_RESET_TIMER
            })
            Toast.makeText(this, "计时已重置为 ${config.watchMinutes} 分钟", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnQuestionBank).setOnClickListener { openQuestionBankDialog() }
        findViewById<Button>(R.id.btnChangePin).setOnClickListener { openChangePinDialog() }
        findViewById<Button>(R.id.btnBatterySetup).setOnClickListener { requestBatteryWhitelist() }
        findViewById<Button>(R.id.btnViewLogs).setOnClickListener { openLogsDialog() }
        findViewById<Button>(R.id.btnUsageGuide).setOnClickListener { openGuideDialog() }
    }

    private fun openQuestionBankDialog() {
        val grades = (1..9).map { "${it}年级" }.toTypedArray()
        val curGrades = config.enabledGrades
        val checked = BooleanArray(9) { (it + 1).toString() in curGrades }

        AlertDialog.Builder(this)
            .setTitle("题库管理（共 ${repo.getTotalCount()} 题 · 勾选启用年级）")
            .setMultiChoiceItems(grades, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("保存") { _, _ ->
                val selected = (0 until 9).filter { checked[it] }.map { (it + 1).toString() }.toSet()
                if (selected.isEmpty()) {
                    Toast.makeText(this, "请至少保留一个年级", Toast.LENGTH_SHORT).show()
                } else {
                    config.enabledGrades = selected
                    refreshUi()
                }
            }
            .setNeutralButton("上下册 / URL导入") { _, _ ->
                openVolumeAndUrlDialog()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openVolumeAndUrlDialog() {
        val volumes = arrayOf("上册", "下册", "通用", "全册")
        val curVol = config.enabledVolumes
        val checked = BooleanArray(volumes.size) { volumes[it] in curVol }

        AlertDialog.Builder(this)
            .setTitle("选择册别或在线导入题库")
            .setMultiChoiceItems(volumes, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("保存册别") { _, _ ->
                val sel = volumes.indices.filter { checked[it] }.map { volumes[it] }.toSet()
                if (sel.isNotEmpty()) config.enabledVolumes = sel
                refreshUi()
            }
            .setNeutralButton("URL / U盘导入") { _, _ ->
                openUrlImportDialog()
            }
            .show()
    }

    private fun openUrlImportDialog() {
        val input = EditText(this).apply {
            hint = "输入 JSON 题库链接（留空则扫描 U 盘/Download）"
        }
        AlertDialog.Builder(this)
            .setTitle("导入人教版扩展题库")
            .setMessage("支持填入符合格式的在线 JSON 题库直链；若已将 study_lock_questions.json 放入电视 Download 目录，直接点确定扫描即可。")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isEmpty()) {
                    repo.reload()
                    refreshUi()
                    Toast.makeText(this, "已重新扫描本地题库，当前共 ${repo.getTotalCount()} 题", Toast.LENGTH_SHORT).show()
                } else {
                    thread {
                        val res = repo.importFromUrl(url)
                        runOnUiThread {
                            res.onSuccess { cnt ->
                                refreshUi()
                                Toast.makeText(this, "成功导入 $cnt 道题！", Toast.LENGTH_LONG).show()
                            }.onFailure { err ->
                                Toast.makeText(this, "导入失败：${err.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openChangePinDialog() {
        val input = EditText(this).apply { hint = "输入 4~6 位数字新密码" }
        AlertDialog.Builder(this)
            .setTitle("修改数字密码（当前：${config.parentalPin}）")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val pin = input.text.toString().trim()
                if (pin.length in 4..6 && pin.all { it.isDigit() }) {
                    config.parentalPin = pin
                    config.addLog("家长已修改数字密码")
                    Toast.makeText(this, "密码已更新", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "请输入 4~6 位纯数字", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun requestBatteryWhitelist() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } catch (_: Exception) {
            Toast.makeText(this, "请在小米电视「电视管家 → 应用管理」中开启自启动与省电无限制", Toast.LENGTH_LONG).show()
        }
    }

    private fun openLogsDialog() {
        val logs = config.getLogs()
        val msg = if (logs.isEmpty()) "暂无运行记录" else logs.joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle("运行记录")
            .setMessage(msg)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun openGuideDialog() {
        AlertDialog.Builder(this)
            .setTitle("小米电视使用须知")
            .setMessage(
                "1. 本应用使用「悬浮窗」将答题页面盖在视频上方，请务必授予悬浮窗权限。\n" +
                    "2. 请在小米电视「电视管家」中开启本应用的「自启动」并将省电策略设为「无限制」。\n" +
                    "3. 如需接入更多人教版题目，可将 study_lock_questions.json 放入电视 Download 目录或在题库管理中输入 JSON 链接。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun formatMin(totalMin: Int): String =
        String.format("%02d:%02d", (totalMin / 60) % 24, totalMin % 60)

    private fun refreshStatusPills() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val inWhitelist = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || pm.isIgnoringBatteryOptimizations(packageName)
        val gText = if (config.guardEnabled) "守护 已开启" else "守护 已关闭"
        val sText = if (GuardForegroundService.isRunning) "后台服务 运行中" else "后台服务 未运行"
        val bText = if (inWhitelist) "电池白名单 已加入" else "未加电池白名单"
        findViewById<TextView>(R.id.tvStatusPills).text = "$gText  ·  $sText  ·  $bText"
    }

    private fun refreshUi() {
        findViewById<TextView>(R.id.tvHeaderBadge).text =
            "可用 ${repo.getMatchingCount(config)}/${repo.getTotalCount()} 题 · 电视版"

        val enabled = config.guardEnabled
        findViewById<Button>(R.id.btnToggleGuard).text = if (enabled) "暂停守护" else "开启守护"
        findViewById<TextView>(R.id.tvGuardSub).text =
            if (enabled) "守护已开启 · 到点自动全屏弹题" else "守护已关闭 · 点击右侧按钮开启"
        if (!enabled) {
            findViewById<TextView>(R.id.tvStatusHeadline).text = "守护未开启"
        }
        refreshStatusPills()

        findViewById<TextView>(R.id.tvCustomDuration).text = "${config.watchMinutes} 分钟"
        findViewById<TextView>(R.id.tvCustomQ).text = "${config.questionsPerRound} 道"
        findViewById<TextView>(R.id.tvCustomWait).text = "${config.wrongWaitSeconds} 秒"

        findViewById<TextView>(R.id.tvCurrentSummary).text =
            "每轮 ${config.watchMinutes} 分钟 · 每次 ${config.questionsPerRound} 题 · 答错等待 ${config.wrongWaitSeconds} 秒"

        findViewById<Button>(R.id.btnTogglePinyin).text =
            "一二年级注音：" + if (config.pinyinEnabled) "开启" else "关闭"
        findViewById<Button>(R.id.btnTogglePauseMedia).text =
            "答题暂停播放：" + if (config.pauseMediaOnQuiz) "开启" else "关闭"
        findViewById<Button>(R.id.btnToggleRead12).text =
            "一二年级朗读：" + if (config.readAloudGrade12) "开启" else "关闭"
        findViewById<Button>(R.id.btnToggleStrict).text =
            "严格模式：" + if (config.strictMode) "开启" else "关闭"
        findViewById<Button>(R.id.btnToggleExplainAloud).text =
            "答错语音讲解：" + if (config.explainAloudOnWrong) "开启" else "关闭"

        findViewById<Button>(R.id.btnScheduleToggle).text =
            if (config.scheduleAllDay) "当前：全天生效 (点击改按时段)" else "当前：按时段生效 (点击改全天)"
        findViewById<TextView>(R.id.tvScheduleWindow).text =
            "生效时段：${formatMin(config.scheduleStartMinutes)} ~ ${formatMin(config.scheduleEndMinutes)}"

        val tempMin = ((config.tempPauseUntilMillis - System.currentTimeMillis()) / 60_000L).toInt()
        findViewById<TextView>(R.id.tvTempPauseStatus).text =
            if (tempMin > 0) "正在临时停用，约 ${tempMin + 1} 分钟后自动恢复" else "当前未临时停用"

        val subjs = config.enabledSubjects
        findViewById<Button>(R.id.btnSubjPinyin).text = (if ("拼音题" in subjs) "✓ " else "") + "拼音题"
        findViewById<Button>(R.id.btnSubjCn).text = (if ("语文" in subjs) "✓ " else "") + "语文"
        findViewById<Button>(R.id.btnSubjMath).text = (if ("数学" in subjs) "✓ " else "") + "数学"
        findViewById<Button>(R.id.btnSubjEn).text = (if ("英语" in subjs) "✓ " else "") + "英语"
        findViewById<Button>(R.id.btnSubjPhy).text = (if ("物理" in subjs) "✓ " else "") + "物理"
    }

    override fun onDestroy() {
        ttsManager.release()
        super.onDestroy()
    }
}
