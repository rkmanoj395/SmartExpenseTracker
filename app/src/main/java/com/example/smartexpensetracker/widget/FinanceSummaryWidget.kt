package com.example.smartexpensetracker.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.example.smartexpensetracker.data.AccountSummaryEntity
import com.example.smartexpensetracker.data.AppDatabase
import com.example.smartexpensetracker.ui.MainActivity

class FinanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FinanceSummaryWidget()
}

class FinanceSummaryWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dao = AppDatabase.getInstance(context).transactionDao()
        val summary = dao.getAccountSummarySnapshot() ?: AccountSummaryEntity()
        val uncategorizedCount = dao.getUncategorizedCount()

        provideContent {
            GlanceTheme {
                WidgetContent(
                    bankBalance = summary.cumulativeBankBalance,
                    creditCardDue = summary.creditCardDue,
                    uncategorizedCount = uncategorizedCount
                )
            }
        }
    }

    @Composable
    private fun WidgetContent(
        bankBalance: Double,
        creditCardDue: Double,
        uncategorizedCount: Int
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(Color(0xFF0F172A)))
                .padding(14.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "FINANCE TRACKER",
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF94A3B8)),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Spacer(modifier = GlanceModifier.defaultWeight())
                if (uncategorizedCount > 0) {
                    Text(
                        text = "$uncategorizedCount Need Review",
                        style = TextStyle(
                            color = ColorProvider(Color(0xFFFBBF24)),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }

            Spacer(modifier = GlanceModifier.height(10.dp))

            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Cumulative Bank Balance Column
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = "Cumulative Balance",
                        style = TextStyle(
                            color = ColorProvider(Color(0xFFCBD5E1)),
                            fontSize = 12.sp
                        )
                    )
                    Spacer(modifier = GlanceModifier.height(2.dp))
                    Text(
                        text = String.format("₹%,.2f", bankBalance),
                        style = TextStyle(
                            color = ColorProvider(Color(0xFF34D399)),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }

                Spacer(modifier = GlanceModifier.width(12.dp))

                // Credit Card Due Column
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = "Credit Card Due",
                        style = TextStyle(
                            color = ColorProvider(Color(0xFFCBD5E1)),
                            fontSize = 12.sp
                        )
                    )
                    Spacer(modifier = GlanceModifier.height(2.dp))
                    Text(
                        text = String.format("₹%,.2f", creditCardDue),
                        style = TextStyle(
                            color = ColorProvider(Color(0xFFF87171)),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }
    }

    companion object {
        suspend fun updateAllWidgets(context: Context) {
            FinanceSummaryWidget().updateAll(context)
        }
    }
}
