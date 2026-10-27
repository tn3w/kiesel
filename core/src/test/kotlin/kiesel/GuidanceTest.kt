package kiesel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuidanceTest {
    private val graph = GraphBuilder().run {
        val start = addNode(0.0, 0.0)
        val corner = addNode(0.0, 0.001)
        val end = addNode(0.001, 0.001)
        val spur = addNode(-0.001, 0.001)
        addEdge(start, corner, Road.Calm, street = "Elm Street")
        addEdge(corner, end, Road.Calm, street = "Oak Lane")
        addEdge(corner, spur, Road.Calm, street = "Oak Lane")
        build()
    }
    private val route = graph.route(0, 2, Mode.Walk, Profile.Fastest)!!

    @Test
    fun `turn at junction then arrival`() {
        assertEquals(listOf(Turn.Left, Turn.Arrive), route.maneuvers.map { it.turn })
        assertEquals("Oak Lane", route.maneuvers.first().street)
    }

    @Test
    fun `previews at start, prepares once, then says now`() {
        val navigator = Navigator(route, Mode.Walk)
        val preview = navigator.update(LatLon(0.0, 0.0)).announcement!!
        assertEquals(Turn.Left, preview.maneuver.turn)
        assertEquals(111.0, preview.meters!!, 1.0)

        val prepare = navigator.update(LatLon(0.0, 0.0005)).announcement!!
        assertEquals(55.0, prepare.meters!!, 1.0)
        assertNull(navigator.update(LatLon(0.0, 0.00052)).announcement)

        val now = navigator.update(LatLon(0.0, 0.00095)).announcement!!
        assertNull(now.meters)
        assertNull(now.then)
    }

    @Test
    fun `arrives at the destination`() {
        val navigator = Navigator(route, Mode.Walk)
        val arrived = navigator.update(LatLon(0.001, 0.001))
        assertTrue(arrived.arrived)
        assertEquals(Turn.Arrive, arrived.announcement!!.maneuver.turn)
        assertTrue(arrived.remainingMeters < 1)
    }

    @Test
    fun `leaving the route needs two stray fixes`() {
        val navigator = Navigator(route, Mode.Walk)
        assertFalse(navigator.update(LatLon(0.002, 0.0)).offRoute)
        assertTrue(navigator.update(LatLon(0.002, 0.0)).offRoute)
        val back = navigator.update(LatLon(0.00001, 0.0003))
        assertFalse(back.offRoute)
        assertEquals(0.0, back.position.latitude, 1e-9)
    }

    @Test
    fun `counts roundabout exits`() {
        val graph = GraphBuilder().run {
            val north = addNode(0.0003, 0.0)
            val east = addNode(0.0, 0.0003)
            val south = addNode(-0.0003, 0.0)
            val west = addNode(0.0, -0.0003)
            listOf(west, south, east, north, west).zipWithNext { from, to ->
                addEdge(from, to, Road.Street, oneway = true, roundabout = true)
            }
            addEdge(addNode(0.0, -0.002), west, Road.Street)
            addEdge(south, addNode(-0.002, 0.0), Road.Street)
            addEdge(east, addNode(0.0, 0.002), Road.Street, street = "Exit Road")
            addEdge(north, addNode(0.002, 0.0), Road.Street)
            build()
        }
        val route = graph.route(
            LatLon(0.0, -0.002), LatLon(0.0, 0.002), Mode.Bike, Profile.Fastest,
        )!!
        val roundabout = route.maneuvers.first()
        assertEquals(Turn.Roundabout, roundabout.turn)
        assertEquals(2, roundabout.exit)
        assertEquals("Exit Road", roundabout.street)
        assertEquals(2, route.maneuvers.size)
    }

    @Test
    fun `keeps left at a fork and stays silent along a bend`() {
        val fork = GraphBuilder().run {
            val start = addNode(0.0, 0.0)
            val split = addNode(0.0, 0.001)
            addEdge(start, split, Road.Calm)
            addEdge(split, addNode(0.0002, 0.002), Road.Calm)
            addEdge(split, addNode(-0.0002, 0.002), Road.Calm)
            build()
        }
        val goal = LatLon(0.0002, 0.002)
        val left = fork.route(LatLon(0.0, 0.0), goal, Mode.Walk, Profile.Fastest)
        assertEquals(Turn.KeepLeft, assertNotNull(left).maneuvers.first().turn)

        val bend = GraphBuilder().run {
            val start = addNode(0.0, 0.0)
            val knee = addNode(0.0, 0.001)
            addEdge(start, knee, Road.Calm)
            addEdge(knee, addNode(0.001, 0.002), Road.Calm)
            addEdge(knee, addNode(-0.001, 0.001), Road.Footway)
            build()
        }
        val along =
            bend.route(LatLon(0.0, 0.0), LatLon(0.001, 0.002), Mode.Walk, Profile.Fastest)
        assertEquals(listOf(Turn.Arrive), assertNotNull(along).maneuvers.map { it.turn })
    }
}
