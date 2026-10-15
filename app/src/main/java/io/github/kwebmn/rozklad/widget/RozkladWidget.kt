package io.github.kwebmn.rozklad.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
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
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import io.github.kwebmn.rozklad.MainActivity

class RozkladWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadWidgetData(context)
        provideContent { GlanceTheme { WidgetContent(data) } }
    }
}

class RozkladWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RozkladWidget()
}

@Composable
private fun WidgetContent(data: WidgetData) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Text(
            data.title,
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, color = GlanceTheme.colors.onSurface),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(6.dp))
        data.empty?.let {
            Text(it, style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant))
        }
        data.rows.forEach { WidgetRowView(it) }
    }
}

@Composable
private fun WidgetRowView(row: WidgetRow) {
    val background = if (row.highlight) GlanceTheme.colors.primaryContainer else GlanceTheme.colors.widgetBackground
    val color = if (row.highlight) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.onSurface
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(background)
            .cornerRadius(8.dp)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(
            row.time,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = color),
            modifier = GlanceModifier.width(44.dp),
        )
        Text(
            row.subject,
            style = TextStyle(
                fontSize = 13.sp,
                color = color,
                textDecoration = if (row.cancelled) TextDecoration.LineThrough else TextDecoration.None,
            ),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        Text(row.room, style = TextStyle(fontSize = 12.sp, color = color), maxLines = 1)
    }
}
