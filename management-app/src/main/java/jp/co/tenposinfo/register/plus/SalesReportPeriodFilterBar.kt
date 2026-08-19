package jp.co.tenposinfo.register.plus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun SalesReportPeriodFilterBarV135(
    options: SalesReportFilterOptions,
    filter: SalesReportFilter,
    onFilterChanged: (SalesReportFilter) -> Unit,
) {
    val from = filter.businessDateFrom
    val to = filter.businessDateTo
    val validationError = SalesReportPeriodPolicy.validationError(filter)
    val selectableToDates = SalesReportPeriodPolicy.selectableToDates(from, options.businessDates)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("売上集計期間", fontWeight = FontWeight.Bold)
                    Text(
                        text = SalesReportPeriodPolicy.displayLabel(filter),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(
                    onClick = {
                        onFilterChanged(
                            filter.copy(
                                businessDate = null,
                                businessDateFrom = null,
                                businessDateTo = null,
                            ),
                        )
                    },
                ) {
                    Text("全期間")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PeriodDateMenu(
                    label = "開始",
                    selected = from,
                    values = options.businessDates,
                    emptyLabel = "開始日",
                    modifier = Modifier.weight(1f),
                    onSelected = { selectedFrom ->
                        val compatibleTo = filter.businessDateTo
                            ?.takeIf {
                                it in SalesReportPeriodPolicy.selectableToDates(
                                    selectedFrom,
                                    options.businessDates,
                                )
                            }
                            ?: selectedFrom
                        onFilterChanged(
                            filter.copy(
                                businessDate = null,
                                businessDateFrom = selectedFrom,
                                businessDateTo = compatibleTo,
                            ),
                        )
                    },
                )
                PeriodDateMenu(
                    label = "終了",
                    selected = to,
                    values = selectableToDates,
                    emptyLabel = if (from == null) "開始日を選択" else "終了日",
                    modifier = Modifier.weight(1f),
                    enabled = from != null,
                    onSelected = { selectedTo ->
                        onFilterChanged(
                            filter.copy(
                                businessDate = null,
                                businessDateTo = selectedTo,
                            ),
                        )
                    },
                )
            }

            Text(
                text = "任意期間は両端を含め最大${SalesReportPeriodPolicy.MAX_RANGE_DAYS}日。売上画面で単一営業日を選ぶと単日指定を優先します。",
                style = MaterialTheme.typography.labelSmall,
            )
            if (validationError != null) {
                Text(
                    text = validationError,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PeriodDateMenu(
    label: String,
    selected: String?,
    values: List<String>,
    emptyLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled && values.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "$label：${selected ?: emptyLabel}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            values.distinct().forEach { value ->
                DropdownMenuItem(
                    text = { Text(value) },
                    onClick = {
                        expanded = false
                        onSelected(value)
                    },
                )
            }
        }
    }
}
