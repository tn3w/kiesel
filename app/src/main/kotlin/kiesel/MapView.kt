package kiesel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import android.location.Location
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.kiesel.R
import java.io.Reader
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

@Composable
fun KieselMap(model: AppModel, palette: Palette, topInset: Int, bottomInset: Int) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val language = context.locale.toLanguageTag().substringBefore('-')
    val view = remember { createMapView(context, palette) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var placedFor by remember { mutableStateOf<String?>(null) }
    var bearing by remember { mutableFloatStateOf(0f) }

    DisposableEffect(lifecycle) {
        view.getMapAsync { ready ->
            setUp(ready, model, language)
            ready.addOnCameraMoveListener {
                bearing = ready.cameraPosition.bearing.toFloat()
            }
            map = ready
        }
        val observer = LifecycleEventObserver { _, event -> forward(view, event) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView({ view }, Modifier.fillMaxSize())
        if (!model.navigating) {
            MapControls(model, bearing, Modifier.align(Alignment.TopEnd)) {
                map?.let(::faceNorth)
            }
        }
    }

    LaunchedEffect(map, palette.dark, model.tiles) {
        val ready = map ?: return@LaunchedEffect
        style = null
        val tiles = model.tiles
        val json = tiles?.let { styleJson(context, palette, language, it) }
            ?: emptyStyle(palette)
        ready.setStyle(Style.Builder().fromJson(json)) { style = it }
    }

    LaunchedEffect(map, model.here == null, model.graph) {
        val ready = map ?: return@LaunchedEffect
        val first = placedFor == null
        if (!first && placedFor == model.region.orEmpty()) return@LaunchedEffect
        val here = model.here?.let { cameraAt(it, BROWSE_ZOOM) }
        val saved = if (first) model.camera ?: here else null
        val camera = saved ?: model.graph?.let { cameraAt(it.middle(), REGION_ZOOM) }
            ?: return@LaunchedEffect
        ready.moveCamera(CameraUpdateFactory.newCameraPosition(camera))
        if (first) ready.addOnCameraIdleListener { model.camera = ready.cameraPosition }
        placedFor = model.region.orEmpty()
    }

    LaunchedEffect(style, model.routes, model.profile, model.navigating) {
        val source = style?.getSourceAs<GeoJsonSource>("routes") ?: return@LaunchedEffect
        val shown = model.routes.filter { !model.navigating || it.key == model.profile }
        val features = shown.mapNotNull { (profile, route) ->
            val feature = route?.let { line(it.points) }
            feature?.apply { addBooleanProperty("selected", profile == model.profile) }
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    LaunchedEffect(style, model.destination, model.manualOrigin) {
        val source = style?.getSourceAs<GeoJsonSource>("pins") ?: return@LaunchedEffect
        val pins = listOfNotNull(
            model.destination?.let { pin(it.location, "destination") },
            model.manualOrigin?.takeIf { model.here == null }?.let { pin(it, "origin") },
        )
        source.setGeoJson(FeatureCollection.fromFeatures(pins))
    }

    LaunchedEffect(style, model.location, model.guidance) {
        val ready = map ?: return@LaunchedEffect
        val loaded = style ?: return@LaunchedEffect
        val location = model.location ?: return@LaunchedEffect
        showPuck(context, ready, loaded, model, palette)
        ready.locationComponent.forceLocationUpdate(snapped(location, model.guidance))
    }

    LaunchedEffect(style, model.following, model.navigating, model.location == null) {
        val ready = map ?: return@LaunchedEffect
        if (style == null || !ready.locationComponent.isLocationComponentActivated) {
            return@LaunchedEffect
        }
        follow(ready, model, view.height)
    }

    val route = model.route
    LaunchedEffect(map, route, bottomInset) {
        val ready = map ?: return@LaunchedEffect
        if (model.navigating) return@LaunchedEffect
        val points = route?.points ?: model.destination?.let { listOf(it.location) }
            ?: return@LaunchedEffect
        val margin = (MARGIN_DP * context.resources.displayMetrics.density).toInt()
        val update = if (points.distinct().size < 2) {
            CameraUpdateFactory.newLatLngZoom(points.first().toLatLng(), PLACE_ZOOM)
        } else {
            val bounds = LatLngBounds.Builder()
                .includes(points.map { it.toLatLng() })
                .build()
            CameraUpdateFactory.newLatLngBounds(
                bounds, margin, topInset + margin, margin, bottomInset + margin,
            )
        }
        ready.animateCamera(update, CAMERA_MILLIS)
    }
}

/** Locate button, plus a compass while the map is turned; tapping it faces north. */
@Composable
private fun MapControls(
    model: AppModel,
    bearing: Float,
    modifier: Modifier,
    onNorth: () -> Unit,
) = Column(
    modifier.statusBarsPadding().padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
) {
    val palette = LocalPalette.current
    if (model.location != null) {
        MapButton({ model.following = !model.following }) {
            val filled = model.following
            val icon = if (filled) Icons.Rounded.NearMe else Icons.Outlined.NearMe
            val label = stringResource(R.string.locate)
            Icon(icon, label, Modifier.size(24.dp), tint = palette.accent)
        }
    }
    val turned = bearing in TURNED_DEGREES..(360 - TURNED_DEGREES)
    val enter = fadeIn() + scaleIn()
    AnimatedVisibility(turned, enter = enter, exit = fadeOut() + scaleOut()) {
        MapButton(onNorth) { Needle(bearing, palette) }
    }
}

@Composable
private fun Needle(bearing: Float, palette: Palette) =
    Canvas(Modifier.size(26.dp).rotate(-bearing)) {
        val middle = Offset(size.width / 2, size.height / 2)
        val half = size.width * 0.18f
        fun tip(towards: Float) = Path().apply {
            moveTo(middle.x, towards)
            lineTo(middle.x - half, middle.y)
            lineTo(middle.x + half, middle.y)
            close()
        }
        drawPath(tip(0f), palette.pin)
        drawPath(tip(size.height), palette.secondary.copy(alpha = 0.5f))
    }

private fun faceNorth(map: MapLibreMap) {
    val upright = CameraPosition.Builder(map.cameraPosition)
        .bearing(0.0)
        .tilt(0.0)
        .build()
    map.animateCamera(CameraUpdateFactory.newCameraPosition(upright), CAMERA_MILLIS)
}

private fun createMapView(context: Context, palette: Palette): MapView {
    MapLibre.getInstance(context)
    MapLibre.setConnected(false)
    val options = MapLibreMapOptions.createFromAttributes(context)
        .logoEnabled(false)
        .attributionEnabled(false)
        .compassEnabled(false)
        .foregroundLoadColor(palette.ground.toArgb())
        .localIdeographFontFamily("sans-serif")
    return MapView(context, options).apply { onCreate(null) }
}

private fun forward(view: MapView, event: Lifecycle.Event) = when (event) {
    Lifecycle.Event.ON_START -> view.onStart()
    Lifecycle.Event.ON_RESUME -> view.onResume()
    Lifecycle.Event.ON_PAUSE -> view.onPause()
    Lifecycle.Event.ON_STOP -> view.onStop()
    Lifecycle.Event.ON_DESTROY -> view.onDestroy()
    else -> Unit
}

private fun setUp(map: MapLibreMap, model: AppModel, language: String) {
    map.setMinZoomPreference(MIN_ZOOM)
    map.setMaxZoomPreference(MAX_ZOOM)
    map.addOnMapLongClickListener {
        model.drop(LatLon(it.latitude, it.longitude))
        true
    }
    map.addOnMapClickListener { tapped ->
        val place = labelAt(map, tapped, language) ?: return@addOnMapClickListener false
        model.choose(place)
        true
    }
}

/** A named label under the finger becomes a destination, like tapping a place. */
private fun labelAt(map: MapLibreMap, tapped: LatLng, language: String): Place? {
    val pixel = map.projection.toScreenLocation(tapped)
    val area = RectF(pixel.x, pixel.y, pixel.x, pixel.y)
    area.inset(-TAP_PIXELS, -TAP_PIXELS)
    val feature = map.queryRenderedFeatures(area, *LABEL_LAYERS)
        .firstOrNull { it.hasProperty("name") } ?: return null
    val name = feature.getStringProperty("name:$language")
        ?: feature.getStringProperty("name")
    val point = feature.geometry() as? Point
    val location = point?.let { LatLon(it.latitude(), it.longitude()) }
        ?: LatLon(tapped.latitude, tapped.longitude)
    return Place(name, location)
}

@SuppressLint("MissingPermission")
private fun showPuck(
    context: Context,
    map: MapLibreMap,
    style: Style,
    model: AppModel,
    palette: Palette,
) {
    val component = map.locationComponent
    if (component.isLocationComponentActivated) return
    val look = LocationComponentOptions.builder(context)
        .accuracyColor(palette.accent.toArgb())
        .accuracyAlpha(0.12f)
        .trackingGesturesManagement(true)
        .build()
    component.activateLocationComponent(
        LocationComponentActivationOptions.builder(context, style)
            .useDefaultLocationEngine(false)
            .locationComponentOptions(look)
            .build(),
    )
    component.isLocationComponentEnabled = true
    component.addOnCameraTrackingChangedListener(
        object : OnCameraTrackingChangedListener {
            override fun onCameraTrackingDismissed() {
                model.following = false
            }

            override fun onCameraTrackingChanged(currentMode: Int) = Unit
        },
    )
}

/** Navigation tilts the map and turns it with the phone; browsing keeps you centered. */
private fun follow(map: MapLibreMap, model: AppModel, height: Int) {
    val component = map.locationComponent
    component.renderMode = if (model.navigating) RenderMode.COMPASS else RenderMode.NORMAL
    when {
        model.navigating && model.following -> {
            map.moveCamera(CameraUpdateFactory.paddingTo(0.0, height * 0.45, 0.0, 0.0))
            component.setCameraMode(
                CameraMode.TRACKING_COMPASS, CAMERA_MILLIS.toLong(), NAVIGATION_ZOOM,
                null, NAVIGATION_TILT, null,
            )
        }
        model.following -> {
            map.moveCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, 0.0))
            component.setCameraMode(
                CameraMode.TRACKING, CAMERA_MILLIS.toLong(), BROWSE_ZOOM, 0.0, 0.0, null,
            )
        }
        else -> component.cameraMode = CameraMode.NONE
    }
    if (!model.navigating && map.cameraPosition.tilt > 0) {
        map.animateCamera(CameraUpdateFactory.tiltTo(0.0), CAMERA_MILLIS)
    }
}

/** On route, the puck sits on the line, so it never wobbles. */
private fun snapped(location: Location, guidance: Guidance?): Location {
    if (guidance == null || guidance.offRoute) return location
    return Location(location).apply {
        latitude = guidance.position.latitude
        longitude = guidance.position.longitude
    }
}

private fun styleJson(
    context: Context,
    palette: Palette,
    language: String,
    tiles: String,
): String {
    val template = context.assets.open("style.json").reader().use(Reader::readText)
    val colors = (if (palette.dark) DARK_MAP else LIGHT_MAP) + mapOf(
        "route" to palette.accent.hex(),
        "routeCasing" to palette.accentCasing.hex(),
        "pin" to palette.pin.hex(),
    )
    return colors.entries.fold(template) { json, (token, color) ->
        json.replace("\"@$token\"", "\"$color\"")
    }.replace("@lang", language).replace("@tiles", tiles)
}

private fun emptyStyle(palette: Palette) = """{"version": 8, "sources": {}, "layers": [
    {"id": "background", "type": "background",
    "paint": {"background-color": "${palette.ground.hex()}"}}]}"""

private fun Color.hex() = "#%06X".format(toArgb() and 0xFFFFFF)

private fun line(points: List<LatLon>) = Feature.fromGeometry(
    LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) }),
)

private fun pin(location: LatLon, kind: String) = Feature.fromGeometry(
    Point.fromLngLat(location.longitude, location.latitude),
).apply { addStringProperty("kind", kind) }

private fun LatLon.toLatLng() = LatLng(latitude, longitude)

private fun cameraAt(point: LatLon, zoom: Double) =
    CameraPosition.Builder().target(point.toLatLng()).zoom(zoom).build()

private fun Graph.middle() = bounds().let { (southWest, northEast) ->
    LatLon(
        (southWest.latitude + northEast.latitude) / 2,
        (southWest.longitude + northEast.longitude) / 2,
    )
}

private val LIGHT_MAP = mapOf(
    "land" to "#F6F4EF", "farm" to "#F1F0E6", "green" to "#DCEBCF", "wood" to "#CFE3C1",
    "rock" to "#ECEAE6", "water" to "#AFD5EE", "building" to "#E9E6E0",
    "buildingLine" to "#DDD9D1", "path" to "#C3BBAE", "cycle" to "#6DB394",
    "rail" to "#CBC8C2", "casing" to "#E0DBD1", "highwayCasing" to "#E6C77F",
    "road" to "#FFFFFF", "highway" to "#F8E2A6", "border" to "#B7A6C9",
    "alternative" to "#A9C4F3", "alternativeCasing" to "#86A6DD",
    "waterText" to "#4F84B0", "halo" to "#F6F4EF", "roadText" to "#5F6368",
    "peakText" to "#7A6A55", "placeText" to "#2F3337", "placeMinor" to "#7D8389",
    "food" to "#E8893B", "shop" to "#D76FA5", "nature" to "#4E9F62",
    "health" to "#D9534F",
    "transit" to "#4A86C8", "poi" to "#7F868C",
)

private val DARK_MAP = mapOf(
    "land" to "#1E2124", "farm" to "#202426", "green" to "#1F2E25", "wood" to "#1D2B22",
    "rock" to "#25282B", "water" to "#1B3142", "building" to "#2A2D31",
    "buildingLine" to "#33373C", "path" to "#4A4F55", "cycle" to "#3F8A6C",
    "rail" to "#3D4146", "casing" to "#2B2F33", "highwayCasing" to "#5B5240",
    "road" to "#3B4046", "highway" to "#6A6150", "border" to "#6E6380",
    "alternative" to "#3E5B86", "alternativeCasing" to "#2C4466",
    "waterText" to "#7FA9CC", "halo" to "#1E2124", "roadText" to "#A9AFB5",
    "peakText" to "#B5A68F", "placeText" to "#E3E6E9", "placeMinor" to "#8E959B",
    "food" to "#F0A35E", "shop" to "#E48DBA", "nature" to "#6CBF80",
    "health" to "#E57373",
    "transit" to "#6FA6E0", "poi" to "#9AA1A7",
)

private val LABEL_LAYERS =
    arrayOf("poi", "place-town", "place-village", "place-minor", "peak", "water-name")
private const val TAP_PIXELS = 24f
private const val MARGIN_DP = 24
private const val TURNED_DEGREES = 1f
private const val CAMERA_MILLIS = 800
private const val MIN_ZOOM = 5.0
private const val MAX_ZOOM = 19.5
private const val REGION_ZOOM = 7.5
private const val BROWSE_ZOOM = 16.0
private const val PLACE_ZOOM = 15.5
private const val NAVIGATION_ZOOM = 17.5
private const val NAVIGATION_TILT = 50.0
