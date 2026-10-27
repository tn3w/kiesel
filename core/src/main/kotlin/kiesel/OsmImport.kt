package kiesel

import com.wolt.osm.parallelpbf.ParallelBinaryParser
import java.io.InputStream

private class RawWay(
    val nodeIds: LongArray,
    val road: Road?,
    val surface: Int,
    val oneway: Boolean,
    val roundabout: Boolean,
    val name: String?,
    val water: Boolean?,
)

/** Builds a [Graph] from an OSM PBF extract; [open] is called once per pass. */
fun importExtract(
    open: () -> InputStream,
    heights: (Double, Double) -> Double = { _, _ -> 0.0 },
): Graph = Importer(open, heights).run()

private class Importer(
    private val open: () -> InputStream,
    heights: (Double, Double) -> Double,
) {
    private val builder = GraphBuilder(heights)
    private val ways = readWays(open)
    private val ids = uniqueSorted(ways)
    private val latitudes = DoubleArray(ids.size) { Double.NaN }
    private val longitudes = DoubleArray(ids.size) { Double.NaN }
    private val uses = IntArray(ids.size)
    private val nodeOf = IntArray(ids.size) { -1 }
    private val namedStreets = HashSet<String>()

    fun run(): Graph {
        readNodes()
        ways.filter { it.road != null }.forEach { way ->
            val positions = positionsOf(way)
            uses[positions.first()] += 2
            uses[positions.last()] += 2
            positions.forEach { uses[it]++ }
        }
        ways.forEach { way ->
            val positions = positionsOf(way)
            if (way.road != null) addStreet(way, positions)
            if (way.water != null) addArea(way.water, positions)
        }
        return builder.build()
    }

    private fun positionsOf(way: RawWay) =
        IntArray(way.nodeIds.size) { ids.binarySearch(way.nodeIds[it]) }

    private fun isKnown(position: Int) = !latitudes[position].isNaN()

    private fun point(position: Int) = LatLon(latitudes[position], longitudes[position])

    private fun readNodes() = parse(open) {
        onNode { node ->
            val position = ids.binarySearch(node.id)
            if (position >= 0) {
                latitudes[position] = node.lat
                longitudes[position] = node.lon
            }
            val name = node.tags["name"]
            if (name != null && node.tags.keys.any { it in PLACE_KEYS }) {
                val location = LatLon(node.lat, node.lon)
                builder.addPlace(Place(name, location, rankOf(node.tags)))
            }
        }
    }

    private fun addStreet(way: RawWay, positions: IntArray) {
        var start = 0
        while (start < positions.size) {
            if (latitudes[positions[start]].isNaN()) {
                start++
                continue
            }
            var end = start
            while (end + 1 < positions.size && isKnown(positions[end + 1])) end++
            addRun(way, positions.copyOfRange(start, end + 1))
            start = end + 1
        }
    }

    private fun addRun(way: RawWay, run: IntArray) {
        addName(way, run[run.size / 2])
        var first = 0
        for (i in 1..run.lastIndex) {
            if (i != run.lastIndex && uses[run[i]] < 2) continue
            addEdge(way, run, first, i)
            first = i
        }
    }

    private fun addEdge(way: RawWay, run: IntArray, first: Int, last: Int) {
        val via = DoubleArray((last - first - 1) * 2) {
            val position = run[first + 1 + it / 2]
            if (it % 2 == 0) latitudes[position] else longitudes[position]
        }
        val from = node(run[first])
        val to = node(run[last])
        if (from == to && via.isEmpty()) return
        builder.addEdge(
            from, to, way.road!!, way.surface, way.oneway, way.name, via, way.roundabout,
        )
    }

    private fun node(position: Int) = nodeOf[position].takeIf { it >= 0 }
        ?: builder.addNode(latitudes[position], longitudes[position])
            .also { nodeOf[position] = it }

    private fun addName(way: RawWay, position: Int) {
        val name = way.name ?: return
        val district = "${(latitudes[position] * 100).toInt()}," +
            "${(longitudes[position] * 100).toInt()}"
        if (namedStreets.add("$name@$district")) {
            builder.addPlace(Place(name, point(position)))
        }
    }

    private fun addArea(water: Boolean, positions: IntArray) {
        if (positions.any { latitudes[it].isNaN() }) return
        val ring = DoubleArray(positions.size * 2) {
            val position = positions[it / 2]
            if (it % 2 == 0) latitudes[position] else longitudes[position]
        }
        builder.addArea(water, ring)
    }
}

private fun uniqueSorted(ways: List<RawWay>): LongArray {
    val all = LongArray(ways.sumOf { it.nodeIds.size })
    var cursor = 0
    ways.forEach {
        it.nodeIds.copyInto(all, cursor)
        cursor += it.nodeIds.size
    }
    all.sort()
    var count = 0
    for (i in all.indices) if (i == 0 || all[i] != all[i - 1]) all[count++] = all[i]
    return all.copyOf(count)
}

private fun readWays(open: () -> InputStream): List<RawWay> {
    val ways = ArrayList<RawWay>()
    parse(open) {
        onWay { way ->
            val ids = way.nodes.toLongArray()
            val tags = way.tags
            val road = roadOf(tags)
            val closed = ids.size > 3 && ids.first() == ids.last()
            val water = if (closed) waterOf(tags) else null
            if (road == null && water == null) return@onWay
            val direction = onewayOf(tags)
            ways += RawWay(
                nodeIds = if (direction < 0) ids.reversedArray() else ids,
                road = road,
                surface = surfaceOf(tags),
                oneway = direction != 0,
                roundabout = tags["junction"] in ROUNDABOUTS,
                name = tags["name"],
                water = water,
            )
        }
    }
    return ways
}

private fun parse(open: () -> InputStream, configure: ParallelBinaryParser.() -> Unit) =
    open().use { ParallelBinaryParser(it, 1).apply(configure).parse() }

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

private val ROUNDABOUTS = setOf("roundabout", "circular")

private val GREEN_LANDUSE =
    setOf("forest", "meadow", "grass", "orchard", "vineyard", "village_green")

private val PLACE_KEYS =
    setOf("amenity", "shop", "tourism", "historic", "leisure", "place", "railway")
