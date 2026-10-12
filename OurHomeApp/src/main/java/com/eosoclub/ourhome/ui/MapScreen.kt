package com.eosoclub.ourhome.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eosoclub.ourhome.BuildConfig
import com.eosoclub.ourhome.data.ApiClient
import com.eosoclub.ourhome.data.CheckableMember
import com.eosoclub.ourhome.data.LiveCheck
import com.eosoclub.ourhome.data.LiveCheckChoices
import com.eosoclub.ourhome.data.LiveChecksOverview
import com.eosoclub.ourhome.data.MapPlace
import com.eosoclub.ourhome.data.MapTarget
import com.eosoclub.ourhome.data.MemberPresence
import com.eosoclub.ourhome.data.RingOverview
import com.eosoclub.ourhome.data.batteryLabel
import com.eosoclub.ourhome.data.awayOnMap
import com.eosoclub.ourhome.data.fixAge
import com.eosoclub.ourhome.data.intervalLabel
import com.eosoclub.ourhome.data.mapRefreshMs
import com.eosoclub.ourhome.data.mapTarget
import com.eosoclub.ourhome.data.minutesLabel
import com.eosoclub.ourhome.data.peopleAt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.time.Instant
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// The household map (web: /map): every map place as its circle, labelled with
// who is there, and everyone away at the point their phone left from, with how
// long ago. Not live: it shows what the server has and refetches every minute.
// MapLibre with OpenStreetMap tiles, no API key. Labels are drawn by Compose
// over the map, so the map style needs no font server.

class MapViewModel(private val api: ApiClient) : ViewModel() {
    data class UiState(
        val places: List<MapPlace> = emptyList(),
        val presence: List<MemberPresence> = emptyList(),
        /** Whether the person looking shares their own location. */
        val sharing: Boolean = true,
        /** Live checks: who I may check, the ones I have running (null on servers without them). */
        val checks: LiveChecksOverview? = null,
        /** The children I may ring (null on servers without ringing). */
        val ring: RingOverview? = null,
        val loaded: Boolean = false,
        val loading: Boolean = true,
        val error: String? = null,
    )

    val state = MutableStateFlow(UiState())

    fun load() = viewModelScope.launch {
        state.update { it.copy(loading = true) }
        state.load(
            fetch = {
                MapData(
                    api.mapPlaces(),
                    api.presence(),
                    api.locationConsent().isSharing,
                    runCatching { api.liveChecks() }.getOrNull(),
                    runCatching { api.ringOverview() }.getOrNull(),
                )
            },
            onOk = { s, data ->
                s.copy(
                    places = data.places,
                    presence = data.presence,
                    sharing = data.sharing,
                    checks = data.checks,
                    ring = data.ring,
                    loaded = true,
                    loading = false,
                    error = null,
                )
            },
            onError = { s, message -> s.copy(loading = false, error = message ?: "Couldn't load the map") },
        )
    }

    fun startCheck(targetId: String, minutes: Int, intervalSec: Int, onDone: (String) -> Unit) = viewModelScope.launch {
        try {
            val check = api.startLiveCheck(targetId, minutes, intervalSec)
            onDone("Checking on ${check.targetName} until ${clockLabel(check.endsAt)}")
            load()
        } catch (e: Exception) {
            onDone(e.message ?: "Couldn't start the live check")
        }
    }

    fun stopCheck(id: String, onDone: (String) -> Unit) = viewModelScope.launch {
        try {
            api.stopLiveCheck(id)
            load()
        } catch (e: Exception) {
            onDone(e.message ?: "Couldn't stop the live check")
        }
    }

    /** Rings [member]'s phone; [onDone] gets the message to show. */
    fun ring(member: CheckableMember, onDone: (String) -> Unit) = viewModelScope.launch {
        try {
            api.ringPhone(member.id)
            onDone("Ringing ${member.name}'s phone")
        } catch (e: Exception) {
            onDone(e.message ?: "Couldn't ring their phone")
        }
    }

    private class MapData(
        val places: List<MapPlace>,
        val presence: List<MemberPresence>,
        val sharing: Boolean,
        val checks: LiveChecksOverview?,
        val ring: RingOverview?,
    )
}

@Composable
internal fun MapScreen(api: ApiClient, showMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val vm = viewModel { MapViewModel(api) }
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Refetch while the screen is showing: once a minute (this also ages the
    // labels), or as often as a running live check asks its phone.
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.load().join()
                delay(mapRefreshMs(vm.state.value.checks?.started.orEmpty()))
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        if (LoadState(state.loading, state.error, !state.loaded, "", { vm.load() })) return@Box
        var focus by remember { mutableStateOf<Focus?>(null) }
        val focusOn = { target: MapTarget -> focus = Focus(target, (focus?.seq ?: 0) + 1) }
        var starting by remember { mutableStateOf<CheckableMember?>(null) }
        val checks = state.checks
        val started = checks?.started.orEmpty()
        // Zoom to each check's first fix, once.
        val focused = remember { mutableSetOf<String>() }
        LaunchedEffect(started) {
            started.firstOrNull { it.fix != null && it.id !in focused }?.let { check ->
                focused += check.id
                check.fix?.let { focusOn(MapTarget.Spot(it.lat, it.lng)) }
            }
        }

        Column(Modifier.fillMaxSize()) {
            if (!state.sharing) {
                Text(
                    "You aren't sharing where you are, so you aren't on the map. Turn it on in Profile → Location.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            HouseholdMap(
                state.places,
                state.presence,
                started,
                focus,
                Modifier.fillMaxWidth().weight(0.6f),
            )
            LazyColumn(
                contentPadding = PaddingValues(vertical = 8.dp),
                modifier = Modifier.fillMaxWidth().weight(0.4f),
            ) {
                if (checks != null && (checks.checkable.isNotEmpty() || started.isNotEmpty())) {
                    item { MapHeading("Live check") }
                    items(started, key = { "c-" + it.id }) { check ->
                        LiveCheckRow(
                            check,
                            onFocus = check.fix?.let { fix -> { focusOn(MapTarget.Spot(fix.lat, fix.lng)) } },
                            onStop = { vm.stopCheck(check.id, showMessage) },
                        )
                    }
                    val running = started.map { it.targetId }.toSet()
                    if (!checks.available) {
                        item { MapNote("Live checks need instant phone alerts (Firebase), which this server doesn't have set up.") }
                    } else {
                        items(checks.checkable.filter { it.id !in running }, key = { "k-" + it.id }) { member ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                            ) {
                                Text(member.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                TextButton(onClick = { starting = member }) { Text("Check location") }
                            }
                        }
                    }
                }
                val ring = state.ring
                if (ring != null && ring.ringable.isNotEmpty()) {
                    item { MapHeading("Ring phone") }
                    if (!ring.available) {
                        item { MapNote("Ringing a phone needs instant phone alerts (Firebase), which this server doesn't have set up.") }
                    } else {
                        item { MapNote("Rings loudly for up to 30 seconds, even on silent. They can tap Stop on their phone.") }
                        items(ring.ringable, key = { "r-" + it.id }) { member ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                            ) {
                                Text(member.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                TextButton(onClick = { vm.ring(member, showMessage) }) { Text("Ring") }
                            }
                        }
                    }
                }
                item { MapHeading("Who's where") }
                if (state.presence.isEmpty()) {
                    item { MapNote("Nobody yet. Members show up here once they share their location and their phone has reported in.") }
                }
                items(state.presence, key = { "m-" + it.userId }) { member ->
                    val target = mapTarget(member, state.places)
                    val battery = member.battery?.let { batteryLabel(it, System.currentTimeMillis()) }
                    MapRow(
                        title = member.name,
                        trailing = "${member.label} · ${relativeTime(member.since)}",
                        onClick = target?.let { { focusOn(it) } },
                        subtitle = battery?.text,
                        subtitleIsError = battery?.low == true,
                    )
                }
                item { MapHeading("Places") }
                if (state.places.isEmpty()) {
                    item { MapNote("No places yet. Someone who manages the household adds them on the website, in Settings → Map places.") }
                }
                items(state.places, key = { "p-" + it.id }) { place ->
                    MapRow(
                        title = place.name,
                        trailing = KIND_LABELS[place.kind] ?: place.kind,
                        onClick = { focusOn(MapTarget.Area(place.lat, place.lng, place.radiusM.toDouble())) },
                    )
                }
            }
        }

        val member = starting
        if (member != null && checks != null) {
            StartCheckDialog(
                member,
                checks.choices,
                onStart = { minutes, interval ->
                    starting = null
                    vm.startCheck(member.id, minutes, interval, showMessage)
                },
                onDismiss = { starting = null },
            )
        }
    }
}

@Composable
private fun LiveCheckRow(check: LiveCheck, onFocus: (() -> Unit)?, onStop: () -> Unit) {
    val status = when {
        check.state == "waiting" -> "waiting for their phone"
        check.state == "no_answer" -> "their phone hasn't answered"
        check.fix != null -> runCatching {
            fixAge(Instant.parse(check.fix.at).toEpochMilli(), System.currentTimeMillis())
        }.getOrDefault("live")
        else -> "live"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onFocus != null) { onFocus?.invoke() }
            .padding(start = 16.dp, end = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(check.targetName, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
            Text(
                "Until ${clockLabel(check.endsAt)} · $status",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onStop) { Text("Stop") }
    }
}

/** How long and how often, within the household's limits. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StartCheckDialog(
    member: CheckableMember,
    choices: LiveCheckChoices,
    onStart: (minutes: Int, intervalSec: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var minutes by remember { mutableIntStateOf(choices.defaultMinutes) }
    var interval by remember { mutableIntStateOf(choices.defaultIntervalSec) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Check where ${member.name} is?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Their phone shows a notification the whole time, and they can stop it. It's listed on their Profile.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("For", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.minutes.forEach { m ->
                        FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text(minutesLabel(m)) })
                    }
                }
                Text("Update every", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.intervals.forEach { s ->
                        FilterChip(selected = interval == s, onClick = { interval = s }, label = { Text(intervalLabel(s)) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onStart(minutes, interval) }) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val KIND_LABELS = mapOf("home" to "Home", "store" to "Store", "other" to "Other")

/** Something to zoom to; [seq] makes tapping the same row twice move again. */
private data class Focus(val target: MapTarget, val seq: Int)

@Composable
private fun MapHeading(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun MapNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun MapRow(
    title: String,
    trailing: String,
    onClick: (() -> Unit)?,
    subtitle: String? = null,
    subtitleIsError: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (subtitleIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(trailing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --- The map ----------------------------------------------------------------------------

private const val TILE_STYLE = """
{
  "version": 8,
  "sources": {
    "osm": {
      "type": "raster",
      "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
      "tileSize": 256,
      "maxzoom": 19,
      "attribution": "© OpenStreetMap contributors"
    }
  },
  "layers": [{ "id": "osm", "type": "raster", "source": "osm" }]
}
"""
private const val AREAS = "areas"
private const val AWAY = "away"
private const val LIVE = "live"
private const val HOME_COLOR = "#22C55E"
private const val AWAY_COLOR = "#F59E0B"
/** Around a lone point (someone away), the map shows about this much. */
private const val SPOT_RADIUS_M = 150.0

/** MapLibre, set up once per process. OpenStreetMap asks apps that load its
 *  tiles to name themselves, so tile requests say which app they're from. */
private object MapSetup {
    private var ready = false

    fun ensure(context: Context) {
        if (ready) return
        MapLibre.getInstance(context.applicationContext)
        HttpRequestUtil.setOkHttpClient(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("User-Agent", "OurHome-Android/${BuildConfig.VERSION_NAME}").build())
                }
                .build(),
        )
        ready = true
    }
}

private fun hex(color: Color): String = String.format(Locale.ROOT, "#%06X", 0xFFFFFF and color.toArgb())

/** The corners of a box around a circle, for fitting the camera to it. */
private fun corners(lat: Double, lng: Double, radiusM: Double): List<LatLng> {
    val dLat = radiusM / 111_320.0
    val dLng = radiusM / (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
    return listOf(LatLng(lat - dLat, lng - dLng), LatLng(lat + dLat, lng + dLng))
}

/** A circle on the ground as a 64-sided polygon (flat-earth offsets: fine at a few km). */
private fun circle(lat: Double, lng: Double, radiusM: Double, color: String, opacity: Double, width: Double): Feature {
    val dLat = radiusM / 111_320.0
    val dLng = radiusM / (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
    val ring = (0..64).map { i ->
        val angle = 2 * Math.PI * i / 64
        Point.fromLngLat(lng + dLng * cos(angle), lat + dLat * sin(angle))
    }
    return Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))).apply {
        addStringProperty("color", color)
        addNumberProperty("opacity", opacity)
        addNumberProperty("width", width)
    }
}

/** A name on the map, at a point on the screen (pixels inside the map). */
private data class MapLabel(val x: Float, val y: Float, val title: String, val detail: String?, val beside: Boolean)

@Composable
private fun HouseholdMap(
    places: List<MapPlace>,
    presence: List<MemberPresence>,
    /** Live checks the viewer has running: each latest fix is drawn. */
    live: List<LiveCheck>,
    focus: Focus?,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val storeColor = hex(MaterialTheme.colorScheme.primary)
    val otherColor = hex(MaterialTheme.colorScheme.onSurfaceVariant)
    val mapView = remember {
        MapSetup.ensure(context)
        MapView(context).apply { onCreate(null) }
    }
    // Set once the style has loaded: sources and layers exist from then on.
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    // Bumped on every camera move, so the labels follow the map.
    var cameraMoves by remember { mutableIntStateOf(0) }
    // Fitted to what's on the map once; after that the viewer's pan and zoom stay.
    var fitted by remember { mutableStateOf(false) }
    val padding = with(LocalDensity.current) { 48.dp.roundToPx() }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            // Leaving the screen while the app is open: wind the map down in order.
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.addOnCameraMoveListener { cameraMoves++ }
            val style = Style.Builder()
                .fromJson(TILE_STYLE)
                .withSource(GeoJsonSource(AREAS))
                .withSource(GeoJsonSource(AWAY))
                .withSource(GeoJsonSource(LIVE))
                .withLayer(
                    FillLayer("areas-fill", AREAS).withProperties(
                        PropertyFactory.fillColor(Expression.toColor(Expression.get("color"))),
                        PropertyFactory.fillOpacity(Expression.get("opacity")),
                    ),
                )
                .withLayer(
                    LineLayer("areas-line", AREAS).withProperties(
                        PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))),
                        PropertyFactory.lineWidth(Expression.get("width")),
                    ),
                )
                .withLayer(
                    CircleLayer("away-dot", AWAY).withProperties(
                        PropertyFactory.circleRadius(7f),
                        PropertyFactory.circleColor(AWAY_COLOR),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleStrokeWidth(2f),
                    ),
                )
                .withLayer(
                    CircleLayer("live-dot", LIVE).withProperties(
                        PropertyFactory.circleRadius(8f),
                        PropertyFactory.circleColor(storeColor),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleStrokeWidth(3f),
                    ),
                )
            m.setStyle(style) { map = m }
        }
    }

    // Draw the places and people (again on every refetch).
    LaunchedEffect(map, places, presence, live, storeColor, otherColor) {
        val m = map ?: return@LaunchedEffect
        val style = m.style ?: return@LaunchedEffect
        val fixes = live.mapNotNull { check -> check.fix?.let { check to it } }
        // Someone being checked live is shown at the live fix instead.
        val liveIds = fixes.map { it.first.targetId }.toSet()
        val away = awayOnMap(presence).filter { it.userId !in liveIds }
        val areas = places.map { place ->
            val color = when (place.kind) {
                "home" -> HOME_COLOR
                "store" -> storeColor
                else -> otherColor
            }
            circle(place.lat, place.lng, place.radiusM.toDouble(), color, if (peopleAt(place.id, presence).isEmpty()) 0.08 else 0.22, 2.0)
        } + away.mapNotNull { member ->
            val point = member.point ?: return@mapNotNull null
            val accuracy = point.accuracyM?.takeIf { it > 0 } ?: return@mapNotNull null
            circle(point.lat, point.lng, accuracy, AWAY_COLOR, 0.1, 1.0)
        } + fixes.mapNotNull { (_, fix) ->
            val accuracy = fix.accuracyM?.takeIf { it > 0 } ?: return@mapNotNull null
            circle(fix.lat, fix.lng, accuracy, storeColor, 0.12, 1.0)
        }
        style.getSourceAs<GeoJsonSource>(AREAS)?.setGeoJson(FeatureCollection.fromFeatures(areas))
        style.getSourceAs<GeoJsonSource>(AWAY)?.setGeoJson(
            FeatureCollection.fromFeatures(away.mapNotNull { member -> member.point?.let { Feature.fromGeometry(Point.fromLngLat(it.lng, it.lat)) } }),
        )
        style.getSourceAs<GeoJsonSource>(LIVE)?.setGeoJson(
            FeatureCollection.fromFeatures(fixes.map { (_, fix) -> Feature.fromGeometry(Point.fromLngLat(fix.lng, fix.lat)) }),
        )

        if (!fitted && (places.isNotEmpty() || away.isNotEmpty())) {
            fitted = true
            val points = places.flatMap { corners(it.lat, it.lng, it.radiusM.toDouble()) } +
                away.flatMap { member -> member.point?.let { corners(it.lat, it.lng, SPOT_RADIUS_M) }.orEmpty() }
            m.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(), padding))
        }
    }

    // Zoom to whatever was tapped in the list.
    LaunchedEffect(map, focus) {
        val m = map ?: return@LaunchedEffect
        val target = focus?.target ?: return@LaunchedEffect
        val points = when (target) {
            is MapTarget.Area -> corners(target.lat, target.lng, target.radiusM)
            is MapTarget.Spot -> corners(target.lat, target.lng, SPOT_RADIUS_M)
        }
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(), padding))
    }

    Box(modifier.clipToBounds()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        val m = map
        if (m != null) {
            val labels = remember(m, places, presence, live, cameraMoves) {
                val projection = m.projection
                val now = System.currentTimeMillis()
                val fixes = live.mapNotNull { check -> check.fix?.let { check to it } }
                val liveIds = fixes.map { it.first.targetId }.toSet()
                places.map { place ->
                    val at = projection.toScreenLocation(LatLng(place.lat, place.lng))
                    MapLabel(at.x, at.y, place.name, peopleAt(place.id, presence).joinToString(", ").ifEmpty { null }, beside = false)
                } + awayOnMap(presence).filter { it.userId !in liveIds }.mapNotNull { member ->
                    val point = member.point ?: return@mapNotNull null
                    val at = projection.toScreenLocation(LatLng(point.lat, point.lng))
                    // A point a live check left after they left: when it was seen.
                    val detail = if (point.at != null && point.at != member.since) {
                        "Seen ${relativeTime(point.at)}"
                    } else {
                        "Left ${relativeTime(member.since)}"
                    }
                    MapLabel(at.x, at.y, member.name, detail, beside = true)
                } + fixes.map { (check, fix) ->
                    val at = projection.toScreenLocation(LatLng(fix.lat, fix.lng))
                    val age = runCatching { fixAge(Instant.parse(fix.at).toEpochMilli(), now) }.getOrDefault("live")
                    MapLabel(at.x, at.y, check.targetName, "Live, $age", beside = true)
                }
            }
            MapLabels(labels, Modifier.fillMaxSize())
        }
    }
}

/** Places labels above their place's centre, and people beside their dot. */
@Composable
private fun MapLabels(labels: List<MapLabel>, modifier: Modifier) {
    val gap = with(LocalDensity.current) { 10.dp.roundToPx() }
    Layout(
        content = {
            labels.forEach { label ->
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 2.dp,
                ) {
                    Column(Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) {
                        Text(label.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        label.detail?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { i, placeable ->
                val label = labels[i]
                val x = if (label.beside) label.x.roundToInt() + gap else (label.x - placeable.width / 2f).roundToInt()
                val y = if (label.beside) (label.y - placeable.height / 2f).roundToInt() else label.y.roundToInt() - placeable.height - gap
                placeable.place(x, y)
            }
        }
    }
}
