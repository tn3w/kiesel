package kiesel

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val STEP = 0.001

private fun gridGraph(size: Int, random: Random? = null): GraphBuilder {
    val builder = GraphBuilder()
    val nodes = (0 until size * size).map {
        builder.addNode(it / size * STEP, it % size * STEP)
    }
    val roads = Road.entries
    for (row in 0 until size) {
        for (column in 0 until size) {
            val node = nodes[row * size + column]
            listOf(row to column + 1, row + 1 to column)
                .filter { (r, c) -> r < size && c < size }
                .forEach { (r, c) ->
                    builder.addEdge(
                        node,
                        nodes[r * size + c],
                        random?.let { roads[it.nextInt(roads.size)] } ?: Road.Calm,
                        random?.nextInt(3) ?: 1,
                        random?.nextInt(4) == 0,
                    )
                }
        }
    }
    return builder
}

private fun Graph.referenceCost(
    start: Int,
    goal: Int,
    mode: Mode,
    profile: Profile,
): Double? {
    val cost = DoubleArray(arcCount) { Double.POSITIVE_INFINITY }
    forEachOutgoing(start) { cost[it] = arcCost(it, mode, profile) }
    var changed = true
    while (changed) {
        changed = false
        for (arc in 0 until arcCount) {
            if (cost[arc].isInfinite()) continue
            forEachOutgoing(arcEnd(arc)) { next ->
                val candidate = cost[arc] + arcCost(next, mode, profile) +
                    turnCost(arc, next, profile)
                if (next != (arc xor 1) && candidate < cost[next] - 1e-9) {
                    cost[next] = candidate
                    changed = true
                }
            }
        }
    }
    return (0 until arcCount).filter { arcEnd(it) == goal }.minOfOrNull { cost[it] }
        ?.takeIf { it.isFinite() }
}

class RouterTest {
    @Test
    fun `A star matches exhaustive search for every mode and profile`() {
        val random = Random(7)
        repeat(4) {
            val graph = gridGraph(6, random).build()
            repeat(10) {
                val start = random.nextInt(graph.nodeCount)
                val goal = random.nextInt(graph.nodeCount)
                for (mode in Mode.entries) for (profile in Profile.entries) {
                    val expected = graph.referenceCost(start, goal, mode, profile)
                    val actual = graph.route(start, goal, mode, profile)?.cost
                    if (start == goal) continue
                    assertEquals(expected == null, actual == null, "$mode $profile")
                    if (expected != null) assertEquals(expected, actual!!, 1e-6)
                }
            }
        }
    }

    @Test
    fun `profiles pick their preferred path`() {
        for (profile in listOf(Profile.Scenic, Profile.Quiet, Profile.Easiest)) {
            val graph = diamond()
            when (profile) {
                Profile.Scenic -> graph.scenery.put(2, 2).put(3, 2)
                Profile.Quiet -> graph.crowding.put(0, 3).put(1, 3)
                else -> graph.surfaces.put(0, ROUGH.toByte()).put(1, ROUGH.toByte())
            }
            val fastest = diamond().route(0, 1, Mode.Walk, Profile.Fastest)!!
            val preferred = graph.route(0, 1, Mode.Walk, profile)!!
            assertNear(DIRECT, fastest.points[1])
            assertNear(DETOUR, preferred.points[1])
        }
    }

    @Test
    fun `easiest avoids stairs while fastest takes them`() {
        val builder = GraphBuilder()
        val start = builder.addNode(0.0, 0.0)
        val goal = builder.addNode(0.0, STEP)
        val stairs = builder.addNode(0.0001, STEP / 2)
        val street = builder.addNode(0.002, STEP / 2)
        builder.addEdge(start, stairs, Road.Steps)
        builder.addEdge(stairs, goal, Road.Steps)
        builder.addEdge(start, street, Road.Calm)
        builder.addEdge(street, goal, Road.Calm)
        val graph = builder.build()

        assertNear(graph.point(stairs), firstStop(graph, start, goal, Profile.Fastest))
        assertNear(graph.point(street), firstStop(graph, start, goal, Profile.Easiest))
        val bike = firstStop(graph, start, goal, Profile.Fastest, Mode.Bike)
        assertNear(graph.point(street), bike)
    }

    @Test
    fun `fewer turns win ties`() {
        val graph = gridGraph(5).build()
        val route = graph.route(6, 18, Mode.Walk, Profile.Easiest)!!
        assertEquals(2, route.maneuvers.size)
    }

    @Test
    fun `bikes respect oneway and walkers ignore it`() {
        val builder = GraphBuilder()
        val west = builder.addNode(0.0, 0.0)
        val east = builder.addNode(0.0, STEP)
        builder.addEdge(west, east, Road.Calm, oneway = true)
        val graph = builder.build()

        assertNotNull(graph.route(west, east, Mode.Bike, Profile.Fastest))
        assertNull(graph.route(east, west, Mode.Bike, Profile.Fastest))
        assertNotNull(graph.route(east, west, Mode.Walk, Profile.Fastest))
    }

    @Test
    fun `disconnected fragments are dropped and snapping stays routable`() {
        val builder = gridGraph(3)
        val island = builder.addNode(1.0, 1.0)
        builder.addEdge(island, builder.addNode(1.0, 1.001), Road.Footway)
        val graph = builder.build()

        assertEquals(9, graph.nodeCount)
        assertEquals(0, graph.snap(LatLon(-0.0001, -0.0001))!!.edge)
    }

    @Test
    fun `graph survives serialization`() {
        val graph = gridGraph(4, Random(3)).build()
        val file = File.createTempFile("graph", null).apply { deleteOnExit() }
        file.outputStream().use(graph::write)
        val loaded = Graph.read(file)

        assertEquals(
            graph.route(0, 15, Mode.Walk, Profile.Scenic)?.cost,
            loaded.route(0, 15, Mode.Walk, Profile.Scenic)?.cost,
        )
        val origin = LatLon(0.0, 0.0)
        assertEquals(graph.snap(origin)!!.edge, loaded.snap(origin)!!.edge)
    }

    @Test
    fun `routes start and end inside edges`() {
        val builder = GraphBuilder()
        val west = builder.addNode(0.0, 0.0)
        val east = builder.addNode(0.0, 4 * STEP)
        builder.addEdge(west, east, Road.Calm)
        val graph = builder.build()

        val near = LatLon(0.0, STEP)
        val far = LatLon(0.0, 3 * STEP)
        val route = graph.route(near, far, Mode.Walk, Profile.Fastest)!!
        assertEquals(2 * STEP * 111_195, route.meters, 1.0)
        assertEquals(LatLon(0.0, STEP).longitude, route.points.first().longitude, 1e-9)
        assertEquals(LatLon(0.0, 3 * STEP).longitude, route.points.last().longitude, 1e-9)

        val back = graph.route(far, near, Mode.Walk, Profile.Fastest)!!
        assertEquals(route.meters, back.meters, 1.0)
    }

    @Test
    fun `curved edges keep their bends and drop collinear points`() {
        val builder = GraphBuilder()
        val start = builder.addNode(0.0, 0.0)
        val end = builder.addNode(0.0, 4 * STEP)
        val straight = DoubleArray(6) { if (it % 2 == 0) 0.0 else (it / 2 + 1) * STEP }
        builder.addEdge(start, end, Road.Calm, via = straight)
        assertEquals(0, builder.build().shapeLatitudes.limit())

        val bent = GraphBuilder()
        val a = bent.addNode(0.0, 0.0)
        val b = bent.addNode(0.0, 2 * STEP)
        bent.addEdge(a, b, Road.Calm, via = doubleArrayOf(STEP, STEP))
        val graph = bent.build()
        assertEquals(1, graph.shapeLatitudes.limit())
        assertTrue(graph.lengths[0] > 2 * STEP * 111_195 * 1.2)
    }

    @Test
    fun `climbing costs time and descending does not`() {
        val builder = GraphBuilder { latitude, _ -> latitude * 20_000 }
        val low = builder.addNode(0.0, 0.0)
        val high = builder.addNode(STEP, 0.0)
        builder.addEdge(low, high, Road.Calm)
        val graph = builder.build()

        val uphill = graph.route(low, high, Mode.Walk, Profile.Fastest)!!
        val downhill = graph.route(high, low, Mode.Walk, Profile.Fastest)!!
        assertEquals(20.0, uphill.rise, 0.5)
        assertEquals(0.0, downhill.rise, 0.5)
        val extra = uphill.seconds - downhill.seconds
        assertEquals(20.0 * Mode.Walk.climbSeconds, extra, 1.0)
    }

    @Test
    fun `easiest takes the flat detour over a steep shortcut`() {
        val builder = GraphBuilder { latitude, longitude ->
            val onHill = latitude > 0.00005 && longitude in 0.0008..0.0012
            if (onHill) 8.0 else 0.0
        }
        val start = builder.addNode(0.0, 0.0)
        val goal = builder.addNode(0.0, 2 * STEP)
        val hill = builder.addNode(0.0001, STEP)
        val flat = builder.addNode(-0.0010, STEP)
        listOf(hill, flat).forEach {
            builder.addEdge(start, it, Road.Calm)
            builder.addEdge(it, goal, Road.Calm)
        }
        val graph = builder.build()

        assertNear(graph.point(hill), firstStop(graph, start, goal, Profile.Fastest))
        assertNear(graph.point(flat), firstStop(graph, start, goal, Profile.Easiest))
    }

    private fun firstStop(
        graph: Graph,
        start: Int,
        goal: Int,
        profile: Profile,
        mode: Mode = Mode.Walk,
    ) = graph.route(start, goal, mode, profile)!!.points[1]

    private fun diamond(): Graph {
        val builder = GraphBuilder()
        val start = builder.addNode(0.0, 0.0)
        val goal = builder.addNode(0.0, 2 * STEP)
        val direct = builder.addNode(0.0001, STEP)
        val detour = builder.addNode(-0.0004, STEP)
        builder.addEdge(start, direct, Road.Calm)
        builder.addEdge(direct, goal, Road.Calm)
        builder.addEdge(start, detour, Road.Calm)
        builder.addEdge(detour, goal, Road.Calm)
        return builder.build()
    }

    private companion object {
        val DIRECT = LatLon(0.0001, STEP)
        val DETOUR = LatLon(-0.0004, STEP)
    }
}
