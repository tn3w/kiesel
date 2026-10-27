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
}

enum class Road(val traffic: Int) {
    Steps(0), Footway(0), Path(0), Track(0), Cycleway(0), Calm(1), Street(2), Main(3)
}

/** [rank]: 3 city, 2 town, 1 village or suburb, 0 anything else. */
class Place(val name: String, val location: LatLon, val rank: Int = 0)

/** Searchable names; [keys] holds the same names folded by [searchKey]. */
class Places internal constructor(
    private val names: Texts,
    private val keys: Texts,
    private val ranks: ByteBuffer,
    private val latitudes: FloatBuffer,
    private val longitudes: FloatBuffer,
) {
    val size get() = ranks.limit()

    internal fun columns() =
        arrayOf(*names.columns(), *keys.columns(), ranks, latitudes, longitudes)

    internal companion object {
        fun read(columns: Columns) = with(columns) {
            Places(texts(), texts(), bytes(), floats(), floats())
        }
    }

    /** Whole-name prefixes first, then word starts; bigger places, then nearer ones. */
    fun search(query: String, near: LatLon, limit: Int): List<Place> {
        val needle = searchKey(query.trim())
        if (needle.isEmpty()) return emptyList()
        val hits = keys.containing(needle.encodeToByteArray(), MAX_SEARCH_HITS)
        val quality = hits.associateWith { matchQuality(keys[it], needle) }
        val meters = hits.associateWith { near.distanceTo(locationAt(it)) }
        return hits.sortedWith(compareBy({ quality[it] }, { -ranks[it] }, { meters[it] }))
            .map(::placeAt).distinctBy(::duplicateKey).take(limit)
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

    private fun placeAt(index: Int) =
        Place(names[index], locationAt(index), ranks[index].toInt())
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
    val streetNames: Texts,
    val places: Places,
    index: Index? = null,
    bearings: ByteBuffer? = null,
) {
    val nodeCount get() = latitudes.limit()

    val edgeCount get() = from.limit()

    val arcCount get() = edgeCount * 2

    @PublishedApi
    internal val index = index ?: Index.of(this)

    /** Start and end bearing per arc, computed once for routing. */
    private val bearings = bearings ?: ByteBuffer.wrap(ByteArray(arcCount * 2) {
        bearingByte(stretchBearing(it / 2, atEnd = it % 2 == 1))
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

    fun streetName(edge: Int) = streets[edge].takeIf { it >= 0 }?.let { streetNames[it] }

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
    fun startBearing(arc: Int) = degrees(bearings[arc * 2])

    /** Degrees clockwise from north over the last stretch of the arc. */
    fun endBearing(arc: Int) = degrees(bearings[arc * 2 + 1])

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
        IntBuffer.wrap(intArrayOf(FORMAT)), latitudes, longitudes, from, to, lengths,
        roads, surfaces, scenery, crowding, oneway, roundabout, streets, rise, fall,
        shapeStarts, shapeLatitudes, shapeLongitudes, *streetNames.columns(),
        *places.columns(), index.first, index.arcs, index.entries, bearings,
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
        fun read(file: File) = readColumns(file) {
            check(ints()[0] == FORMAT) { "Pack format changed: download the map again" }
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
                streetNames = texts(),
                places = Places.read(this),
                index = Index(ints(), ints(), longs()),
                bearings = bytes(),
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
        val row = indexCell(point.latitude)
        val column = indexCell(point.longitude)
        return (row - radius..row + radius).flatMap { r ->
            val low = lowerBound(cellId(r, column - radius) shl EDGE_BITS)
            val high = lowerBound((cellId(r, column + radius) + 1) shl EDGE_BITS)
            (low until high).map { (entries[it] and EDGE_MASK).toInt() }
        }
    }

    private fun lowerBound(key: Long) = partition(entries.limit()) { entries[it] < key }

    companion object {
        fun of(graph: Graph): Index {
            check(graph.edgeCount <= EDGE_MASK) { "Too many edges: ${graph.edgeCount}" }
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
    return cellId(indexCell(latitude), indexCell(longitude))
}

private fun sampleSteps(graph: Graph, edge: Int, segment: Int): Int {
    val next = segment + 1
    val latitudeSpan = graph.latitudeAt(edge, next) - graph.latitudeAt(edge, segment)
    val longitudeSpan =
        graph.longitudeAt(edge, next) - graph.longitudeAt(edge, segment)
    val cells = maxOf(abs(latitudeSpan), abs(longitudeSpan)) * INDEX_CELLS_PER_DEGREE * 2
    return maxOf(1, ceil(cells).toInt())
}

/**
 * Collects raw map data in primitive arrays, so a whole country fits in memory, and
 * compiles it into an immutable [Graph].
 */
class GraphBuilder(private val heights: (Double, Double) -> Double = { _, _ -> 0.0 }) {
    private val nodeLatitudes = Floats()
    private val nodeLongitudes = Floats()
    private val from = Ints()
    private val to = Ints()
    private val roads = Ints()
    private val surfaces = Ints()
    private val oneway = Ints()
    private val roundabout = Ints()
    private val streets = Ints()
    private val viaStarts = Ints().apply { add(0) }
    private val via = Floats()
    private val streetNames = ArrayList<String>()
    private val streetIds = HashMap<String, Int>()
    private val places = ArrayList<Place>()
    private val areaStarts = Ints().apply { add(0) }
    private val areaPoints = Floats()

    fun addNode(latitude: Double, longitude: Double): Int {
        nodeLatitudes.add(latitude.toFloat())
        nodeLongitudes.add(longitude.toFloat())
        return nodeLatitudes.size - 1
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
        this.from.add(from)
        this.to.add(to)
        roads.add(road.ordinal)
        surfaces.add(surface)
        this.oneway.add(if (oneway) 1 else 0)
        this.roundabout.add(if (roundabout) 1 else 0)
        streets.add(street?.let(::streetId) ?: -1)
        via.forEach { this.via.add(it.toFloat()) }
        viaStarts.add(this.via.size)
    }

    fun addPlace(place: Place) {
        places += place
    }

    /** [ring] holds closed-polygon vertices as interleaved latitude, longitude. */
    fun addArea(ring: DoubleArray) {
        ring.forEach { areaPoints.add(it.toFloat()) }
        areaStarts.add(areaPoints.size)
    }

    private fun streetId(name: String) = streetIds.getOrPut(name.replace('\n', ' ')) {
        streetNames += name
        streetNames.lastIndex
    }

    fun build(): Graph {
        val kept = largestComponent()
        val remap = IntArray(nodeLatitudes.size) { -1 }
        kept.forEach {
            remap[from[it]] = 0
            remap[to[it]] = 0
        }
        val points = IntArray(remap.count { it == 0 })
        var count = 0
        for (node in remap.indices) {
            if (remap[node] < 0) continue
            points[count] = node
            remap[node] = count++
        }
        val grid = gridOf(kept, points)
        val edges = CompiledEdges(kept.size)
        kept.forEachIndexed { index, edge -> edges.add(index, rawPoints(edge), grid) }
        fun ints(value: (Int) -> Int) =
            IntBuffer.wrap(IntArray(kept.size) { value(kept[it]) })
        fun bytes(values: Ints) =
            ByteBuffer.wrap(ByteArray(kept.size) { values[kept[it]].toByte() })
        fun floats(values: Floats) =
            FloatBuffer.wrap(FloatArray(points.size) { values[points[it]] })
        return Graph(
            latitudes = floats(nodeLatitudes),
            longitudes = floats(nodeLongitudes),
            from = ints { remap[from[it]] },
            to = ints { remap[to[it]] },
            lengths = FloatBuffer.wrap(edges.lengths),
            roads = bytes(roads),
            surfaces = bytes(surfaces),
            scenery = ByteBuffer.wrap(edges.scenery),
            crowding = ByteBuffer.wrap(edges.crowding),
            oneway = bytes(oneway),
            roundabout = bytes(roundabout),
            streets = ints { streets[it] },
            rise = ShortBuffer.wrap(edges.rise),
            fall = ShortBuffer.wrap(edges.fall),
            shapeStarts = IntBuffer.wrap(edges.shapeStarts),
            shapeLatitudes = FloatBuffer.wrap(edges.shapeLatitudes.toArray()),
            shapeLongitudes = FloatBuffer.wrap(edges.shapeLongitudes.toArray()),
            streetNames = Texts.of(streetNames),
            places = compilePlaces(),
        )
    }

    private fun gridOf(kept: IntArray, points: IntArray): Grid {
        fun lowest(values: Floats) = points.minOf { values[it] }.toDouble()
        fun highest(values: Floats) = points.maxOf { values[it] }.toDouble()
        val grid = Grid(
            lowest(nodeLatitudes), highest(nodeLatitudes),
            lowest(nodeLongitudes), highest(nodeLongitudes),
        )
        points.forEach { grid.count(nodeLatitudes[it], nodeLongitudes[it]) }
        for (edge in kept) {
            for (i in viaStarts[edge] until viaStarts[edge + 1] step 2) {
                grid.count(via[i], via[i + 1])
            }
        }
        for (area in 0 until areaStarts.size - 1) {
            val start = areaStarts[area]
            val size = areaStarts[area + 1] - start
            grid.fill(DoubleArray(size) { areaPoints[start + it].toDouble() })
        }
        return grid
    }

    /** Edge columns derived from full geometry, filled in edge order. */
    private inner class CompiledEdges(size: Int) {
        val lengths = FloatArray(size)
        val scenery = ByteArray(size)
        val crowding = ByteArray(size)
        val rise = ShortArray(size)
        val fall = ShortArray(size)
        val shapeStarts = IntArray(size + 1)
        val shapeLatitudes = Floats()
        val shapeLongitudes = Floats()

        fun add(index: Int, raw: DoubleArray, grid: Grid) {
            val simplified = simplify(raw, ROAD_TOLERANCE_METERS)
            for (i in 2 until simplified.size - 2 step 2) {
                shapeLatitudes.add(simplified[i].toFloat())
                shapeLongitudes.add(simplified[i + 1].toFloat())
            }
            shapeStarts[index + 1] = shapeLatitudes.size
            lengths[index] = pathLength(simplified).toFloat()
            scenery[index] = mean(raw) { latitude, longitude ->
                grid.scenery(latitude, longitude)
            }
            crowding[index] = mean(raw) { latitude, longitude ->
                grid.crowding(latitude, longitude)
            }
            val (up, down) = climb(raw)
            rise[index] = decimeters(up)
            fall[index] = decimeters(down)
        }

        private inline fun mean(raw: DoubleArray, value: (Double, Double) -> Int): Byte {
            val vertices = raw.size / 2
            val sum = (0 until vertices).sumOf { value(raw[it * 2], raw[it * 2 + 1]) }
            return (sum.toDouble() / vertices).roundToInt().toByte()
        }
    }

    private fun rawPoints(edge: Int): DoubleArray {
        val start = viaStarts[edge]
        val last = viaStarts[edge + 1] - start + 2
        return DoubleArray(last + 2) {
            when (it) {
                0 -> nodeLatitudes[from[edge]]
                1 -> nodeLongitudes[from[edge]]
                last -> nodeLatitudes[to[edge]]
                last + 1 -> nodeLongitudes[to[edge]]
                else -> via[start + it - 2]
            }.toDouble()
        }
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

    /** Edges of the largest connected part, in their original order. */
    private fun largestComponent(): IntArray {
        val parent = IntArray(nodeLatitudes.size) { it }
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
        val edges = 0 until from.size
        edges.forEach { parent[find(from[it])] = find(to[it]) }
        val sizes = IntArray(parent.size)
        edges.forEach { sizes[find(from[it])]++ }
        val largest = sizes.indices.maxByOrNull { sizes[it] }
        return edges.filter { find(from[it]) == largest }.toIntArray()
    }

    private fun compilePlaces(): Places {
        fun floats(value: (Place) -> Double) =
            FloatBuffer.wrap(FloatArray(places.size) { value(places[it]).toFloat() })
        val names = places.map { it.name }
        return Places(
            Texts.of(names),
            Texts.of(names.map(::searchKey)),
            ByteBuffer.wrap(ByteArray(places.size) { places[it].rank.toByte() }),
            floats { it.location.latitude },
            floats { it.location.longitude },
        )
    }
}

/** Growable int array without boxing. */
internal class Ints {
    private var values = IntArray(16)
    var size = 0
        private set

    fun add(value: Int) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size++] = value
    }

    operator fun get(index: Int) = values[index]

    fun toArray() = values.copyOf(size)
}

/** Growable float array without boxing. */
internal class Floats {
    private var values = FloatArray(16)
    var size = 0
        private set

    fun add(value: Float) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size++] = value
    }

    operator fun get(index: Int) = values[index]

    fun toArray() = values.copyOf(size)
}

/** Growable long array without boxing. */
internal class Longs {
    private var values = LongArray(16)
    var size = 0
        private set

    fun add(value: Long) {
        if (size == values.size) values = values.copyOf(size + size / 2)
        values[size++] = value
    }

    fun toArray() = values.copyOf(size)
}

/** Node density and green/water coverage on a ~100 m cell grid over the data's extent. */
private class Grid(south: Double, north: Double, west: Double, east: Double) {
    private val minRow = cellIndex(south) - MARGIN_CELLS
    private val minColumn = cellIndex(west) - MARGIN_CELLS
    private val rows = cellIndex(north) - minRow + MARGIN_CELLS + 1
    private val columns = cellIndex(east) - minColumn + MARGIN_CELLS + 1
    private val green = BitSet(rows * columns)
    private val density = ShortArray(rows * columns)

    fun count(latitude: Float, longitude: Float) {
        val cell = slot(cellIndex(latitude.toDouble()), cellIndex(longitude.toDouble()))
        if (cell >= 0 && density[cell] < Short.MAX_VALUE) density[cell]++
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
/** Raised whenever the graph file layout changes. */
private const val FORMAT = 2
private const val CELLS_PER_DEGREE = 1000
private const val INDEX_CELLS_PER_DEGREE = 500
private const val BEARING_STEPS = 256
private const val ROAD_TOLERANCE_METERS = 6.0
private const val BEARING_METERS = 15.0
private const val DECIMETERS = 10.0
private const val SAMPLE_METERS = 30.0
private const val CLIMB_THRESHOLD_METERS = 5.0
private const val MAX_SEARCH_HITS = 3000
private const val MARGIN_CELLS = 2
private const val EDGE_BITS = 26
private const val EDGE_MASK = (1L shl EDGE_BITS) - 1
private val SNAP_RADII = listOf(1, 2, 4, 8, 16, 32, 64)

/** Node counts in a 3×3 cell neighborhood (≈ 300 m) that raise crowding 0→3. */
private val CROWDING_STEPS = listOf(40, 150, 400)

private fun cellIndex(degrees: Double) = floor(degrees * CELLS_PER_DEGREE).toInt()

/** Spatial index cells of about 220 m by 150 m: most edges fit in one. */
private fun indexCell(degrees: Double) = floor(degrees * INDEX_CELLS_PER_DEGREE).toInt()

/** A bearing in 256 steps per turn, one byte each. */
private fun bearingByte(degrees: Double) =
    (degrees / 360 * BEARING_STEPS).roundToInt().toByte()

private fun degrees(bearing: Byte) = (bearing.toInt() and 0xFF) * 360.0 / BEARING_STEPS

/** 18 bits of row and 19 bits of column leave [EDGE_BITS] for the edge. */
private fun cellId(row: Int, column: Int) =
    ((row + 90_000L) shl 19) or (column + 180_000L)
