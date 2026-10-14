package com.example.smartexpensetracker.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartexpensetracker.ai.GeminiTransactionAnalyzer
import com.example.smartexpensetracker.data.*
import com.example.smartexpensetracker.service.TransactionNotificationListenerService
import com.example.smartexpensetracker.widget.FinanceSummaryWidget
import com.example.smartexpensetracker.worker.UncategorizedReminderWorker
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Schedule daily 9:00 PM reminder check for uncategorized transactions
        UncategorizedReminderWorker.scheduleDailyReminder(applicationContext)

        val openUncategorizedTab = intent?.getBooleanExtra("OPEN_UNCATEGORIZED_TAB", false) ?: false

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0B0F19)
                ) {
                    ExpenseTrackerApp(initialTab = if (openUncategorizedTab) 1 else 0)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExpenseTrackerApp(initialTab: Int = 0) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.getInstance(context).transactionDao() }

    val allTransactions by dao.observeAllTransactions().collectAsState(initial = emptyList())
    val uncategorizedTransactions by dao.observeUncategorizedTransactions().collectAsState(initial = emptyList())
    val accountSummary by dao.observeAccountSummary().collectAsState(initial = AccountSummaryEntity())

    var selectedTab by remember { mutableIntStateOf(initialTab) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showBalanceAdjustDialog by remember { mutableStateOf(false) }
    var transactionToCategorize by remember { mutableStateOf<TransactionEntity?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        val perms = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    val isNotificationListenerEnabled = remember {
        mutableStateOf(checkNotificationListenerEnabled(context))
    }

    Scaffold(
        containerColor = Color(0xFF0B0F19),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AI Expense & Card Tracker", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            "Auto-listening to SMS & App Notifications",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { showBalanceAdjustDialog = true }) {
                        Text("Set Balances", color = Color(0xFF38BDF8))
                    }
                    TextButton(onClick = { showSettingsDialog = true }) {
                        Text("AI Key", color = Color(0xFFA78BFA))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF111827),
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // Notification Access Banner if not enabled yet
            if (!isNotificationListenerEnabled.value) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF451A03)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Enable Notification Listener",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFDE68A)
                        )
                        Text(
                            "Grant Notification Access so the app can read incoming alerts from Google Pay, PhonePe, Paytm, SMS, and Banking apps.",
                            fontSize = 12.sp,
                            color = Color(0xFFFEF3C7)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row {
                            Button(
                                onClick = {
                                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                }
                            ) {
                                Text("Open Settings")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = {
                                    isNotificationListenerEnabled.value = checkNotificationListenerEnabled(context)
                                }
                            ) {
                                Text("Refresh Status")
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Summary Card (Mirrors the Homescreen Widget)
            val summary = accountSummary ?: AccountSummaryEntity()
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Cumulative Balance", color = Color(0xFF94A3B8), fontSize = 12.sp)
                            Text(
                                String.format("₹%,.2f", summary.cumulativeBankBalance),
                                color = Color(0xFF34D399),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Credit Card Due", color = Color(0xFF94A3B8), fontSize = 12.sp)
                            Text(
                                String.format("₹%,.2f", summary.creditCardDue),
                                color = Color(0xFFF87171),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    // Simulate an ambiguous UPI transfer that needs manual categorization
                                    TransactionRepository.processIncomingAlert(
                                        context = context,
                                        rawText = "Rs. 350.00 debited from A/c XX4821 to VPA ramesh.k@okaxis via UPI. Avl Bal: Rs. 42,150.00",
                                        sourcePackage = "com.google.android.apps.nbu.paisa.user"
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Simulate Unclear UPI", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                UncategorizedReminderWorker.triggerImmediateCheckNow(context)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Test Daily Reminder", fontSize = 11.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Tabs: All Transactions vs Needs Manual Category
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color(0xFF111827)
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("All (${allTransactions.size})") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            "Needs Category (${uncategorizedTransactions.size})",
                            color = if (uncategorizedTransactions.isNotEmpty()) Color(0xFFFBBF24) else Color.Unspecified
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            val displayedList = if (selectedTab == 0) allTransactions else uncategorizedTransactions

            if (displayedList.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (selectedTab == 0) "No transactions captured yet."
                        else "All transactions are categorized!",
                        color = Color(0xFF64748B)
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(displayedList, key = { it.id }) { txn ->
                        TransactionRowCard(
                            txn = txn,
                            onAssignCategoryClick = { transactionToCategorize = txn }
                        )
                    }
                }
            }
        }
    }

    // Manual Category Assignment Dialog
    transactionToCategorize?.let { txn ->
        var customCategory by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { transactionToCategorize = null },
            title = { Text("Categorize ₹${String.format("%,.2f", txn.amount)}") },
            text = {
                Column {
                    Text("Payee: ${txn.merchantOrPayee}", fontWeight = FontWeight.SemiBold)
                    Text(txn.rawNotificationText, fontSize = 12.sp, color = Color(0xFF94A3B8))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Select a category:", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        GeminiTransactionAnalyzer.STANDARD_CATEGORIES
                            .filter { it != "Uncategorized" }
                            .forEach { cat ->
                                AssistChip(
                                    onClick = {
                                        scope.launch {
                                            dao.updateCategoryManually(txn.id, cat)
                                            FinanceSummaryWidget.updateAllWidgets(context)
                                            transactionToCategorize = null
                                        }
                                    },
                                    label = { Text(cat, fontSize = 12.sp) }
                                )
                            }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = customCategory,
                        onValueChange = { customCategory = it },
                        label = { Text("Or enter custom category") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                if (customCategory.isNotBlank()) {
                    Button(
                        onClick = {
                            scope.launch {
                                dao.updateCategoryManually(txn.id, customCategory.trim())
                                FinanceSummaryWidget.updateAllWidgets(context)
                                transactionToCategorize = null
                            }
                        }
                    ) {
                        Text("Save Custom")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { transactionToCategorize = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Gemini API Key & Daily Reminder Time Dialog
    if (showSettingsDialog) {
        var apiKey by remember { mutableStateOf(AppPreferences.getGeminiApiKey(context)) }
        val (savedHour, savedMin) = remember { AppPreferences.getReminderTime(context) }
        var hourText by remember { mutableStateOf(savedHour.toString()) }
        var minText by remember { mutableStateOf(savedMin.toString()) }

        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("AI & Daily Reminder Settings") },
            text = {
                Column {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("Gemini API Key (gemini-2.5-flash)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Daily Reminder Time (24-Hour Format):", fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = hourText,
                            onValueChange = { hourText = it },
                            label = { Text("Hour (0-23)") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = minText,
                            onValueChange = { minText = it },
                            label = { Text("Minute (0-59)") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        AppPreferences.setGeminiApiKey(context, apiKey)
                        val h = hourText.toIntOrNull()?.coerceIn(0, 23) ?: 21
                        val m = minText.toIntOrNull()?.coerceIn(0, 59) ?: 0
                        AppPreferences.setReminderTime(context, h, m)
                        UncategorizedReminderWorker.scheduleDailyReminder(context)
                        showSettingsDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Set Baseline Bank Balance & Credit Card Due Dialog
    if (showBalanceAdjustDialog) {
        val current = accountSummary ?: AccountSummaryEntity()
        var bankText by remember { mutableStateOf(current.cumulativeBankBalance.toString()) }
        var ccText by remember { mutableStateOf(current.creditCardDue.toString()) }

        AlertDialog(
            onDismissRequest = { showBalanceAdjustDialog = false },
            title = { Text("Update Baseline Balances") },
            text = {
                Column {
                    OutlinedTextField(
                        value = bankText,
                        onValueChange = { bankText = it },
                        label = { Text("Cumulative Bank Balance (₹)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = ccText,
                        onValueChange = { ccText = it },
                        label = { Text("Current Credit Card Due (₹)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            dao.saveAccountSummary(
                                AccountSummaryEntity(
                                    id = 1,
                                    cumulativeBankBalance = bankText.toDoubleOrNull() ?: 0.0,
                                    creditCardDue = ccText.toDoubleOrNull() ?: 0.0
                                )
                            )
                            FinanceSummaryWidget.updateAllWidgets(context)
                            showBalanceAdjustDialog = false
                        }
                    }
                ) {
                    Text("Update & Sync Widget")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBalanceAdjustDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun TransactionRowCard(
    txn: TransactionEntity,
    onAssignCategoryClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()) }
    val needsCategory = !txn.isCategoryFetchable || txn.category == "Uncategorized"

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (needsCategory) Color(0xFF2E1F0F) else Color(0xFF1E293B)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        txn.merchantOrPayee,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                    Text(
                        "${txn.type.name} • ${dateFormat.format(Date(txn.timestamp))}",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
                Text(
                    text = String.format("₹%,.2f", txn.amount),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = when (txn.type) {
                        TransactionType.CREDIT -> Color(0xFF34D399)
                        TransactionType.CC_SPEND -> Color(0xFFF87171)
                        else -> Color(0xFFFBBF24)
                    }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = onAssignCategoryClick,
                    label = {
                        Text(
                            if (needsCategory) "⚠️ Uncategorized (Tap to set)"
                            else txn.category,
                            fontSize = 12.sp
                        )
                    }
                )
                Text(
                    txn.sourcePackage.substringAfterLast('.'),
                    fontSize = 11.sp,
                    color = Color(0xFF64748B)
                )
            }
        }
    }
}

fun checkNotificationListenerEnabled(context: Context): Boolean {
    val cn = ComponentName(context, TransactionNotificationListenerService::class.java)
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return flat != null && flat.contains(cn.flattenToString())
}
