package dev.sukbeomh.beta7compact.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.sukbeomh.beta7compact.App
import dev.sukbeomh.beta7compact.ui.theme.AppTheme
import dev.sukbeomh.beta7compact.ui.theme.LocalAppTheme

@Composable internal fun ThemeSetting(app: App) {
    val current = LocalAppTheme.current
    Text("앱 테마", style = MaterialTheme.typography.titleSmall)
    Card {
        Column(Modifier.fillMaxWidth().selectableGroup().padding(vertical = 8.dp)) {
            AppTheme.entries.forEach { theme ->
                Row(Modifier.fillMaxWidth().selectable(
                    selected = current == theme, role = Role.RadioButton,
                    onClick = { app.settings.edit().putString(AppTheme.PREFERENCE_KEY, theme.id).apply() },
                ).heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(selected = current == theme, onClick = null)
                    Text(theme.label, Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(theme.colors.background, theme.colors.primary, theme.colors.onBackground).forEach { color ->
                            Box(Modifier.size(16.dp).background(color, CircleShape))
                        }
                    }
                }
            }
            Text("배경·버튼·글자 색을 함께 변경합니다.\n선택은 자동 저장되며 사진의 색상과 저장 결과는 바뀌지 않습니다.",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
