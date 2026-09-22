// Smart Insights card: surfaces the output of the analytics engine on the dashboard.
package com.ray.flowmeter.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.TrendingDown
import androidx.compose.material.icons.rounded.TrendingFlat
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ray.flowmeter.R
import com.ray.flowmeter.analytics.Forecast
import com.ray.flowmeter.analytics.Insights
import com.ray.flowmeter.analytics.RiskLevel
import com.ray.flowmeter.ui.theme.StaggeredEntrance
import com.ray.flowmeter.utils.SpeedFormatter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun InsightsCard(
    insights: Insights,
    modifier: Modifier = Modifier,
    staggerIndex: Int? = null,
) {
    StaggeredEntrance(index = staggerIndex) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(32.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            ),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
                        shape = CircleShape,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.label_smart_insights),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Spacer(Modifier.height(16.dp))

                if (insights.sampleDays == 0 && insights.dailyForecast == null) {
                    Text(
                        text = stringResource(R.string.insight_learning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }

                insights.dailyForecast?.let { forecast ->
                    ForecastBlock(
                        title = stringResource(R.string.insight_projected_today),
                        forecast = forecast,
                    )
                }

                insights.monthlyForecast?.let { forecast ->
                    if (forecast.projectedBytes > 0) {
                        Spacer(Modifier.height(14.dp))
                        ForecastBlock(
                            title = stringResource(R.string.insight_projected_month),
                            forecast = forecast,
                            compact = true,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Trend
                val trend = insights.trendPercent
                InsightRow(
                    icon = when {
                        trend > 5 -> Icons.Rounded.TrendingUp
                        trend < -5 -> Icons.Rounded.TrendingDown
                        else -> Icons.Rounded.TrendingFlat
                    },
                    tint = when {
                        trend > 20 -> MaterialTheme.colorScheme.error
                        trend < -5 -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    text = when {
                        trend > 5 -> stringResource(R.string.insight_trend_up, trend)
                        trend < -5 -> stringResource(R.string.insight_trend_down, -trend)
                        else -> stringResource(R.string.insight_trend_flat)
                    },
                )

                insights.busiestHour?.let { hour ->
                    InsightRow(
                        icon = Icons.Rounded.Schedule,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = stringResource(R.string.insight_busiest_hour, formatHour(hour)),
                    )
                }

                if (insights.weekdayAverageBytes > 0 || insights.weekendAverageBytes > 0) {
                    InsightRow(
                        icon = Icons.Rounded.Insights,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = stringResource(
                            R.string.insight_weekday_weekend,
                            SpeedFormatter.formatUsage(insights.weekdayAverageBytes),
                            SpeedFormatter.formatUsage(insights.weekendAverageBytes),
                        ),
                    )
                }

                AnimatedVisibility(visible = insights.backgroundLeakBytes > 0) {
                    InsightRow(
                        icon = Icons.Rounded.BatteryAlert,
                        tint = MaterialTheme.colorScheme.error,
                        text = stringResource(
                            R.string.insight_leak,
                            SpeedFormatter.formatUsage(insights.backgroundLeakBytes),
                        ),
                    )
                }

                insights.anomalies.lastOrNull()?.let { anomaly ->
                    InsightRow(
                        icon = Icons.Rounded.WarningAmber,
                        tint = MaterialTheme.colorScheme.error,
                        text = stringResource(
                            R.string.insight_anomaly,
                            formatDay(anomaly.epochMillis),
                            SpeedFormatter.formatUsage(anomaly.observedBytes),
                            SpeedFormatter.formatUsage(anomaly.expectedBytes),
                        ),
                    )
                }

                insights.suggestedDailyLimitBytes?.let { suggestion ->
                    InsightRow(
                        icon = Icons.Rounded.AutoAwesome,
                        tint = MaterialTheme.colorScheme.tertiary,
                        text = stringResource(
                            R.string.insight_suggest_daily,
                            SpeedFormatter.formatUsage(suggestion),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ForecastBlock(
    title: String,
    forecast: Forecast,
    compact: Boolean = false,
) {
    val riskColor = riskColor(forecast.risk)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = SpeedFormatter.formatUsage(forecast.projectedBytes),
                style = if (compact) {
                    MaterialTheme.typography.titleLarge
                } else {
                    MaterialTheme.typography.headlineMedium
                },
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(
                    R.string.insight_range,
                    SpeedFormatter.formatUsage(forecast.lowerBytes),
                    SpeedFormatter.formatUsage(forecast.upperBytes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Surface(
            color = riskColor.copy(alpha = 0.12f),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.insight_confidence,
                    (forecast.confidence * 100).toInt(),
                ),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = riskColor,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }

    Spacer(Modifier.height(10.dp))

    val progress by animateFloatAsState(
        targetValue = forecast.confidence.coerceIn(0f, 1f),
        label = "insightConfidence",
    )
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape),
        color = riskColor,
        trackColor = riskColor.copy(alpha = 0.15f),
    )

    Spacer(Modifier.height(8.dp))

    Text(
        text = riskLabel(forecast.risk),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = riskColor,
    )

    forecast.exhaustionAtMillis?.let { at ->
        Text(
            text = stringResource(R.string.insight_exhaustion_at, formatTime(at)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (forecast.risk == RiskLevel.WATCH || forecast.risk == RiskLevel.AT_RISK) {
        Text(
            text = stringResource(
                R.string.insight_budget_per_hour,
                SpeedFormatter.formatUsage(forecast.safeBytesPerHourRemaining),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InsightRow(icon: ImageVector, tint: Color, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun riskColor(risk: RiskLevel): Color = when (risk) {
    RiskLevel.SAFE -> MaterialTheme.colorScheme.primary
    RiskLevel.ON_TRACK -> MaterialTheme.colorScheme.tertiary
    RiskLevel.WATCH -> MaterialTheme.colorScheme.secondary
    RiskLevel.AT_RISK, RiskLevel.EXCEEDED -> MaterialTheme.colorScheme.error
}

@Composable
private fun riskLabel(risk: RiskLevel): String = when (risk) {
    RiskLevel.SAFE -> stringResource(R.string.insight_risk_safe)
    RiskLevel.ON_TRACK -> stringResource(R.string.insight_risk_on_track)
    RiskLevel.WATCH -> stringResource(R.string.insight_risk_watch)
    RiskLevel.AT_RISK -> stringResource(R.string.insight_risk_at_risk)
    RiskLevel.EXCEEDED -> stringResource(R.string.insight_risk_exceeded)
}

private fun formatHour(hour: Int): String {
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
        set(Calendar.MINUTE, 0)
    }
    return SimpleDateFormat("h a", Locale.getDefault()).format(cal.time)
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))

private fun formatDay(millis: Long): String =
    SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(millis))
