package kiesel

import java.io.File
import java.io.OutputStream
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.nio.ShortBuffer
import java.text.Normalizer
import java.util.BitSet
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val latitude: Double, val longitude: Double) {
    fun distanceTo(other: LatLon): Double {
        val latitudeDelta = Math.toRadians(other.latitude - latitude)
        val longitudeDelta = Math.toRadians(other.longitude - longitude)
        val a = sin(latitudeDelta / 2).let { it * it } +
            cos(Math.toRadians(latitude)) * cos(Math.toRadians(other.latitude)) *
            sin(longitudeDelta / 2).let { it * it }
        return 2 * EARTH_RADIUS_METERS * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Degrees clockwise from north. */
    fun bearingTo(other: LatLon): Double {
        val east = (other.longitude - longitude) * cos(Math.toRadians(latitude))
        return (Math.toDegrees(atan2(east, other.latitude - latitude)) + 360) % 360
    }
}

enum class Road(val traffic: Int) {
    Steps(0), Footway(0), Path(0), Track(0), Cycleway(0), Calm(1), Street(2), Main(3)
}

/** [rank]: 3 city, 2 town, 1 village or suburb, 0 anything else. */
class Place(val name: String, val location: LatLon, val rank: Int = 0)

/**
 * Names packed into one string (one per line) so a country stays searchable.
 * [keys] is the same text folded by [searchKey], so offsets match.
 */
class Places internal constructor(
    private val names: String,
    private val keys: String,
    private val starts: IntBuffer,
    private val ranks: ByteBuffer,
    private val latitudes: FloatBuffer,
    private val longitudes: FloatBuffer,
) {
    val size get() = ranks.limit()

    internal fun columns() =
        arrayOf<Buffer>(text(names), text(keys), starts, ranks, latitudes, longitudes)

    internal companion object {
        fun read(columns: Columns) = with(columns) {
            Places(text(), text(), ints(), bytes(), floats(), floats())
        }
    }

    /** Whole-name prefixes first, then word starts; bigger places, then nearer ones. */
    fun search(query: String, near: LatLon, limit: Int): List<Place> {
        val needle = searchKey(query.trim().replace('\n', ' '))
        if (needle.isEmpty()) return emptyList()
        val hits = ArrayList<Int>()
        var position = keys.indexOf(needle)
        while (position >= 0 && hits.size < MAX_SEARCH_HITS) {
            val index = partition(starts.limit()) { starts[it] <= position } - 1
            hits += index
            position = keys.indexOf(needle, starts[index + 1])
        }
        return hits.sortedWith(
            compareBy(
                { matchQuality(keys.substring(starts[it], starts[it + 1] - 1), needle) },
                { -ranks[it] },
                { near.distanceTo(locationAt(it)) },
            )
        ).map(::placeAt).distinctBy(::duplicateKey).take(limit)
    }

    private fun matchQuality(key: String, needle: String) = when {
        key.startsWith(needle) -> 0
        key.split(' ', '-', '/').any { it.startsWith(needle) } -> 1
        else -> 2
    }

    /** Same name within roughly a kilometer counts as one place. */
    private fun duplicateKey(place: Place) = Triple(
        place.name,
        (place.location.latitude * 100).toInt(),
        (place.location.longitude * 100).toInt(),
    )

    private fun locationAt(index: Int) =
        LatLon(latitudes[index].toDouble(), longitudes[index].toDouble())

    private fun placeAt(index: Int) = Place(
        names.substring(starts[index], starts[index + 1] - 1),
        locationAt(index),
        ranks[index].toInt(),
    )
}

/** Lowercase without accents, one char per char: "Zürich" → "zurich". */
fun searchKey(text: String) = buildString(text.length) {
    text.forEach {
        append(Normalizer.normalize("$it", Normalizer.Form.NFD)[0].lowercaseChar())
    }
}

class Snap(val edge: Int, val along: Double, val point: LatLon, val offset: Double)

/**
 * Junction graph. Undirected edges carry simplified geometry (interior points only).
 * Arc = edge * 2 (from→to) or edge * 2 + 1 (to→from).
 */
class Graph internal constructor(
    val latitudes: FloatBuffer,
    val longitudes: FloatBuffer,
    val from: IntBuffer,
    val to: IntBuffer,
    val lengths: FloatBuffer,
    val roads: ByteBuffer,
    val surfaces: ByteBuffer,
    val scenery: ByteBuffer,
    val crowding: ByteBuffer,
    val oneway: ByteBuffer,
    val roundabout: ByteBuffer,
    val streets: IntBuffer,
    val rise: ShortBuffer,
    val fall: ShortBuffer,
    val shapeStarts: IntBuffer,
    val shapeLatitudes: FloatBuffer,
    val shapeLongitudes: FloatBuffer,
    val streetNames: List<String>,
    val places: Places,
    index: Index? = null,
    bearings: ShortBuffer? = null,
) {
    val nodeCount get() = latitudes.limit()

    val edgeCount get() = from.limit()

    val arcCount get() = edgeCount * 2

    @PublishedApi
    internal val index = index ?: Index.of(this)

    /** Start and end bearing per arc in whole degrees, computed once for routing. */
    private val bearings = bearings ?: ShortBuffer.wrap(ShortArray(arcCount * 2) {
        stretchBearing(it / 2, atEnd = it % 2 == 1).roundToInt().toShort()
    })

    fun point(node: Int) = LatLon(latitudes[node].toDouble(), longitudes[node].toDouble())

    /** South-west and north-east corner around all nodes. */
    fun bounds(): Pair<LatLon, LatLon> {
        val nodes = 0 until nodeCount
        fun lowest(values: FloatBuffer) = nodes.minOf { values[it].toDouble() }
        fun highest(values: FloatBuffer) = nodes.maxOf { values[it].toDouble() }
        val southWest = LatLon(lowest(latitudes), lowest(longitudes))
        return southWest to LatLon(highest(latitudes), highest(longitudes))
    }

    fun length(edge: Int) = lengths[edge].toDouble()

    fun road(edge: Int) = Road.entries[roads[edge].toInt()]

    fun surface(edge: Int) = surfaces[edge].toInt()

    fun scenery(edge: Int) = scenery[edge].toInt()

    fun crowding(edge: Int) = crowding[edge].toInt()

    fun isOneway(edge: Int) = oneway[edge] > 0

    fun isRoundabout(edge: Int) = roundabout[edge] > 0

    /** Meters climbed when walking the arc. */
    fun climb(arc: Int) =
        (if (arc % 2 == 0) rise[arc / 2] else fall[arc / 2]) / DECIMETERS

    fun arcStart(arc: Int) = if (arc % 2 == 0) from[arc / 2] else to[arc / 2]

    fun arcEnd(arc: Int) = if (arc % 2 == 0) to[arc / 2] else from[arc / 2]

    fun degree(node: Int) = index.first[node + 1] - index.first[node]

    fun streetName(edge: Int) = streetNames.getOrNull(streets[edge])

    inline fun forEachOutgoing(node: Int, action: (Int) -> Unit) {
        for (position in index.first[node] until index.first[node + 1]) {
            action(index.arcs[position])
        }
    }

    fun pointCount(edge: Int) = shapeStarts[edge + 1] - shapeStarts[edge] + 2

    /** Point [position] of the edge counted along the arc's direction. */
    fun arcLatitude(arc: Int, position: Int) =
        coordinate(arc, position, latitudes, shapeLatitudes)

    fun arcLongitude(arc: Int, position: Int) =
        coordinate(arc, position, longitudes, shapeLongitudes)

    fun latitudeAt(edge: Int, position: Int) = arcLatitude(edge * 2, position)

    fun longitudeAt(edge: Int, position: Int) = arcLongitude(edge * 2, position)

    fun arcPoints(arc: Int) = List(pointCount(arc / 2)) {
        LatLon(arcLatitude(arc, it), arcLongitude(arc, it))
    }

    /** Degrees clockwise from north over the first stretch of the arc. */
    fun startBearing(arc: Int) = bearings[arc * 2].toDouble()

    /** Degrees clockwise from north over the last stretch of the arc. */
    fun endBearing(arc: Int) = bearings[arc * 2 + 1].toDouble()

    /** Degrees in -180..180, positive = right. */
    fun turnAngle(arrival: Int, departure: Int) =
        (startBearing(departure) - endBearing(arrival) + 540) % 360 - 180

    /** Closest point on any edge, widening the search until something is found. */
    fun snap(point: LatLon): Snap? {
        for (radius in SNAP_RADII) {
            if (index.edgesWithin(point, radius).isEmpty()) continue
            return index.edgesWithin(point, radius + 1).distinct()
                .map { snapOn(it, point) }.minBy { it.offset }
        }
        return null
    }

    /** Writes the format [read] maps; leaves [output] open. */
    fun write(output: OutputStream) = output.writeColumns(
        latitudes, longitudes, from, to, lengths, roads, surfaces, scenery, crowding,
        oneway, roundabout, streets, rise, fall, shapeStarts, shapeLatitudes,
        shapeLongitudes, text(streetNames.joinToString("\n")), *places.columns(),
        index.first, index.arcs, index.entries, bearings,
    )

    private fun coordinate(
        arc: Int,
        position: Int,
        nodes: FloatBuffer,
        shape: FloatBuffer,
    ): Double {
        val edge = arc / 2
        val last = pointCount(edge) - 1
        val forward = if (arc % 2 == 0) position else last - position
        return when (forward) {
            0 -> nodes[from[edge]]
            last -> nodes[to[edge]]
            else -> shape[shapeStarts[edge] + forward - 1]
        }.toDouble()
    }

    /** Bearing between the arc end and a point [BEARING_METERS] into the arc. */
    private fun stretchBearing(arc: Int, atEnd: Boolean): Double {
        val last = pointCount(arc / 2) - 1
        val anchor = if (atEnd) last else 0
        val step = if (atEnd) -1 else 1
        var far = anchor + step
        while (far != last - anchor && meters(arc, anchor, far) < BEARING_METERS) {
            far += step
        }
        return if (atEnd) bearing(arc, far, anchor) else bearing(arc, anchor, far)
    }

    private fun meters(arc: Int, first: Int, second: Int): Double {
        val north =
            (arcLatitude(arc, second) - arcLatitude(arc, first)) * METERS_PER_DEGREE
        val east = (arcLongitude(arc, second) - arcLongitude(arc, first)) *
            METERS_PER_DEGREE * cos(Math.toRadians(arcLatitude(arc, first)))
        return hypot(east, north)
    }

    private fun bearing(arc: Int, from: Int, to: Int): Double {
        val east = (arcLongitude(arc, to) - arcLongitude(arc, from)) *
            cos(Math.toRadians(arcLatitude(arc, from)))
        val north = arcLatitude(arc, to) - arcLatitude(arc, from)
        return Math.toDegrees(atan2(east, north))
    }

    private fun snapOn(edge: Int, point: LatLon): Snap {
        val points = arcPoints(edge * 2)
        var best = Snap(edge, 0.0, points[0], Double.MAX_VALUE)
        var covered = 0.0
        for (i in 0 until points.size - 1) {
            val projection = project(point, points[i], points[i + 1])
            val segment = points[i].distanceTo(points[i + 1])
            if (projection.offset < best.offset) {
                val latitude = points[i].latitude +
                    projection.share * (points[i + 1].latitude - points[i].latitude)
                val longitude = points[i].longitude +
                    projection.share * (points[i + 1].longitude - points[i].longitude)
                best = Snap(
                    edge,
                    covered + projection.share * segment,
                    LatLon(latitude, longitude),
                    projection.offset,
                )
            }
            covered += segment
        }
        return best
    }

    companion object {
        /** Maps a [write] file: routing reads it in place, off the heap. */
        fun read(file: File) = with(Columns(mapFile(file))) {
            Graph(
                latitudes = floats(),
                longitudes = floats(),
                from = ints(),
                to = ints(),
                lengths = floats(),
                roads = bytes(),
                surfaces = bytes(),
                scenery = bytes(),
                crowding = bytes(),
                oneway = bytes(),
                roundabout = bytes(),
                streets = ints(),
                rise = shorts(),
                fall = shorts(),
                shapeStarts = ints(),
                shapeLatitudes = floats(),
                shapeLongitudes = floats(),
                streetNames = text().split('\n'),
                places = Places.read(this),
                index = Index(ints(), ints(), longs()),
                bearings = shorts(),
            )
        }
    }
}

internal class Projection(val share: Double, val offset: Double)

/** Where [point] falls on the segment (share 0..1) and how far away it is, in meters. */
internal fun project(point: LatLon, start: LatLon, end: LatLon): Projection {
    val scale = cos(Math.toRadians(start.latitude)) * METERS_PER_DEGREE
    val endEast = (end.longitude - start.longitude) * scale
    val endNorth = (end.latitude - start.latitude) * METERS_PER_DEGREE
    val east = (point.longitude - start.longitude) * scale
    val north = (point.latitude - start.latitude) * METERS_PER_DEGREE
    val squared = endEast * endEast + endNorth * endNorth
    val share = if (squared == 0.0) 0.0 else
        ((east * endEast + north * endNorth) / squared).coerceIn(0.0, 1.0)
    return Projection(share, hypot(east - share * endEast, north - share * endNorth))
}

/**
 * Outgoing arcs per node and a cell grid of edges, as sorted (cell id, edge) entries;
 * built once, stored with the map.
 */
@PublishedApi
internal class Index(val first: IntBuffer, val arcs: IntBuffer, val entries: LongBuffer) {
    /** Edges with a point inside the square of cells around [point]. */
    fun edgesWithin(point: LatLon, radius: Int): List<Int> {
        val row = cellIndex(point.latitude)
        val column = cellIndex(point.longitude)
        return (row - radius..row + radius).flatMap { r ->
            val low = lowerBound(cellId(r, column - radius) shl EDGE_BITS)
            val high = lowerBound((cellId(r, column + radius) + 1) shl EDGE_BITS)
            (low until high).map { (entries[it] and EDGE_MASK).toInt() }
        }
    }

    private fun lowerBound(key: Long) = partition(entries.limit()) { entries[it] < key }

    companion object {
        fun of(graph: Graph): Index {
            val first = IntArray(graph.nodeCount + 1)
            for (edge in 0 until graph.edgeCount) {
                first[graph.from[edge] + 1]++
                first[graph.to[edge] + 1]++
            }
            for (node in 0 until graph.nodeCount) first[node + 1] += first[node]
            val cursor = first.copyOf()
            val arcs = IntArray(graph.arcCount)
            for (arc in 0 until graph.arcCount) arcs[cursor[graph.arcStart(arc)]++] = arc
            var count = 0
            forEachEntry(graph) { count++ }
            val entries = LongArray(count)
            count = 0
            forEachEntry(graph) { entries[count++] = it }
            entries.sort()
            return Index(
                IntBuffer.wrap(first), IntBuffer.wrap(arcs), LongBuffer.wrap(entries),
            )
        }
    }
}

/** One entry per cell each edge touches: (cell id, edge). */
private inline fun forEachEntry(graph: Graph, action: (Long) -> Unit) {
    for (edge in 0 until graph.edgeCount) {
        var last = -1L
        for (i in 0 until graph.pointCount(edge) - 1) {
            val steps = sampleSteps(graph, edge, i)
            for (step in 0..steps) {
                val id = cellAlong(graph, edge, i, step.toDouble() / steps)
                if (id != last) action((id shl EDGE_BITS) or edge.toLong())
                last = id
            }
        }
    }
}

private fun cellAlong(graph: Graph, edge: Int, segment: Int, share: Double): Long {
    val next = segment + 1
    fun at(coordinate: (Int, Int) -> Double) =
        lerp(coordinate(edge, segment), coordinate(edge, next), share)
    val latitude = at(graph::latitudeAt)
    val longitude = at(graph::longitudeAt)
    return cellId(cellIndex(latitude), cellIndex(longitude))
}

private fun sampleSteps(graph: Graph, edge: Int, segment: Int): Int {
    val next = segment + 1
    val latitudeSpan = graph.latitudeAt(edge, next) - graph.latitudeAt(edge, segment)
    val longitudeSpan =
        graph.longitudeAt(edge, next) - graph.longitudeAt(edge, segment)
    val cells = maxOf(abs(latitudeSpan), abs(longitudeSpan)) * CELLS_PER_DEGREE * 2
    return maxOf(1, ceil(cells).toInt())
}

/** Collects raw map data and compiles it into an immutable [Graph]. */
class GraphBuilder(private val heights: (Double, Double) -> Double = { _, _ -> 0.0 }) {
    private class Edge(
        val from: Int,
        val to: Int,
        val road: Road,
        val surface: Int,
        val oneway: Boolean,
        val roundabout: Boolean,
        val street: String?,
        val via: DoubleArray,
    )

    private class Area(val water: Boolean, val ring: DoubleArray)

    private val nodes = ArrayList<LatLon>()
    private val edges = ArrayList<Edge>()
    private val places = ArrayList<Place>()
    private val areas = ArrayList<Area>()

    fun addNode(latitude: Double, longitude: Double): Int {
        nodes += LatLon(latitude, longitude)
        return nodes.lastIndex
    }

    /** [via] holds interior points as interleaved latitude, longitude. */
    fun addEdge(
        from: Int,
        to: Int,
        road: Road,
        surface: Int = 1,
        oneway: Boolean = false,
        street: String? = null,
        via: DoubleArray = DoubleArray(0),
        roundabout: Boolean = false,
    ) {
        val name = street?.replace('\n', ' ')
        edges += Edge(from, to, road, surface, oneway, roundabout, name, via)
    }

    fun addPlace(place: Place) {
        places += place
    }

    /** [ring] holds closed-polygon vertices as interleaved latitude, longitude. */
    fun addArea(water: Boolean, ring: DoubleArray) {
        areas += Area(water, ring)
    }

    fun build(): Graph {
        val kept = largestComponent()
        val remap = IntArray(nodes.size) { -1 }
        kept.forEach {
            remap[it.from] = 0
            remap[it.to] = 0
        }
        var count = 0
        for (node in remap.indices) if (remap[node] == 0) remap[node] = count++
        val points = nodes.filterIndexed { node, _ -> remap[node] >= 0 }
        val grid = Grid(extent(points))
        kept.forEach { grid.count(it.via) }
        points.forEach { grid.count(doubleArrayOf(it.latitude, it.longitude)) }
        areas.forEach { grid.fill(it.ring) }

        val compiled = kept.map { compile(it, grid) }
        val streetNames = kept.mapNotNull { it.street }.distinct()
        val streetIds = streetNames.withIndex().associate { it.value to it.index }
        val shapeStarts = IntArray(kept.size + 1)
        compiled.forEachIndexed { i, edge ->
            shapeStarts[i + 1] = shapeStarts[i] + edge.shape.size / 2
        }
        val shape = DoubleArray(shapeStarts.last() * 2)
        for (i in compiled.indices) compiled[i].shape.copyInto(shape, shapeStarts[i] * 2)
        fun floats(size: Int, value: (Int) -> Double) =
            FloatBuffer.wrap(FloatArray(size) { value(it).toFloat() })
        fun bytes(value: (Int) -> Int) =
            ByteBuffer.wrap(ByteArray(kept.size) { value(it).toByte() })
        fun shorts(value: (Int) -> Double) =
            ShortBuffer.wrap(ShortArray(kept.size) { decimeters(value(it)) })
        fun ints(value: (Int) -> Int) = IntBuffer.wrap(IntArray(kept.size, value))
        return Graph(
            latitudes = floats(points.size) { points[it].latitude },
            longitudes = floats(points.size) { points[it].longitude },
            from = ints { remap[kept[it].from] },
            to = ints { remap[kept[it].to] },
            lengths = floats(kept.size) { compiled[it].length },
            roads = bytes { kept[it].road.ordinal },
            surfaces = bytes { kept[it].surface },
            scenery = bytes { compiled[it].scenery },
            crowding = bytes { compiled[it].crowding },
            oneway = bytes { if (kept[it].oneway) 1 else 0 },
            roundabout = bytes { if (kept[it].roundabout) 1 else 0 },
            streets = ints { streetIds[kept[it].street] ?: -1 },
            rise = shorts { compiled[it].rise },
            fall = shorts { compiled[it].fall },
            shapeStarts = IntBuffer.wrap(shapeStarts),
            shapeLatitudes = floats(shape.size / 2) { shape[it * 2] },
            shapeLongitudes = floats(shape.size / 2) { shape[it * 2 + 1] },
            streetNames = streetNames,
            places = compilePlaces(),
        )
    }

    private fun extent(points: List<LatLon>) = doubleArrayOf(
        points.minOf { it.latitude },
        points.maxOf { it.latitude },
        points.minOf { it.longitude },
        points.maxOf { it.longitude },
    )

    private class Compiled(
        val shape: DoubleArray,
        val length: Double,
        val scenery: Int,
        val crowding: Int,
        val rise: Double,
        val fall: Double,
    )

    private fun compile(edge: Edge, grid: Grid): Compiled {
        val raw = rawPoints(edge)
        val simplified = simplify(raw, ROAD_TOLERANCE_METERS)
        val vertices = raw.size / 2
        val scenery = (0 until vertices).sumOf {
            grid.scenery(raw[it * 2], raw[it * 2 + 1])
        }
        val crowding = (0 until vertices).sumOf {
            grid.crowding(raw[it * 2], raw[it * 2 + 1])
        }
        val (rise, fall) = climb(raw)
        return Compiled(
            simplified.copyOfRange(2, simplified.size - 2),
            pathLength(simplified),
            (scenery.toDouble() / vertices).roundToInt(),
            (crowding.toDouble() / vertices).roundToInt(),
            rise,
            fall,
        )
    }

    private fun rawPoints(edge: Edge): DoubleArray {
        val start = nodes[edge.from]
        val end = nodes[edge.to]
        return doubleArrayOf(start.latitude, start.longitude) + edge.via +
            doubleArrayOf(end.latitude, end.longitude)
    }

    /** Ascent and descent, ignoring wobble below [CLIMB_THRESHOLD_METERS]. */
    private fun climb(raw: DoubleArray): Pair<Double, Double> {
        val samples = ArrayList<Double>()
        for (i in 0 until raw.size / 2 - 1) {
            val start = LatLon(raw[i * 2], raw[i * 2 + 1])
            val end = LatLon(raw[i * 2 + 2], raw[i * 2 + 3])
            val steps = maxOf(1, ceil(start.distanceTo(end) / SAMPLE_METERS).toInt())
            for (step in 0 until steps) {
                val share = step.toDouble() / steps
                samples += heights(
                    lerp(start.latitude, end.latitude, share),
                    lerp(start.longitude, end.longitude, share),
                )
            }
        }
        samples += heights(raw[raw.size - 2], raw[raw.size - 1])
        var rise = 0.0
        var fall = 0.0
        var reference = samples.first()
        for (height in samples) {
            if (abs(height - reference) < CLIMB_THRESHOLD_METERS) continue
            val change = height - reference
            if (change > 0) rise += change else fall -= change
            reference = height
        }
        val rest = samples.last() - reference
        if (rest > 0) rise += rest else fall -= rest
        return rise to fall
    }

    private fun largestComponent(): List<Edge> {
        val parent = IntArray(nodes.size) { it }
        fun find(node: Int): Int {
            var root = node
            while (parent[root] != root) root = parent[root]
            var current = node
            while (parent[current] != root) {
                val next = parent[current]
                parent[current] = root
                current = next
            }
            return root
        }
        edges.forEach { parent[find(it.from)] = find(it.to) }
        val sizes = IntArray(nodes.size)
        edges.forEach { sizes[find(it.from)]++ }
        val largest = sizes.indices.maxByOrNull { sizes[it] }
        return edges.filter { find(it.from) == largest }
    }

    private fun compilePlaces(): Places {
        val names = StringBuilder()
        val starts = IntArray(places.size + 1)
        places.forEachIndexed { i, place ->
            starts[i] = names.length
            names.append(place.name.replace('\n', ' ')).append('\n')
        }
        starts[places.size] = names.length
        fun floats(value: (Place) -> Double) =
            FloatBuffer.wrap(FloatArray(places.size) { value(places[it]).toFloat() })
        return Places(
            names.toString(),
            searchKey(names.toString()),
            IntBuffer.wrap(starts),
            ByteBuffer.wrap(ByteArray(places.size) { places[it].rank.toByte() }),
            floats { it.location.latitude },
            floats { it.location.longitude },
        )
    }
}

/** Node density and green/water coverage on a ~100 m cell grid over the data's extent. */
private class Grid(extent: DoubleArray) {
    private val minRow: Int
    private val minColumn: Int
    private val rows: Int
    private val columns: Int
    private val green: BitSet
    private val density: ShortArray

    init {
        minRow = cellIndex(extent[0]) - MARGIN_CELLS
        minColumn = cellIndex(extent[2]) - MARGIN_CELLS
        rows = cellIndex(extent[1]) - minRow + MARGIN_CELLS + 1
        columns = cellIndex(extent[3]) - minColumn + MARGIN_CELLS + 1
        green = BitSet(rows * columns)
        density = ShortArray(rows * columns)
    }

    fun count(points: DoubleArray) {
        for (i in 0 until points.size / 2) {
            val cell = slot(cellIndex(points[i * 2]), cellIndex(points[i * 2 + 1]))
            if (cell >= 0 && density[cell] < Short.MAX_VALUE) density[cell]++
        }
    }

    fun fill(ring: DoubleArray) {
        val latitudes = (0 until ring.size / 2).map { ring[it * 2] }
        val lowest = cellIndex(latitudes.min()).coerceAtLeast(minRow)
        val highest = cellIndex(latitudes.max()).coerceAtMost(minRow + rows - 1)
        for (row in lowest..highest) {
            val crossings = crossings(ring, (row + 0.5) / CELLS_PER_DEGREE)
            for (pair in crossings.chunked(2).filter { it.size == 2 }) {
                for (column in cellIndex(pair[0])..cellIndex(pair[1])) mark(row, column)
            }
        }
        mark(cellIndex(ring[0]), cellIndex(ring[1]))
    }

    /** Longitudes where the ring crosses the given latitude, ascending. */
    private fun crossings(ring: DoubleArray, latitude: Double): List<Double> {
        val result = ArrayList<Double>()
        var previous = ring.size / 2 - 1
        for (i in 0 until ring.size / 2) {
            val startLatitude = ring[previous * 2]
            val endLatitude = ring[i * 2]
            if ((endLatitude > latitude) != (startLatitude > latitude)) {
                val share = (latitude - startLatitude) / (endLatitude - startLatitude)
                result += lerp(ring[previous * 2 + 1], ring[i * 2 + 1], share)
            }
            previous = i
        }
        return result.sorted()
    }

    fun scenery(latitude: Double, longitude: Double): Int {
        val row = cellIndex(latitude)
        val column = cellIndex(longitude)
        return when {
            isGreen(row, column) -> 2
            neighbors(row, column).any { (r, c) -> isGreen(r, c) } -> 1
            else -> 0
        }
    }

    fun crowding(latitude: Double, longitude: Double): Int {
        val row = cellIndex(latitude)
        val column = cellIndex(longitude)
        val nodes = neighbors(row, column).sumOf { (r, c) ->
            density.getOrElse(slot(r, c)) { 0 }.toInt()
        }
        return CROWDING_STEPS.count { nodes >= it }
    }

    private fun neighbors(row: Int, column: Int) =
        (-1..1).flatMap { dr -> (-1..1).map { dc -> (row + dr) to (column + dc) } }

    private fun mark(row: Int, column: Int) {
        val cell = slot(row, column)
        if (cell >= 0) green.set(cell)
    }

    private fun isGreen(row: Int, column: Int) =
        slot(row, column).let { it >= 0 && green[it] }

    private fun slot(row: Int, column: Int): Int {
        val r = row - minRow
        val c = column - minColumn
        return if (r in 0 until rows && c in 0 until columns) r * columns + c else -1
    }
}

/** Douglas–Peucker on interleaved latitude, longitude; endpoints are always kept. */
internal fun simplify(points: DoubleArray, tolerance: Double): DoubleArray {
    val count = points.size / 2
    if (count <= 2) return points
    fun at(i: Int) = LatLon(points[i * 2], points[i * 2 + 1])
    val keep = BooleanArray(count)
    keep[0] = true
    keep[count - 1] = true
    val pending = ArrayDeque(listOf(intArrayOf(0, count - 1)))
    while (pending.isNotEmpty()) {
        val (first, last) = pending.removeLast()
        val farthest = (first + 1 until last)
            .maxByOrNull { project(at(it), at(first), at(last)).offset } ?: continue
        if (project(at(farthest), at(first), at(last)).offset <= tolerance) continue
        keep[farthest] = true
        pending.add(intArrayOf(first, farthest))
        pending.add(intArrayOf(farthest, last))
    }
    return (0 until count).filter { keep[it] }
        .flatMap { listOf(points[it * 2], points[it * 2 + 1]) }
        .toDoubleArray()
}

private fun decimeters(meters: Double) =
    (meters * DECIMETERS).roundToInt().coerceAtMost(Short.MAX_VALUE.toInt()).toShort()

private fun pathLength(points: DoubleArray) = (0 until points.size / 2 - 1).sumOf {
    LatLon(points[it * 2], points[it * 2 + 1])
        .distanceTo(LatLon(points[it * 2 + 2], points[it * 2 + 3]))
}


internal fun lerp(start: Double, end: Double, share: Double) =
    start + (end - start) * share

/** First index in 0..[size] where [isBefore] is false; it must be true, then false. */
internal inline fun partition(size: Int, isBefore: (Int) -> Boolean): Int {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (isBefore(middle)) low = middle + 1 else high = middle
    }
    return low
}

private const val EARTH_RADIUS_METERS = 6_371_000.0
private const val METERS_PER_DEGREE = 111_320.0
private const val CELLS_PER_DEGREE = 1000
private const val ROAD_TOLERANCE_METERS = 6.0
private const val BEARING_METERS = 15.0
private const val DECIMETERS = 10.0
private const val SAMPLE_METERS = 30.0
private const val CLIMB_THRESHOLD_METERS = 5.0
private const val MAX_SEARCH_HITS = 3000
private const val MARGIN_CELLS = 2
private const val EDGE_BITS = 24
private const val EDGE_MASK = (1L shl EDGE_BITS) - 1
private val SNAP_RADII = listOf(1, 2, 4, 8, 16, 32, 64)

/** Node counts in a 3×3 cell neighborhood (≈ 300 m) that raise crowding 0→3. */
private val CROWDING_STEPS = listOf(40, 150, 400)

private fun cellIndex(degrees: Double) = floor(degrees * CELLS_PER_DEGREE).toInt()

private fun cellId(row: Int, column: Int) =
    ((row + 100_000L) shl 20) or (column + 400_000L)
