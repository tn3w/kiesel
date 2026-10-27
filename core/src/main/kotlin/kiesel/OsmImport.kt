package kiesel

import com.wolt.osm.parallelpbf.ParallelBinaryParser
import java.io.InputStream
import java.util.Arrays
import kotlin.math.roundToInt

/** A way's node references are [first] until [end] in the importer's reference table. */
private class RawWay(
    val first: Int,
    val end: Int,
    val road: Road?,
    val surface: Int,
    val oneway: Boolean,
    val roundabout: Boolean,
    val name: String?,
)

/**
 * Builds a [Graph] from an OSM PBF extract; [open] is called once per pass.
 * Node tables are primitive arrays, freed before the graph is compiled.
 */
fun importExtract(
    open: () -> InputStream,
    heights: (Double, Double) -> Double = { _, _ -> 0.0 },
): Graph = GraphBuilder(heights).also { Importer(open, it).run() }.build()

private class Importer(
    private val open: () -> InputStream,
    private val builder: GraphBuilder,
) {
    private val ways = ArrayList<RawWay>()
    private val positions: IntArray
    private val latitudes: IntArray
    private val longitudes: IntArray

    init {
        val references = readWays()
        val ids = uniqueSorted(references)
        positions = IntArray(references.size)
        Arrays.parallelSetAll(positions) { ids.binarySearch(references[it]) }
        latitudes = IntArray(ids.size) { UNKNOWN }
        longitudes = IntArray(ids.size)
        readNodes(ids)
    }

    private val uses = ByteArray(latitudes.size)
    private val nodeOf = IntArray(latitudes.size) { -1 }
    private val namedStreets = HashSet<String>()

    fun run() {
        val (roads, areas) = ways.partition { it.road != null }
        roads.forEach(::countUses)
        spatialOrder(roads).forEach(::addStreet)
        areas.forEach(::addArea)
    }

    /** All node references of kept ways, in way order. */
    private fun readWays(): LongArray {
        val references = Longs()
        val names = HashMap<String, String>()
        parse {
            onWay { way ->
                val tags = way.tags
                val road = roadOf(tags)
                val closed = way.nodes.size > 3 && way.nodes.first() == way.nodes.last()
                val area = closed && waterOf(tags) != null
                if (road == null && !area) return@onWay
                val direction = onewayOf(tags)
                val nodes = if (direction < 0) way.nodes.reversed() else way.nodes
                synchronized(ways) {
                    val first = references.size
                    nodes.forEach(references::add)
                    ways += RawWay(
                        first = first,
                        end = references.size,
                        road = road,
                        surface = surfaceOf(tags),
                        oneway = direction != 0,
                        roundabout = tags["junction"] in ROUNDABOUTS,
                        name = tags["name"]?.let { names.getOrPut(it) { it } },
                    )
                }
            }
        }
        return references.toArray()
    }

    private fun readNodes(ids: LongArray) = parse {
        onNode { node ->
            val position = ids.binarySearch(node.id)
            if (position >= 0) {
                latitudes[position] = (node.lat * FIXED_POINT).roundToInt()
                longitudes[position] = (node.lon * FIXED_POINT).roundToInt()
            }
            val name = node.tags["name"]
            if (name != null && node.tags.keys.any { it in PLACE_KEYS }) {
                val place = Place(name, LatLon(node.lat, node.lon), rankOf(node.tags))
                synchronized(builder) { builder.addPlace(place) }
            }
        }
    }

    private fun parse(configure: ParallelBinaryParser.() -> Unit) = open().use {
        val threads = Runtime.getRuntime().availableProcessors()
        ParallelBinaryParser(it, threads).apply(configure).parse()
    }

    /** Ends and shared nodes count 2 or more: the graph has a junction there. */
    private fun countUses(way: RawWay) {
        for (i in way.first until way.end) {
            if (uses[positions[i]] < 2) uses[positions[i]]++
        }
        uses[positions[way.first]] = 2
        uses[positions[way.end - 1]] = 2
    }

    /** Ways near each other on the map get nearby node and edge numbers. */
    private fun spatialOrder(roads: List<RawWay>): List<RawWay> {
        val keys = LongArray(roads.size) {
            (zOrder(positions[roads[it].first]) shl 32) or it.toLong()
        }
        keys.sort()
        return keys.map { roads[(it and INDEX_MASK).toInt()] }
    }

    /** Interleaved row and column bits on a 65536² grid over the globe. */
    private fun zOrder(position: Int): Long {
        val row = ((latitude(position) + 90) / 180 * GRID_MAX).toLong()
        val column = ((longitude(position) + 180) / 360 * GRID_MAX).toLong()
        var key = 0L
        for (bit in 0 until 16) {
            key = key or ((row shr bit and 1) shl (2 * bit + 1))
            key = key or ((column shr bit and 1) shl (2 * bit))
        }
        return key
    }

    private fun isKnown(position: Int) = latitudes[position] != UNKNOWN

    private fun latitude(position: Int) = latitudes[position] / FIXED_POINT

    private fun longitude(position: Int) = longitudes[position] / FIXED_POINT

    private fun point(position: Int) = LatLon(latitude(position), longitude(position))

    /** Interleaved latitude, longitude of references [from] until [end]. */
    private fun coordinates(from: Int, end: Int) = DoubleArray((end - from) * 2) {
        val position = positions[from + it / 2]
        if (it % 2 == 0) latitude(position) else longitude(position)
    }

    private fun addStreet(way: RawWay) {
        var start = way.first
        while (start < way.end) {
            if (!isKnown(positions[start])) {
                start++
                continue
            }
            var last = start
            while (last + 1 < way.end && isKnown(positions[last + 1])) last++
            addRun(way, start, last)
            start = last + 1
        }
    }

    private fun addRun(way: RawWay, first: Int, last: Int) {
        addName(way, positions[(first + last + 1) / 2])
        var start = first
        for (i in first + 1..last) {
            if (i != last && uses[positions[i]] < 2) continue
            addEdge(way, start, i)
            start = i
        }
    }

    private fun addEdge(way: RawWay, first: Int, last: Int) {
        val via = coordinates(first + 1, last)
        val from = node(positions[first])
        val to = node(positions[last])
        if (from == to && via.isEmpty()) return
        builder.addEdge(
            from, to, way.road!!, way.surface, way.oneway, way.name, via, way.roundabout,
        )
    }

    private fun node(position: Int) = nodeOf[position].takeIf { it >= 0 }
        ?: builder.addNode(latitude(position), longitude(position))
            .also { nodeOf[position] = it }

    private fun addName(way: RawWay, position: Int) {
        val name = way.name ?: return
        val district = "${(latitude(position) * 100).toInt()}," +
            "${(longitude(position) * 100).toInt()}"
        if (namedStreets.add("$name@$district")) {
            builder.addPlace(Place(name, point(position)))
        }
    }

    private fun addArea(way: RawWay) {
        if (!(way.first until way.end).all { isKnown(positions[it]) }) return
        builder.addArea(coordinates(way.first, way.end))
    }
}

private fun uniqueSorted(references: LongArray): LongArray {
    val all = references.copyOf()
    Arrays.parallelSort(all)
    var count = 0
    for (i in all.indices) if (i == 0 || all[i] != all[i - 1]) all[count++] = all[i]
    return all.copyOf(count)
}

internal fun roadOf(tags: Map<String, String>): Road? {
    val closed = tags["access"] in setOf("no", "private")
    if (closed && tags["foot"] != "yes" && tags["bicycle"] != "yes") return null
    return when (tags["highway"]) {
        "steps" -> Road.Steps
        "footway", "pedestrian", "corridor" -> Road.Footway
        "path", "bridleway" -> Road.Path
        "track" -> Road.Track
        "cycleway" -> Road.Cycleway
        "living_street", "residential", "road" -> Road.Calm
        "service" -> if (tags["service"] == "parking_aisle") null else Road.Calm
        "unclassified", "tertiary", "tertiary_link" -> Road.Street
        "secondary", "secondary_link", "primary", "primary_link" -> Road.Main
        else -> null
    }
}

internal fun surfaceOf(tags: Map<String, String>): Int {
    val roughTrack = tags["tracktype"] in setOf("grade3", "grade4", "grade5")
    return when (tags["surface"]) {
        "asphalt", "concrete", "paved", "paving_stones", "concrete:plates" -> 0
        "gravel", "dirt", "ground", "earth", "sand", "mud", "grass", "unpaved",
        "pebblestone", "cobblestone", "sett", "rock" -> ROUGH
        null -> if (roughTrack) ROUGH else 1
        else -> 1
    }
}

/** 1 = along the way, -1 = against it, 0 = both ways. */
internal fun onewayOf(tags: Map<String, String>): Int {
    val bikeBothWays = tags["oneway:bicycle"] == "no" ||
        tags["cycleway"]?.startsWith("opposite") == true
    return when {
        bikeBothWays -> 0
        tags["oneway"] == "-1" -> -1
        tags["oneway"] in setOf("yes", "1", "true") -> 1
        tags["junction"] in ROUNDABOUTS -> 1
        else -> 0
    }
}

internal fun rankOf(tags: Map<String, String>) = when (tags["place"]) {
    "city" -> 3
    "town" -> 2
    "village", "suburb" -> 1
    else -> 0
}

/** true = water, false = green, null = not a scenic area. */
internal fun waterOf(tags: Map<String, String>): Boolean? = when {
    tags["natural"] == "water" || tags["waterway"] == "riverbank" -> true
    tags["landuse"] in setOf("basin", "reservoir") -> true
    tags["natural"] in setOf("wood", "scrub", "wetland", "grassland", "heath") -> false
    tags["landuse"] in GREEN_LANDUSE -> false
    tags["leisure"] in setOf("park", "garden", "nature_reserve") -> false
    else -> null
}

/** OSM stores coordinates as 1e-7 degree integers. */
private const val FIXED_POINT = 1e7
private const val UNKNOWN = Int.MIN_VALUE
private const val GRID_MAX = 0xFFFF
private const val INDEX_MASK = 0xFFFFFFFFL

private val ROUNDABOUTS = setOf("roundabout", "circular")

private val GREEN_LANDUSE =
    setOf("forest", "meadow", "grass", "orchard", "vineyard", "village_green")

private val PLACE_KEYS =
    setOf("amenity", "shop", "tourism", "historic", "leisure", "place", "railway")
