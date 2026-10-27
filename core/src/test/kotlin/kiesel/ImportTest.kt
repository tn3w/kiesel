package kiesel

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class ImportTest {
    @Test
    fun `classifies highways`() {
        assertEquals(Road.Steps, roadOf(mapOf("highway" to "steps")))
        assertEquals(Road.Main, roadOf(mapOf("highway" to "primary")))
        assertNull(roadOf(mapOf("highway" to "motorway")))
        assertNull(roadOf(mapOf("highway" to "service", "access" to "private")))
        assertEquals(
            Road.Calm,
            roadOf(mapOf("highway" to "service", "access" to "private", "foot" to "yes")),
        )
    }

    @Test
    fun `classifies surface, direction and scenic areas`() {
        assertEquals(0, surfaceOf(mapOf("surface" to "asphalt")))
        assertEquals(ROUGH, surfaceOf(mapOf("surface" to "gravel")))
        assertEquals(ROUGH, surfaceOf(mapOf("tracktype" to "grade4")))
        assertEquals(-1, onewayOf(mapOf("oneway" to "-1")))
        assertEquals(1, onewayOf(mapOf("junction" to "roundabout")))
        assertEquals(0, onewayOf(mapOf("oneway" to "yes", "oneway:bicycle" to "no")))
        assertEquals(true, waterOf(mapOf("natural" to "water")))
        assertEquals(false, waterOf(mapOf("leisure" to "park")))
        assertNull(waterOf(mapOf("building" to "yes")))
    }

    @Test
    fun `searches places by name, nearest and best match first`() {
        val builder = GraphBuilder()
        builder.addEdge(builder.addNode(0.0, 0.0), builder.addNode(0.0, 0.001), Road.Calm)
        builder.addPlace(Place("Hauptstrasse", LatLon(10.0, 10.0)))
        builder.addPlace(Place("Hauptstrasse", LatLon(0.0, 0.1)))
        builder.addPlace(Place("Bahnhof Hauptbahnhof", LatLon(0.0, 0.0)))
        val places = builder.build().places

        val found = places.search("haupt", LatLon(0.0, 0.0), 5)
        val names = found.map { it.name }
        val expected = listOf("Hauptstrasse", "Hauptstrasse", "Bahnhof Hauptbahnhof")
        assertEquals(expected, names)
        assertEquals(0.1, found.first().location.longitude, 1e-6)
        assertTrue(places.search("zzz", LatLon(0.0, 0.0), 5).isEmpty())
    }

    @Test
    fun `search ignores accents and prefers bigger places`() {
        val builder = GraphBuilder()
        builder.addEdge(builder.addNode(0.0, 0.0), builder.addNode(0.0, 0.001), Road.Calm)
        builder.addPlace(Place("Zürichstrasse", LatLon(0.0, 0.0)))
        builder.addPlace(Place("Zürich", LatLon(1.0, 1.0), rank = 3))
        val places = builder.build().places

        val names = places.search("zurich", LatLon(0.0, 0.0), 5).map { it.name }
        assertEquals(listOf("Zürich", "Zürichstrasse"), names)
    }

    /** Set KIESEL_EXTRACT=/path/region.osm.pbf to run on real data. */
    @Test
    fun `routes on a real extract`() {
        val path = System.getenv("KIESEL_EXTRACT").orEmpty()
        assumeTrue(path.isNotEmpty())
        val started = System.currentTimeMillis()
        val graph = importExtract(open = { File(path).inputStream() })
        println("import ${System.currentTimeMillis() - started} ms: " +
            "${graph.latitudes.size} nodes, ${graph.from.size} edges, " +
            "${graph.places.latitudes.size} places")
        println("scenery ${graph.scenery.groupBy { it }.mapValues { it.value.size }}")
        println("crowding ${graph.crowding.groupBy { it }.mapValues { it.value.size }}")
        System.getenv("KIESEL_OUTPUT")?.takeIf { it.isNotEmpty() }
            ?.let { File(it).outputStream().use(graph::write) }

        val random = Random(1)
        val totals = Profile.entries.associateWith { DoubleArray(2) }
        var pairs = 0
        repeat(40) {
            val start = random.nextInt(graph.latitudes.size)
            val goal = random.nextInt(graph.latitudes.size)
            val routes = Profile.entries.associateWith {
                graph.route(start, goal, Mode.Walk, it)
            }
            if (routes.values.any { it == null }) return@repeat
            pairs++
            routes.forEach { (profile, route) ->
                totals[profile]!![0] += route!!.meters
                totals[profile]!![1] += route.maneuvers.size.toDouble()
            }
        }
        totals.forEach { (profile, sums) ->
            println("$profile: ${(sums[0] / pairs).toInt()} m, ${sums[1] / pairs} turns")
        }
        assertTrue(pairs > 0)
    }
}
