package com.example.scrolltrack.ui.notifications

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.scrolltrack.ui.model.NotificationTreemapItem
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.graphics.Color
import coil.compose.rememberAsyncImagePainter
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import com.example.scrolltrack.R
import com.example.scrolltrack.data.AppMetadataRepository
import com.example.scrolltrack.db.AppMetadata
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    navController: NavController,
    viewModel: NotificationsViewModel
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notification Heatmap") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (val state = uiState) {
                is NotificationsUiState.Loading -> {
                    CircularProgressIndicator()
                }
                is NotificationsUiState.Success -> {
                    PeriodSelector(
                        selectedPeriod = state.selectedPeriod,
                        onPeriodSelected = viewModel::selectPeriod
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = state.periodTitle,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "${state.totalCount} notifications",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    if (state.notificationCounts.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No notifications for this period.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(state.notificationCounts, key = { it.first.packageName }) { (metadata, count) ->
                                var icon by remember { mutableStateOf<android.graphics.drawable.Drawable?>(null) }
                                LaunchedEffect(metadata.packageName) {
                                    icon = viewModel.getIcon(metadata.packageName)
                                }

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Image(
                                            painter = rememberAsyncImagePainter(
                                                model = icon ?: R.mipmap.ic_launcher_round
                                            ),
                                            contentDescription = "${metadata.appName} icon",
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                        )
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Text(
                                            text = metadata.appName,
                                            style = MaterialTheme.typography.bodyLarge,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = count.toString(),
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodSelector(
    selectedPeriod: NotificationPeriod,
    onPeriodSelected: (NotificationPeriod) -> Unit
) {
    val options = NotificationPeriod.entries
    val segmentedButtonColors = SegmentedButtonDefaults.colors(
        activeContainerColor = MaterialTheme.colorScheme.primary,
        activeContentColor = MaterialTheme.colorScheme.onPrimary,
        inactiveContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        inactiveContentColor = MaterialTheme.colorScheme.onSecondaryContainer
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, period ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                onClick = { onPeriodSelected(period) },
                selected = period == selectedPeriod,
                colors = segmentedButtonColors,
                icon = {
                    if (period == selectedPeriod) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            modifier = Modifier.size(ButtonDefaults.IconSize)
                        )
                    }
                }
            ) {
                Text(period.name)
            }
        }
    }
}

/**
 * A simplified placeholder for the Treemap that arranges items in a FlowRow.
 * A proper squarified treemap algorithm is more complex and will be implemented next.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SimpleTreemap(
    items: List<NotificationTreemapItem>,
    modifier: Modifier = Modifier
) {
    val totalCount = items.sumOf { it.count }.toFloat()
    if (totalCount == 0f) return

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { item ->
            val weight = item.count / totalCount
            Card(
                modifier = Modifier
                    .fillMaxWidth(fraction = (weight * 2).coerceIn(0.15f, 0.8f)) // Basic sizing
                    .padding(4.dp),
                colors = CardDefaults.cardColors(containerColor = item.color.copy(alpha = 0.8f))
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    item.icon?.let {
                        Image(
                            painter = rememberAsyncImagePainter(model = it),
                            contentDescription = item.appName,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${item.appName}: ${item.count}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
} 