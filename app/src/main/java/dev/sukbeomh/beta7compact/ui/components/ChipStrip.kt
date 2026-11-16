package dev.sukbeomh.beta7compact.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable fun ChipStrip(labels: List<String>, selected: String, select: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(labels) { label -> FilterChip(selected == label, { select(label) }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp)) }
    }
}
