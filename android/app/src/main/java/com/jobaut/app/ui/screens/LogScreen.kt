package com.jobaut.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jobaut.app.ui.DashboardUiState
import com.jobaut.app.ui.DashboardViewModel
import com.jobaut.app.ui.LogEntry

// Color palette for log levels
private val ColorAction = Color(0xFFD81B60) // Magenta
private val ColorInput = Color(0xFF00C853)  // Green
private val ColorLlm = Color(0xFF2979FF)    // Blue
private val ColorSuccess = Color(0xFF10B981) // Emerald
private val ColorError = Color(0xFFEF5350)   // Red
private val ColorInfo = Color(0xFF90A4AE)    // Slate / Neutral

@Composable
fun LogScreen(
    uiState: DashboardUiState,
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    var selectedFilter by remember { mutableStateOf<String?>(null) }

    val filteredLogs = remember(uiState.logs, selectedFilter) {
        if (selectedFilter == null) {
            uiState.logs
        } else {
            uiState.logs.filter { it.tag.equals(selectedFilter, ignoreCase = true) }
        }
    }

    // Auto-scroll to bottom when new logs are added
    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // --- Header & Clear Button ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Live Execution Log",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${filteredLogs.size} events recorded",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(
                onClick = { viewModel.clearLogs() },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = "Clear logs",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // --- Filter Chips ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val filters = listOf("ALL", "ACTION", "INPUT", "LLM", "SUCCESS", "ERROR")
            filters.forEach { filter ->
                val isSelected = (filter == "ALL" && selectedFilter == null) || (filter == selectedFilter)
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        selectedFilter = if (filter == "ALL") null else filter
                    },
                    label = {
                        Text(
                            text = filter,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // --- Console Log List ---
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF0F141C) // Terminal dark background
        ) {
            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No logs recorded yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF546E7A),
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(filteredLogs) { logEntry ->
                        LogItemView(logEntry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogItemView(entry: LogEntry) {
    val (badgeBg, badgeFg) = when (entry.tag.uppercase()) {
        "ACTION" -> Pair(ColorAction.copy(alpha = 0.2f), ColorAction)
        "INPUT" -> Pair(ColorInput.copy(alpha = 0.2f), ColorInput)
        "LLM" -> Pair(ColorLlm.copy(alpha = 0.2f), ColorLlm)
        "SUCCESS" -> Pair(ColorSuccess.copy(alpha = 0.2f), ColorSuccess)
        "ERROR" -> Pair(ColorError.copy(alpha = 0.2f), ColorError)
        else -> Pair(ColorInfo.copy(alpha = 0.2f), ColorInfo)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        // Timestamp
        Text(
            text = entry.timestamp,
            color = Color(0xFF607D8B),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp)
        )

        Spacer(modifier = Modifier.width(6.dp))

        // Tag badge
        Surface(
            color = badgeBg,
            shape = RoundedCornerShape(4.dp)
        ) {
            Text(
                text = entry.tag,
                color = badgeFg,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Message
        Text(
            text = entry.message,
            color = Color(0xFFE2E8F0),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            modifier = Modifier.weight(1f)
        )
    }
}
