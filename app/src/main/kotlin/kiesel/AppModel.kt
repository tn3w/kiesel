package kiesel

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import app.kiesel.R
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.Channels
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition

/** Lives as long as the process, so navigation survives the screen turning off. */
class AppModel(private val application: Application) : CoroutineScope by MainScope() {
    private val preferences = application.getSharedPreferences("kiesel", 0)
    private val maps = File(application.filesDir, "maps")

    var regions by mutableStateOf(installedRegions())
        private set
    var region by mutableStateOf(
        preferences.getString("region", null)?.takeIf { it in regions }
            ?: regions.firstOrNull(),
    )
        private set
    var graph by mutableStateOf<Graph?>(null)
        private set
    var heights by mutableStateOf<Heights?>(null)
        private set
    var failed by mutableStateOf(false)
        private set
    var importing by mutableStateOf(false)
        private set
    var importFailed by mutableStateOf(false)
        private set
    var partsMissing by mutableStateOf(false)
        private set
    var importProgress by mutableFloatStateOf(0f)
        private set
    var managing by mutableStateOf(false)
    var mode by mutableStateOf(preferences.enum("mode", Mode.Walk))
        private set
    var profile by mutableStateOf(preferences.enum("profile", Profile.Fastest))
        private set
    var location by mutableStateOf<Location?>(null)
        private set
    var manualOrigin by mutableStateOf<LatLon?>(null)
        private set
    var destination by mutableStateOf<Place?>(null)
        private set
    var routes by mutableStateOf<Map<Profile, Route?>>(emptyMap())
        private set
    var guidance by mutableStateOf<Guidance?>(null)
        private set
    var results by mutableStateOf<List<Place>>(emptyList())
        private set
    var muted by mutableStateOf(false)
        private set
    var following by mutableStateOf(false)
    var camera: CameraPosition? = null

    val here get() = location?.let { LatLon(it.latitude, it.longitude) }
    val origin get() = here ?: manualOrigin
    val route get() = routes[profile]
    val navigating get() = guidance != null
    val tiles get() = region?.let { "pmtiles://file://${File(maps, "$it/$TILES").path}" }

    private val voice = Voice(application)
    private var router: Router? = null
    private var navigator: Navigator? = null
    private var loading: Job? = null
    private var planning: Job? = null
    private var searching: Job? = null
    private var listener: LocationListener? = null
    private var lastReroute = 0L

    fun load() {
        val name = region ?: return
        if (graph != null || failed || loading?.isActive == true) return
        loading = launch {
            runCatching { withContext(Dispatchers.IO) { read(name) } }
                .onSuccess { (loaded, levels) ->
                    router = Router(loaded)
                    graph = loaded
                    heights = levels
                    plan()
                }
                .onFailure {
                    Log.e("Kiesel", "map load failed", it)
                    failed = true
                }
        }
    }

    private fun read(name: String): Pair<Graph, Heights> {
        val folder = File(maps, name)
        return Graph.read(File(folder, GRAPH)) to Heights.read(File(folder, HEIGHTS))
    }

    /** Switches to an installed region, or to none. */
    fun activate(name: String?) {
        clear()
        loading?.cancel()
        router = null
        graph = null
        heights = null
        failed = false
        results = emptyList()
        managing = false
        region = name
        preferences.edit { putString("region", name) }
        load()
    }

    /** Imports region packs or parts of them (zips), picked here or opened elsewhere. */
    fun install(packs: List<Uri>) {
        if (packs.isEmpty()) return
        importing = true
        importProgress = 0f
        importFailed = false
        partsMissing = false
        launch {
            val names = runCatching { unpackAll(packs) }
                .onFailure { Log.e("Kiesel", "import failed", it) }
                .getOrNull()
            importing = false
            regions = installedRegions()
            val complete = names.orEmpty().filterNotNull().lastOrNull { it in regions }
            when {
                names == null || null in names -> importFailed = true
                complete == null -> partsMissing = true
                else -> activate(complete)
            }
        }
    }

    /** Parts unpack in parallel: each one's pieces go to their own place. */
    private suspend fun unpackAll(packs: List<Uri>) = coroutineScope {
        val resolver = application.contentResolver
        val total = packs.sumOf { pack ->
            resolver.openAssetFileDescriptor(pack, "r")?.use { it.length } ?: 0L
        }
        val read = AtomicLong()
        packs.map { pack ->
            async(Dispatchers.IO) {
                val input = resolver.openInputStream(pack) ?: return@async null
                unpack(CountingStream(input) { bytes ->
                    val done = read.addAndGet(bytes.toLong())
                    importProgress = done.toFloat() / total.coerceAtLeast(1)
                })
            }
        }.awaitAll()
    }

    fun delete(name: String) {
        File(maps, name).deleteRecursively()
        regions = installedRegions()
        if (region == name) activate(regions.firstOrNull())
    }

    fun bytes(name: String) = File(maps, name).walk().sumOf { it.length() }

    private fun installedRegions() =
        maps.listFiles().orEmpty()
            .filter { folder -> FILES.all { File(folder, it).exists() } }
            .map { it.name }
            .sorted()

    /** Files are written beside the old ones and renamed, so open maps stay intact. */
    private fun unpack(input: InputStream): String? {
        val root = maps.canonicalFile
        var name: String? = null
        ZipInputStream(input.buffered(BUFFER_BYTES)).use { zip ->
            val entries = generateSequence { zip.nextEntry }.filter { !it.isDirectory }
            entries.forEach { entry ->
                val target = File(root, entry.name).canonicalFile
                val folder = target.parentFile
                check(folder?.parentFile == root) { "Bad entry ${entry.name}" }
                folder.mkdirs()
                val piece = PIECE.matchEntire(target.name)
                if (piece == null) save(zip, target) else savePiece(zip, folder, piece)
                name = folder.name
            }
        }
        return name
    }

    private fun save(zip: InputStream, target: File) {
        val partial = File(target.path + ".part")
        partial.outputStream().use { zip.copyTo(it, BUFFER_BYTES) }
        partial.renameTo(target)
    }

    /**
     * Writes piece "file.2of3" straight to its place in "file.part" and leaves the
     * piece's name as a marker; once all pieces are in, "file.part" becomes "file".
     */
    private fun savePiece(zip: InputStream, folder: File, piece: MatchResult) {
        val (name, index, count) = piece.destructured
        val partial = File(folder, "$name.part")
        RandomAccessFile(partial, "rw").use { file ->
            val channel = file.channel.position((index.toLong() - 1) * PIECE_BYTES)
            zip.copyTo(Channels.newOutputStream(channel), BUFFER_BYTES)
            if (index == count) file.setLength(channel.position())
        }
        synchronized(this) {
            File(folder, piece.value).createNewFile()
            val markers = (1..count.toInt()).map { File(folder, "$name.${it}of$count") }
            if (!markers.all(File::exists)) return
            partial.renameTo(File(folder, name))
            markers.forEach(File::delete)
        }
    }

    @SuppressLint("MissingPermission")
    fun trackLocation() {
        val granted =
            application.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        if (listener != null || granted != PackageManager.PERMISSION_GRANTED) return
        val manager = application.getSystemService(LocationManager::class.java)
        val provider = LocationManager.FUSED_PROVIDER
            .takeIf(manager::hasProvider) ?: LocationManager.GPS_PROVIDER
        manager.getLastKnownLocation(provider)?.let(::onLocation)
        val request = LocationRequest.Builder(LOCATION_INTERVAL_MILLIS)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .build()
        val executor = application.mainExecutor
        listener = LocationListener(::onLocation).also {
            manager.requestLocationUpdates(provider, request, executor, it)
        }
    }

    fun releaseLocation() {
        val active = listener ?: return
        if (navigating) return
        application.getSystemService(LocationManager::class.java).removeUpdates(active)
        listener = null
    }

    fun search(query: String) {
        searching?.cancel()
        val places = graph?.places
        val near = here ?: camera?.target?.let { LatLon(it.latitude, it.longitude) }
        if (places == null || near == null || query.isBlank()) {
            results = emptyList()
            return
        }
        searching = launch {
            results = withContext(Dispatchers.Default) {
                places.search(query, near, MAX_RESULTS)
            }
        }
    }

    fun drop(point: LatLon) {
        if (navigating) return
        if (origin == null) {
            manualOrigin = point
            plan()
            return
        }
        val street = graph?.snap(point)?.let { graph?.streetName(it.edge) }
        choose(Place(street ?: application.getString(R.string.dropped_pin), point))
    }

    fun choose(place: Place) {
        if (navigating) return
        destination = place
        results = emptyList()
        plan()
    }

    fun select(newMode: Mode) {
        mode = newMode
        preferences.edit { putString("mode", newMode.name) }
        plan()
    }

    fun select(newProfile: Profile) {
        profile = newProfile
        preferences.edit { putString("profile", newProfile.name) }
    }

    fun clear() {
        stop()
        planning?.cancel()
        destination = null
        manualOrigin = null
        routes = emptyMap()
    }

    fun start(choice: Profile) {
        planning?.cancel()
        select(choice)
        val route = route ?: return
        val point = origin ?: return
        val navigator = Navigator(route, mode).also { navigator = it }
        following = true
        ContextCompat.startForegroundService(application, serviceIntent())
        show(navigator.update(point))
    }

    fun end() {
        stop()
        voice.stop()
    }

    private fun stop() {
        if (navigating) application.stopService(serviceIntent())
        navigator = null
        guidance = null
        following = false
    }

    fun toggleMute() {
        muted = !muted
        voice.muted = muted
        if (muted) voice.stop()
    }

    private fun serviceIntent() = Intent(application, NavigationService::class.java)

    /** Selected profile first, so its route shows while the others still compute. */
    private fun plan() {
        val router = router ?: return
        val from = origin ?: return
        val to = destination?.location ?: return
        planning?.cancel()
        routes = emptyMap()
        val order = listOf(profile) + Profile.entries.minus(profile)
        planning = launch {
            for (option in order) {
                val route = withContext(Dispatchers.Default) {
                    router.route(from, to, mode, option) { isActive }
                }
                routes = routes + (option to route)
            }
        }
    }

    /** Measurably moving, clearly sharper, or too far away to be the same spot. */
    private fun isNewPosition(update: Location): Boolean {
        val shown = location ?: return true
        val noise = maxOf(MOVING_METERS_PER_SECOND, update.speedAccuracyMetersPerSecond)
        return update.speed > noise ||
            update.accuracy < shown.accuracy / 2 ||
            update.distanceTo(shown) > update.accuracy + shown.accuracy
    }

    private fun onLocation(update: Location) {
        if (!isNewPosition(update)) return
        val first = location == null
        location = update
        if (first && destination == null) following = true
        if (first && destination != null && !navigating) plan()
        val point = LatLon(update.latitude, update.longitude)
        val next = navigator?.update(point) ?: return
        show(next)
        if (next.arrived) clear()
        if (next.offRoute) reroute(point)
    }

    private fun show(next: Guidance, prefix: Int? = null) {
        guidance = next
        val announcement = next.announcement
        announcement?.let { application.showNavigation(application.spoken(it)) }
        if (announcement == null && prefix == null) return
        voice.speak {
            listOfNotNull(prefix?.let { getString(it) }, announcement?.let { spoken(it) })
                .joinToString(". ")
        }
    }

    private fun reroute(point: LatLon) {
        val router = router ?: return
        val target = destination?.location ?: return
        val now = System.currentTimeMillis()
        if (now - lastReroute < REROUTE_INTERVAL_MILLIS) return
        lastReroute = now
        launch {
            val route = withContext(Dispatchers.Default) {
                router.route(point, target, mode, profile)
            } ?: return@launch
            if (!navigating) return@launch
            routes = routes + (profile to route)
            val navigator = Navigator(route, mode).also { navigator = it }
            show(navigator.update(point), R.string.rerouting)
        }
    }
}

private inline fun <reified T : Enum<T>> android.content.SharedPreferences.enum(
    key: String,
    default: T,
) = enumValues<T>().firstOrNull { it.name == getString(key, null) } ?: default

private const val GRAPH = "graph"
private const val HEIGHTS = "heights"
private const val TILES = "tiles.pmtiles"
private val FILES = listOf(GRAPH, HEIGHTS, TILES)
private val PIECE = Regex("(.+)\\.(\\d+)of(\\d+)")
private const val BUFFER_BYTES = 1 shl 20

private class CountingStream(input: InputStream, private val counted: (Int) -> Unit) :
    FilterInputStream(input) {
    override fun read(buffer: ByteArray, offset: Int, length: Int) =
        super.read(buffer, offset, length).also { if (it > 0) counted(it) }
}
private const val MAX_RESULTS = 8
private const val LOCATION_INTERVAL_MILLIS = 1000L
private const val REROUTE_INTERVAL_MILLIS = 8_000L
private const val MOVING_METERS_PER_SECOND = 0.5f
