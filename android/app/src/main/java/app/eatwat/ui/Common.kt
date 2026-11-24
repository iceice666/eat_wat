package app.eatwat.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.eatwat.data.Photos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) { Column(Modifier.padding(14.dp)) { content() } }
}

@Composable
fun StatTile(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(" $unit", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

@Composable
fun TileGrid(tiles: List<Triple<String, String, String>>, columns: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (k, v, u) -> StatTile(k, v, u, Modifier.weight(1f)) }
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun Chip(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
fun Pin(label: String) {
    Box(
        Modifier.size(26.dp).border(2.dp, LocalViz.current.meal, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun PhotoThumb(name: String, size: Dp, modifier: Modifier = Modifier, maxEdge: Int = 256) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, name) { value = withContext(Dispatchers.IO) { Photos.loadThumb(ctx, name, maxEdge) } }
    bmp?.let {
        Image(
            it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(RoundedCornerShape(8.dp)),
        )
    }
}

@Composable
fun PhotoBanner(name: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, name) { value = withContext(Dispatchers.IO) { Photos.loadThumb(ctx, name, 1200) } }
    bmp?.let {
        Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.clip(RoundedCornerShape(12.dp)))
    }
}

val Y_TICKS = listOf(40.0, 70.0, 100.0, 140.0, 180.0, 250.0, 300.0, 350.0, 400.0)
