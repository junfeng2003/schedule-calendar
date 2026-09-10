package com.example.schedulecalendar

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private lateinit var btnSelectFile: Button
    private lateinit var btnImport: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvPreview: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutPreview: LinearLayout

    private var scheduleRecords: List<ScheduleRecord> = emptyList()
    private var selectedFileName: String = ""

    // 选择JSON文件
    private val selectFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            readJsonFromUri(uri)
        }
    }

    // 日历权限请求
    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            importToCalendar()
        } else {
            Toast.makeText(this, "需要日历权限才能导入日程", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnSelectFile = findViewById(R.id.btnSelectFile)
        btnImport = findViewById(R.id.btnImport)
        tvStatus = findViewById(R.id.tvStatus)
        tvPreview = findViewById(R.id.tvPreview)
        progressBar = findViewById(R.id.progressBar)
        layoutPreview = findViewById(R.id.layoutPreview)

        btnSelectFile.setOnClickListener {
            // 打开文件选择器，选择JSON文件
            selectFileLauncher.launch(arrayOf("application/json", "*/*"))
        }

        btnImport.setOnClickListener {
            if (scheduleRecords.isEmpty()) {
                Toast.makeText(this, "请先选择JSON文件", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            checkCalendarPermissionAndImport()
        }

        btnImport.isEnabled = false
    }

    /** 读取JSON文件 */
    private fun readJsonFromUri(uri: Uri) {
        lifecycleScope.launch {
            tvStatus.text = "正在读取文件..."
            try {
                val jsonString = withContext(Dispatchers.IO) {
                    val sb = StringBuilder()
                    contentResolver.openInputStream(uri)?.use { input ->
                        BufferedReader(InputStreamReader(input)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                sb.append(line)
                            }
                        }
                    }
                    sb.toString()
                }

                scheduleRecords = parseJson(jsonString)
                selectedFileName = uri.lastPathSegment ?: "schedule.json"

                tvStatus.text = "已加载: $selectedFileName\n共 ${scheduleRecords.size} 条上课记录"
                btnImport.isEnabled = true
                showPreview()

                Toast.makeText(this@MainActivity,
                    "加载成功: ${scheduleRecords.size} 条", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                tvStatus.text = "读取失败: ${e.message}"
            }
        }
    }

    /** 解析JSON为课表记录列表 */
    private fun parseJson(jsonString: String): List<ScheduleRecord> {
        val records = mutableListOf<ScheduleRecord>()
        val jsonArray = JSONArray(jsonString)
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            records.add(
                ScheduleRecord(
                    week = obj.getInt("week"),
                    date = obj.getString("date"),
                    weekday = obj.getString("weekday"),
                    time = obj.getString("time"),
                    periods = obj.getString("periods"),
                    location = obj.getString("location"),
                    classes = obj.getString("classes"),
                    course = obj.getString("course")
                )
            )
        }
        return records
    }

    /** 显示前几条记录预览 */
    private fun showPreview() {
        val sb = StringBuilder()
        sb.append("预览（前5条）:\n\n")
        scheduleRecords.take(5).forEach { r ->
            sb.append("第${r.week}周 周${r.weekday} ${r.date}\n")
            sb.append("${r.time} ${r.course}\n")
            sb.append("${r.location}\n")
            sb.append("${r.classes}\n\n")
        }
        if (scheduleRecords.size > 5) {
            sb.append("... 共 ${scheduleRecords.size} 条\n")
        }
        tvPreview.text = sb.toString()
        layoutPreview.visibility = View.VISIBLE
    }

    /** 检查日历权限 */
    private fun checkCalendarPermissionAndImport() {
        val writeGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        val readGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED

        if (writeGranted && readGranted) {
            importToCalendar()
        } else {
            calendarPermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            )
        }
    }

    /** 导入到手机日历 */
    private fun importToCalendar() {
        // 先查找一个可用的日历账户
        val calendarId = getPrimaryCalendarId()
        if (calendarId == -1L) {
            tvStatus.text = "未找到可用日历。\n请在手机设置 → 账户 中添加 Google 账户，\n或使用手机自带的日历应用。"
            return
        }

        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        progressBar.max = scheduleRecords.size
        btnImport.isEnabled = false
        btnSelectFile.isEnabled = false
        tvStatus.text = "正在导入..."

        lifecycleScope.launch {
            var successCount = 0
            var failCount = 0

            val results = withContext(Dispatchers.IO) {
                for ((index, record) in scheduleRecords.withIndex()) {
                    try {
                        addCalendarEvent(calendarId, record)
                        successCount++
                    } catch (e: Exception) {
                        failCount++
                    }
                    // 更新进度
                    publishProgress(index + 1)
                }
                Pair(successCount, failCount)
            }

            val (success, fail) = results
            progressBar.visibility = View.GONE
            btnImport.isEnabled = true
            btnSelectFile.isEnabled = true

            tvStatus.text = "导入完成!\n成功: $success 条\n失败: $fail 条\n\n所有事件已设置提前1天提醒"
            Toast.makeText(this@MainActivity,
                "导入完成: 成功 $success 条", Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun publishProgress(current: Int) {
        withContext(Dispatchers.Main) {
            progressBar.progress = current
            tvStatus.text = "正在导入... $current/${scheduleRecords.size}"
        }
    }

    /** 添加单个日历事件 + 提前1天提醒 */
    private fun addCalendarEvent(calendarId: Long, record: ScheduleRecord) {
        val (startMillis, endMillis) = parseDateTime(record.date, record.time)

        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.TITLE, record.course)
            put(CalendarContract.Events.DESCRIPTION,
                "第${record.week}周 周${record.weekday} ${record.periods}\n班级: ${record.classes}")
            put(CalendarContract.Events.EVENT_LOCATION, record.location)
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            // 在日历中显示为已占用
            put(CalendarContract.Events.AVAILABILITY,
                CalendarContract.Events.AVAILABILITY_BUSY)
        }

        val eventUri: Uri? = contentResolver.insert(
            CalendarContract.Events.CONTENT_URI, values
        )
        val eventId = eventUri?.lastPathSegment?.toLong()
            ?: throw RuntimeException("创建事件失败")

        // 添加提前1天提醒 (1440分钟 = 24小时)
        val reminderValues = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, 1440)
            put(CalendarContract.Reminders.METHOD,
                CalendarContract.Reminders.METHOD_ALERT)
        }
        contentResolver.insert(
            CalendarContract.Reminders.CONTENT_URI, reminderValues
        )
    }

    /** 查找第一个可写入的日历账户ID */
    private fun getPrimaryCalendarId(): Long {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        // 优先查找有写权限的日历
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_OWNER}"
        contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }
        // 如果没有owner级别的，查找任何可写入的
        contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
            null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }
        return -1L
    }

    /** 解析日期和时间为起止时间戳(毫秒)
     * dateStr: "2026-10-08"
     * timeStr: "08:00-09:40"
     */
    private fun parseDateTime(dateStr: String, timeStr: String): Pair<Long, Long> {
        val parts = timeStr.split("-")
        val startTime = parts[0]   // "08:00"
        val endTime = parts[1]     // "09:40"

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val startDate = sdf.parse("$dateStr $startTime")
            ?: throw RuntimeException("解析开始时间失败: $dateStr $startTime")
        val endDate = sdf.parse("$dateStr $endTime")
            ?: throw RuntimeException("解析结束时间失败: $dateStr $endTime")

        val startCal = Calendar.getInstance().apply { time = startDate }
        val endCal = Calendar.getInstance().apply { time = endDate }

        return Pair(startCal.timeInMillis, endCal.timeInMillis)
    }
}

/** 课表记录数据类 */
data class ScheduleRecord(
    val week: Int,
    val date: String,
    val weekday: String,
    val time: String,
    val periods: String,
    val location: String,
    val classes: String,
    val course: String
)
