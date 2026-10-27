package kiesel

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsBike
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.TrendingFlat
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ForkLeft
import androidx.compose.material.icons.rounded.ForkRight
import androidx.compose.material.icons.rounded.LocationCity
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Park
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.RoundaboutRight
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.TurnLeft
import androidx.compose.material.icons.rounded.TurnRight
import androidx.compose.material.icons.rounded.TurnSharpLeft
import androidx.compose.material.icons.rounded.TurnSharpRight
import androidx.compose.material.icons.rounded.TurnSlightLeft
import androidx.compose.material.icons.rounded.TurnSlightRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kiesel.R
import java.util.Locale

class Palette(
    val dark: Boolean,
    val accent: Color,
    val accentCasing: Color,
    val pin: Color,
    val go: Color,
    val stop: Color,
    val ground: Color,
    val sheet: Color,
    val field: Color,
    val banner: Color,
    val text: Color,
    val secondary: Color,
    val separator: Color,
)

private val LightPalette = Palette(
    dark = false,
    accent = Color(0xFF2F7CF6),
    accentCasing = Color(0xFF1F5FCC),
    pin = Color(0xFFE5484D),
    go = Color(0xFF2FA35F),
    stop = Color(0xFFE5484D),
    ground = Color(0xFFF6F4EF),
    sheet = Color(0xFFFBFBFA),
    field = Color(0xFFEDEDEF),
    banner = Color(0xFF1F2A30),
    text = Color(0xFF1C1C1E),
    secondary = Color(0xFF85858B),
    separator = Color(0xFFE4E4E7),
)

private val DarkPalette = Palette(
    dark = true,
    accent = Color(0xFF4C93FF),
    accentCasing = Color(0xFF2B66C8),
    pin = Color(0xFFFF6B6B),
    go = Color(0xFF3DBA70),
    stop = Color(0xFFF2555A),
    ground = Color(0xFF1E2124),
    sheet = Color(0xFF1C1D1F),
    field = Color(0xFF2C2D30),
    banner = Color(0xFF2A3540),
    text = Color(0xFFF2F2F4),
    secondary = Color(0xFF98989E),
    separator = Color(0xFF333438),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

@Composable
fun KieselTheme(content: @Composable () -> Unit) {
    val palette = if (isSystemInDarkTheme()) DarkPalette else LightPalette
    val base = if (palette.dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = palette.accent,
        onPrimary = Color.White,
        background = palette.ground,
        surface = palette.sheet,
        onSurface = palette.text,
        onSurfaceVariant = palette.secondary,
        outlineVariant = palette.separator,
    )
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

private enum class Panel { Search, Place, Navigation, Maps }

@Composable
fun KieselScreen(model: AppModel) {
    val palette = LocalPalette.current
    val top = WindowInsets.statusBars.getTop(LocalDensity.current)
    var sheetHeight by remember { mutableIntStateOf(0) }
    val view = LocalView.current
    DisposableEffect(model.navigating) {
        view.keepScreenOn = model.navigating
        onDispose { }
    }

    Box(Modifier.fillMaxSize().background(palette.ground)) {
        KieselMap(model, palette, top, sheetHeight)
        AnimatedVisibility(
            visible = model.navigating,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) { Banner(model) }
        val bottom = Modifier.align(Alignment.BottomCenter)
        Sheet(bottom.onSizeChanged { sheetHeight = it.height }) {
            val panel = when {
                model.navigating -> Panel.Navigation
                model.managing || model.region == null -> Panel.Maps
                model.destination != null -> Panel.Place
                else -> Panel.Search
            }
            AnimatedContent(
                panel,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                label = "panel",
            ) {
                when (it) {
                    Panel.Search -> SearchPanel(model)
                    Panel.Place -> PlacePanel(model)
                    Panel.Navigation -> NavigationPanel(model)
                    Panel.Maps -> MapsPanel(model)
                }
            }
        }
    }
}

@Composable
private fun Sheet(modifier: Modifier, content: @Composable () -> Unit) = Surface(
    modifier = modifier
        .fillMaxWidth()
        .navigationBarsPadding()
        .imePadding()
        .padding(8.dp),
    shape = RoundedCornerShape(28.dp),
    color = LocalPalette.current.sheet,
    shadowElevation = 16.dp,
) {
    Column(
        Modifier.animateContentSize(spring(stiffness = 500f)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .padding(bottom = 12.dp)
                .size(width = 36.dp, height = 5.dp)
                .clip(CircleShape)
                .background(LocalPalette.current.separator),
        )
        content()
    }
}

@Composable
private fun SearchPanel(model: AppModel) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    LaunchedEffect(model.graph) { model.search(query) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                SearchField(query, enabled = model.graph != null) {
                    query = it
                    model.search(it)
                }
            }
            Spacer(Modifier.width(8.dp))
            RoundIcon(Icons.Rounded.Map, stringResource(R.string.maps), 46.dp) {
                model.managing = true
            }
        }
        when {
            model.failed -> Hint(stringResource(R.string.map_error))
            model.origin == null && query.isEmpty() ->
                Hint(stringResource(R.string.hold_to_start))
        }
        LazyColumn(Modifier.heightIn(max = 340.dp)) {
            items(model.results) { place ->
                ResultRow(model, place) {
                    query = ""
                    focus.clearFocus()
                    model.choose(place)
                }
            }
        }
        if (model.results.isEmpty()) {
            Text(
                stringResource(R.string.attribution),
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                fontSize = 11.sp,
                color = LocalPalette.current.secondary,
            )
        }
    }
}

@Composable
private fun SearchField(query: String, enabled: Boolean, onChange: (String) -> Unit) {
    val palette = LocalPalette.current
    val hint = if (enabled) R.string.search_hint else R.string.loading_places
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(palette.field)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, Modifier.size(22.dp), tint = palette.secondary)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(stringResource(hint), color = palette.secondary, fontSize = 17.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = onChange,
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(color = palette.text, fontSize = 17.sp),
                cursorBrush = SolidColor(palette.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            RoundIcon(Icons.Rounded.Close, stringResource(R.string.clear), 22.dp) {
                onChange("")
            }
        }
    }
}

@Composable
private fun ResultRow(model: AppModel, place: Place, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val distance = model.here?.distanceTo(place.location)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = if (place.rank > 0) Icons.Rounded.LocationCity else Icons.Rounded.Place
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(palette.accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(20.dp), tint = palette.accent) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                place.name,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            distance?.let {
                Text(
                    context.distanceText(it),
                    fontSize = 14.sp,
                    color = palette.secondary,
                )
            }
        }
    }
}

@Composable
private fun PlacePanel(model: AppModel) {
    val destination = model.destination ?: return
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                destination.name,
                Modifier.weight(1f),
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.3).sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val close = stringResource(R.string.close)
            RoundIcon(Icons.Rounded.Close, close, 30.dp, model::clear)
        }
        ModeSwitch(model)
        if (model.origin == null) {
            Hint(stringResource(R.string.hold_to_start))
            return@Column
        }
        Column {
            Profile.entries.forEach { ProfileRow(model, it) }
        }
        val route = model.route
        val heights = model.heights
        if (route != null && heights != null) ElevationChart(route, heights)
    }
}

/** Height along the route with total ascent and descent. */
@Composable
private fun ElevationChart(route: Route, heights: Heights) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val profile = remember(route, heights) {
        List(CHART_SAMPLES) {
            heights.at(route.at(route.meters * it / (CHART_SAMPLES - 1)))
        }
    }
    val changes = profile.zipWithNext { from, to -> to - from }
    val ascent = context.distanceText(changes.filter { it > 0 }.sum())
    val descent = context.distanceText(-changes.filter { it < 0 }.sum())
    Column(Modifier.padding(horizontal = 12.dp)) {
        Text("↑ $ascent   ↓ $descent", fontSize = 13.sp, color = palette.secondary)
        Canvas(Modifier.fillMaxWidth().height(48.dp).padding(top = 6.dp)) {
            val low = profile.min()
            val span = maxOf(profile.max() - low, MIN_CHART_METERS)
            fun x(index: Int) = size.width * index / (profile.size - 1)
            fun y(height: Double) = (size.height * (1 - (height - low) / span)).toFloat()
            val line = Path().apply {
                moveTo(x(0), y(profile.first()))
                profile.forEachIndexed { index, height -> lineTo(x(index), y(height)) }
            }
            val area = Path().apply {
                addPath(line)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(area, palette.accent.copy(alpha = 0.12f))
            val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
            drawPath(line, palette.accent, style = stroke)
        }
    }
}

/** Installed regions plus getting new ones: download in the browser, then import. */
@Composable
private fun MapsPanel(model: AppModel) {
    val links = LocalUriHandler.current
    val choose = ActivityResultContracts.OpenMultipleDocuments()
    val picker = rememberLauncherForActivityResult(choose, model::install)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.maps),
                Modifier.weight(1f),
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (model.region != null) {
                val close = stringResource(R.string.close)
                RoundIcon(Icons.Rounded.Close, close, 30.dp) { model.managing = false }
            }
        }
        model.regions.forEach { RegionRow(model, it) }
        when {
            model.importing -> LinearProgressIndicator(
                progress = { model.importProgress },
                modifier = Modifier.fillMaxWidth(),
            )
            model.importFailed -> Hint(stringResource(R.string.import_failed))
            model.partsMissing -> Hint(stringResource(R.string.import_parts))
            model.regions.isEmpty() -> Hint(stringResource(R.string.maps_hint))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val palette = LocalPalette.current
            Pill(stringResource(R.string.download), palette.accent, Modifier.weight(1f)) {
                links.openUri(PACKS_URL)
            }
            Pill(stringResource(R.string.import_pack), palette.go, Modifier.weight(1f)) {
                picker.launch(arrayOf("application/zip", "application/octet-stream"))
            }
        }
    }
}

@Composable
private fun RegionRow(model: AppModel, name: String) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val active = model.region == name
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.accent.copy(alpha = if (active) 0.10f else 0f))
            .clickable { model.activate(name) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (active) palette.accent else palette.secondary
        Icon(Icons.Rounded.Map, null, Modifier.size(24.dp), tint = tint)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                regionName(name, context.locale),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                Formatter.formatShortFileSize(context, model.bytes(name)),
                fontSize = 13.sp,
                color = palette.secondary,
            )
        }
        RoundIcon(Icons.Rounded.Delete, stringResource(R.string.delete), 34.dp) {
            model.delete(name)
        }
    }
}

/** "switzerland" → "Schweiz" in German; unknown names are just capitalized. */
private fun regionName(name: String, locale: Locale): String {
    val english = name.replace('-', ' ')
    val country = Locale.getISOCountries().map { Locale("", it) }.firstOrNull {
        it.getDisplayCountry(Locale.ENGLISH).equals(english, ignoreCase = true)
    }
    return country?.getDisplayCountry(locale)
        ?: english.split(' ').joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
}

@Composable
private fun ModeSwitch(model: AppModel) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.field)
            .padding(3.dp),
    ) {
        Mode.entries.forEach { mode ->
            val selected = model.mode == mode
            val background by animateColorAsState(
                if (selected) palette.sheet else Color.Transparent,
                label = "mode",
            )
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(background)
                    .clickable { model.select(mode) }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(modeIcon(mode), null, Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(modeName(mode)),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun ProfileRow(model: AppModel, profile: Profile) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val selected = model.profile == profile
    val route = model.routes[profile]
    val background by animateColorAsState(
        if (selected) palette.accent.copy(alpha = 0.10f) else Color.Transparent,
        label = "profile",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .clickable { model.select(profile) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (selected) palette.accent else palette.secondary
        Icon(profileIcon(profile), null, Modifier.size(24.dp), tint = tint)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(profileName(profile)),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                stringResource(profileDetail(profile)),
                fontSize = 13.sp,
                color = palette.secondary,
            )
        }
        when {
            profile !in model.routes ->
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            route == null ->
                Text(stringResource(R.string.no_route), color = palette.secondary)
            else -> Column(horizontalAlignment = Alignment.End) {
                Text(
                    context.durationText(route.seconds),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                val distance = context.distanceText(route.meters)
                Text(
                    "$distance · ↑${context.distanceText(route.rise)}",
                    fontSize = 13.sp,
                    color = palette.secondary,
                )
            }
        }
        if (selected && route != null) {
            Spacer(Modifier.width(12.dp))
            Pill(stringResource(R.string.go), palette.go) { model.start(profile) }
        }
    }
}

@Composable
private fun NavigationPanel(model: AppModel) {
    val guidance = model.guidance ?: return
    val palette = LocalPalette.current
    val context = LocalContext.current
    val arrival = System.currentTimeMillis() + (guidance.remainingSeconds * 1000).toLong()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val volume = if (model.muted) {
            Icons.AutoMirrored.Rounded.VolumeOff
        } else {
            Icons.AutoMirrored.Rounded.VolumeUp
        }
        val label = if (model.muted) R.string.unmute else R.string.mute
        RoundIcon(volume, stringResource(label), 44.dp, model::toggleMute)
        if (!model.following) {
            Spacer(Modifier.width(8.dp))
            RoundIcon(Icons.Outlined.NearMe, stringResource(R.string.recenter), 44.dp) {
                model.following = true
            }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
            val clock = DateFormat.getTimeFormat(context).format(arrival)
            Stat(clock, stringResource(R.string.arrival))
            Stat(context.durationText(guidance.remainingSeconds), null)
            Stat(context.distanceText(guidance.remainingMeters), null)
        }
        Pill(stringResource(R.string.end), palette.stop, onClick = model::end)
    }
}

@Composable
private fun Stat(value: String, label: String?) =
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        label?.let { Text(it, fontSize = 12.sp, color = LocalPalette.current.secondary) }
    }

@Composable
private fun Banner(model: AppModel) {
    val guidance = model.guidance ?: return
    val context = LocalContext.current
    val maneuver = guidance.maneuver
    val instruction = if (maneuver.turn == Turn.Arrive) model.destination?.name else null
    Surface(
        Modifier.statusBarsPadding().padding(8.dp).fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = LocalPalette.current.banner,
        contentColor = Color.White,
        shadowElevation = 16.dp,
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(turnIcon(maneuver.turn), null, Modifier.size(56.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        context.distanceText(guidance.metersToManeuver),
                        fontSize = 34.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.5).sp,
                    )
                    Text(
                        instruction ?: context.capitalized(context.instruction(maneuver)),
                        fontSize = 18.sp,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val next = guidance.next?.takeIf { it.meters - maneuver.meters < THEN_METERS }
            if (next != null) {
                HorizontalDivider(
                    Modifier.padding(vertical = 12.dp),
                    color = Color.White.copy(alpha = 0.15f),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.then),
                        fontSize = 15.sp,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(turnIcon(next.turn), null, Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
fun MapButton(onClick: () -> Unit, content: @Composable () -> Unit) = Surface(
    onClick = onClick,
    modifier = Modifier.size(48.dp),
    shape = RoundedCornerShape(14.dp),
    color = LocalPalette.current.sheet,
    shadowElevation = 8.dp,
) {
    Box(contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Hint(text: String) = Text(
    text,
    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
    color = LocalPalette.current.secondary,
    fontSize = 15.sp,
)

@Composable
private fun Pill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) = Button(
    onClick = onClick,
    modifier = modifier,
    shape = CircleShape,
    colors = ButtonDefaults.buttonColors(
        containerColor = color,
        contentColor = Color.White,
    ),
) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }

@Composable
private fun RoundIcon(
    icon: ImageVector,
    description: String,
    size: Dp,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(palette.field)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, Modifier.size(size * 0.55f), tint = palette.secondary) }
}

private fun turnIcon(turn: Turn) = when (turn) {
    Turn.SharpLeft -> Icons.Rounded.TurnSharpLeft
    Turn.Left -> Icons.Rounded.TurnLeft
    Turn.SlightLeft -> Icons.Rounded.TurnSlightLeft
    Turn.KeepLeft -> Icons.Rounded.ForkLeft
    Turn.KeepRight -> Icons.Rounded.ForkRight
    Turn.SlightRight -> Icons.Rounded.TurnSlightRight
    Turn.Right -> Icons.Rounded.TurnRight
    Turn.SharpRight -> Icons.Rounded.TurnSharpRight
    Turn.Roundabout -> Icons.Rounded.RoundaboutRight
    Turn.Arrive -> Icons.Rounded.Place
}

private fun modeIcon(mode: Mode) = when (mode) {
    Mode.Walk -> Icons.AutoMirrored.Rounded.DirectionsWalk
    Mode.Bike -> Icons.AutoMirrored.Rounded.DirectionsBike
}

private fun modeName(mode: Mode) = when (mode) {
    Mode.Walk -> R.string.walk
    Mode.Bike -> R.string.bike
}

private fun profileIcon(profile: Profile) = when (profile) {
    Profile.Fastest -> Icons.Rounded.Bolt
    Profile.Easiest -> Icons.AutoMirrored.Rounded.TrendingFlat
    Profile.Scenic -> Icons.Rounded.Park
    Profile.Quiet -> Icons.Rounded.SelfImprovement
}

private fun profileName(profile: Profile) = when (profile) {
    Profile.Fastest -> R.string.fastest
    Profile.Easiest -> R.string.easiest
    Profile.Scenic -> R.string.scenic
    Profile.Quiet -> R.string.quiet
}

private fun profileDetail(profile: Profile) = when (profile) {
    Profile.Fastest -> R.string.fastest_detail
    Profile.Easiest -> R.string.easiest_detail
    Profile.Scenic -> R.string.scenic_detail
    Profile.Quiet -> R.string.quiet_detail
}

private const val THEN_METERS = 150.0
private const val CHART_SAMPLES = 120
private const val MIN_CHART_METERS = 20.0
private const val PACKS_URL = "https://github.com/tn3w/kiesel/releases/tag/maps"
