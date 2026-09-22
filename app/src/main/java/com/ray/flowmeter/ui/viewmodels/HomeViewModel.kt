package com.ray.flowmeter.ui.viewmodels

import android.content.Context
import android.net.NetworkCapabilities
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ray.flowmeter.data.UserPreferencesRepository
import com.ray.flowmeter.R
import com.ray.flowmeter.ui.components.ChartType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import com.ray.flowmeter.utils.NetworkStatsCache
import com.ray.flowmeter.utils.SpeedFormatter
import com.ray.flowmeter.analytics.Insights
import com.ray.flowmeter.analytics.InsightsEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
// ViewModel for the Home screen, managing usage stats and chart data
class HomeViewModel(
    private val applicationContext: Context,
    private val repository: UserPreferencesRepository,
) : ViewModel() {
    var isWidgetsOpen by mutableStateOf(false)

    var downloadReceived by mutableStateOf("0 B")
    var uploadSent by mutableStateOf("0 B")

    var dailyUsage by mutableStateOf("0 B")
    var dailyMobileUsage by mutableStateOf("0 B")
    var dailyWifiUsage by mutableStateOf("0 B")
    var monthlyUsage by mutableStateOf("0 B")
    var monthlyMobileUsage by mutableStateOf("0 B")
    var monthlyWifiUsage by mutableStateOf("0 B")

    var dailyMobileUsageBytes by mutableLongStateOf(0L)
    var dailyWifiUsageBytes by mutableLongStateOf(0L)
    var projectedDailyMobileBytes by mutableLongStateOf(0L)
    var dailyMobileLimitBytes by mutableLongStateOf(0L)
    var isDataLimitEnabled by mutableStateOf(value = false)
    private var monthlyResetDay = 1

    var weeklyMobileData by mutableStateOf(List(7) { 0f })
    var weeklyWifiData by mutableStateOf(List(7) { 0f })
    var weekDays by mutableStateOf(listOf("", "", "", "", "", "", ""))
    var weeklyDates by mutableStateOf<List<Calendar>>(emptyList())
    var weeklyYAxisLabels by mutableStateOf(listOf("2 GB", "1.5 GB", "1 GB", "0.5 GB", "0 GB"))

    private var _selectedChartType = mutableStateOf(ChartType.COMBINED)
    val selectedChartType: State<ChartType> = _selectedChartType

    private val insightsEngine = InsightsEngine(applicationContext)

    private val _insights = MutableStateFlow(Insights.EMPTY)
    /** Latest analytics snapshot: forecasts, anomalies, suggestions and habit profile. */
    val insights: StateFlow<Insights> = _insights.asStateFlow()

    private var insightsJob: Job? = null

    init {
        viewModelScope.launch {
            repository.usageChartType.collectLatest { typeStr ->
                _selectedChartType.value = try {
                    ChartType.valueOf(typeStr)
                } catch (_: Exception) {
                    ChartType.COMBINED
                }
            }
        }

        viewModelScope.launch {
            repository.resetTimeHour.collect { updateTotalUsage() }
        }
        viewModelScope.launch {
            repository.resetTimeMinute.collect { updateTotalUsage() }
        }
        viewModelScope.launch {
            repository.monthlyResetDay.collect {
                monthlyResetDay = it
                updateTotalUsage()
            }
        }
        viewModelScope.launch {
            repository.language.collect {
                updateTotalUsage()
            }
        }
    }

    fun updateChartType(type: ChartType) {
        _selectedChartType.value = type
        viewModelScope.launch {
            repository.saveUsageChartType(type.name)
        }
    }

    private var updateJob: Job? = null

    // Refresh all usage statistics for the UI
    fun updateTotalUsage() {
        updateJob?.cancel()
        updateJob = viewModelScope.launch(Dispatchers.IO) {
            val resetHour = repository.resetTimeHour.first()
            val resetMinute = repository.resetTimeMinute.first()

            val calendar = Calendar.getInstance()

            val currentTime = System.currentTimeMillis()

            calendar[Calendar.HOUR_OF_DAY] = resetHour
            calendar[Calendar.MINUTE] = resetMinute
            calendar[Calendar.SECOND] = 0
            calendar[Calendar.MILLISECOND] = 0

            var startTimeDay = calendar.timeInMillis
            if (currentTime < startTimeDay) {
                calendar.add(Calendar.DAY_OF_YEAR, -1)
                startTimeDay = calendar.timeInMillis
            }

            // Correct Monthly Start Time based on Reset Time and Custom Reset Day
            val monthCalendar = (calendar.clone() as Calendar)
            val clampedDay = monthlyResetDay.coerceAtMost(monthCalendar.getActualMaximum(Calendar.DAY_OF_MONTH))
            monthCalendar[Calendar.DAY_OF_MONTH] = clampedDay
            if (currentTime < monthCalendar.timeInMillis) {
                monthCalendar.add(Calendar.MONTH, -1)
                val prevMaxDay = monthCalendar.getActualMaximum(Calendar.DAY_OF_MONTH)
                monthCalendar[Calendar.DAY_OF_MONTH] = monthlyResetDay.coerceAtMost(prevMaxDay)
            }
            val startTimeMonth = monthCalendar.timeInMillis

            val dailyBytesArray = getDeviceUsage(startTimeDay, currentTime)
            val dailyBytes = dailyBytesArray[0] + dailyBytesArray[1]

            val mDaily = getSumUsageForTransport(NetworkCapabilities.TRANSPORT_CELLULAR, startTimeDay, currentTime)
            val wDaily = getSumUsageForTransport(NetworkCapabilities.TRANSPORT_WIFI, startTimeDay, currentTime)

            val timeElapsedMillis = (currentTime - startTimeDay).coerceAtLeast(1000L)
            val dayMillis = 24 * 60 * 60 * 1000L
            val projectedM = ((mDaily.toDouble() / timeElapsedMillis) * dayMillis).toLong()

            val dataLimitEnabled = repository.dataDailyLimitEnabled.first()
            val dataLimit = repository.dataDailyLimit.first()

            val monthlyBytesArray = getDeviceUsage(startTimeMonth, currentTime)
            val monthlyBytes = monthlyBytesArray[0] + monthlyBytesArray[1]

            val mMonthly = getSumUsageForTransport(NetworkCapabilities.TRANSPORT_CELLULAR, startTimeMonth, currentTime)
            val wMonthly = getSumUsageForTransport(NetworkCapabilities.TRANSPORT_WIFI, startTimeMonth, currentTime)

            val rawMobileBytes = mutableListOf<Long>()
            val rawWifiBytes = mutableListOf<Long>()
            val daysList = mutableListOf<String>()
            val datesList = mutableListOf<Calendar>()
            val dateFormat = SimpleDateFormat("EEE", Locale.getDefault())

            var highestDailyUsage = 0L

            for (i in 6 downTo 0) {
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_YEAR, -i)
                
                cal[Calendar.HOUR_OF_DAY] = resetHour
                cal[Calendar.MINUTE] = resetMinute
                cal[Calendar.SECOND] = 0
                cal[Calendar.MILLISECOND] = 0
                
                // If it's currently before today's reset time, then "today" started yesterday
                // and "N days ago" also shifted.
                val baseTodayStart = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, resetHour)
                    set(Calendar.MINUTE, resetMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                
                if (currentTime < baseTodayStart) {
                    cal.add(Calendar.DAY_OF_YEAR, -1)
                }

                datesList.add(cal.clone() as Calendar)
                val startOfDay = cal.timeInMillis
                val endOfDay = if (i == 0) currentTime else startOfDay + (24 * 60 * 60 * 1000L) - 1

                if (i == 0) {
                    daysList.add(applicationContext.getString(R.string.label_today))
                } else {
                    daysList.add(dateFormat.format(cal.time))
                }

                val mBytes = getSumUsageForTransport(NetworkCapabilities.TRANSPORT_CELLULAR, startOfDay, endOfDay)
                val wBytes = getSumUsageForTransport(NetworkCapabilities.TRANSPORT_WIFI, startOfDay, endOfDay)

                rawMobileBytes.add(mBytes)
                rawWifiBytes.add(wBytes)

                val dayTotal = mBytes + wBytes
                if (dayTotal > highestDailyUsage) {
                    highestDailyUsage = dayTotal
                }
            }

            // Scale the chart ceiling dynamically based on the highest daily usage recorded.
            val possibleCeilings = listOf(
                100 * 1024 * 1024L,
                250 * 1024 * 1024L,
                500 * 1024 * 1024L,
                750 * 1024 * 1024L,
                1024 * 1024 * 1024L,
                1536 * 1024 * 1024L,
                2 * 1024 * 1024 * 1024L,
                3 * 1024 * 1024 * 1024L,
                4 * 1024 * 1024 * 1024L,
                5 * 1024 * 1024 * 1024L,
                6 * 1024 * 1024 * 1024L,
                8 * 1024 * 1024 * 1024L,
                10 * 1024 * 1024 * 1024L,
                12 * 1024 * 1024 * 1024L,
                15 * 1024 * 1024 * 1024L,
                20 * 1024 * 1024 * 1024L,
                25 * 1024 * 1024 * 1024L,
                30 * 1024 * 1024 * 1024L,
                40 * 1024 * 1024 * 1024L,
                50 * 1024 * 1024 * 1024L,
                75 * 1024 * 1024 * 1024L,
                100 * 1024 * 1024 * 1024L
            )

            val minCeiling = 100 * 1024 * 1024L
            val rawCeiling = highestDailyUsage.coerceAtLeast(minCeiling)
            val chartCeilingBytes = possibleCeilings.find { it >= rawCeiling }?.toDouble()
                ?: (rawCeiling * 1.1)

            val mobileList = rawMobileBytes.map {
                (it.toDouble() / chartCeilingBytes).toFloat().coerceIn(0f, 1f)
            }
            val wifiList = rawWifiBytes.map {
                (it.toDouble() / chartCeilingBytes).toFloat().coerceIn(0f, 1f)
            }

            val labelsList = listOf(
                formatDataUsage(chartCeilingBytes.toLong()),
                formatDataUsage((chartCeilingBytes * 0.75).toLong()),
                formatDataUsage((chartCeilingBytes * 0.5).toLong()),
                formatDataUsage((chartCeilingBytes * 0.25).toLong()),
                formatDataUsage(0L),
            )

            withContext(Dispatchers.Main) {
                dailyUsage = formatDataUsage(dailyBytes)
                dailyMobileUsage = formatDataUsage(mDaily)
                dailyWifiUsage = formatDataUsage(wDaily)
                monthlyUsage = formatDataUsage(monthlyBytes)
                monthlyMobileUsage = formatDataUsage(mMonthly)
                monthlyWifiUsage = formatDataUsage(wMonthly)

                dailyMobileUsageBytes = mDaily
                dailyWifiUsageBytes = wDaily
                projectedDailyMobileBytes = projectedM
                dailyMobileLimitBytes = dataLimit
                isDataLimitEnabled = dataLimitEnabled

                downloadReceived = formatDataUsage(dailyBytesArray[0])
                uploadSent = formatDataUsage(dailyBytesArray[1])

                weeklyMobileData = mobileList
                weeklyWifiData = wifiList
                weekDays = daysList
                weeklyDates = datesList
                weeklyYAxisLabels = labelsList
            }

            refreshInsights(
                dailyUsedBytes = dailyBytes,
                dailyPeriodStart = startTimeDay,
                dailyLimitBytes = if (dataLimitEnabled) dataLimit else 0L,
                monthlyUsedBytes = monthlyBytes,
                monthlyPeriodStart = startTimeMonth,
            )
        }
    }

    /**
     * Recompute the analytics snapshot in the background.
     *
     * Runs after the fast usage numbers have already been published to the UI, so the dashboard
     * never waits on history ingestion or forecasting.
     */
    private fun refreshInsights(
        dailyUsedBytes: Long,
        dailyPeriodStart: Long,
        dailyLimitBytes: Long,
        monthlyUsedBytes: Long,
        monthlyPeriodStart: Long,
    ) {
        insightsJob?.cancel()
        insightsJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                insightsEngine.ingestCompletedHours()

                val monthlyLimitEnabled = repository.dataMonthlyLimitEnabled.first()
                val monthlyLimit = if (monthlyLimitEnabled) repository.dataMonthlyLimit.first() else 0L

                _insights.value = insightsEngine.compute(
                    dailyUsedBytes = dailyUsedBytes,
                    dailyPeriodStart = dailyPeriodStart,
                    dailyLimitBytes = dailyLimitBytes,
                    monthlyUsedBytes = monthlyUsedBytes,
                    monthlyPeriodStart = monthlyPeriodStart,
                    monthlyLimitBytes = monthlyLimit,
                )
            } catch (_: Exception) {
                // Insights are additive; a failure here must never break the dashboard.
            }
        }
    }

    // Queries total network usage for a transport, served from the shared coalescing cache so
    // the Home screen, the widgets and the monitoring service never duplicate a binder call.
    private suspend fun getSumUsageForTransport(
        transportType: Int,
        startTime: Long,
        endTime: Long,
    ): Long = NetworkStatsCache.summary(applicationContext, transportType, startTime, endTime).total

    private suspend fun getDeviceUsage(
        startTime: Long,
        endTime: Long,
    ): LongArray {
        val combined = NetworkStatsCache.combined(applicationContext, startTime, endTime)
        return longArrayOf(combined.rx, combined.tx)
    }

    private fun formatDataUsage(bytes: Long): String = SpeedFormatter.formatUsage(bytes)
}
