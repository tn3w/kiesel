package kiesel

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

enum class Mode(val maxSpeed: Double, val climbSeconds: Double) {
    Walk(1.4, 6.0),
    Bike(5.0, 10.0);

    /** Meters per second; 0 = impassable. */
    fun speed(road: Road, surface: Int) = when {
        road == Road.Steps -> if (this == Walk) 0.7 else 0.0
        this == Walk -> if (surface == ROUGH) maxSpeed * 0.85 else maxSpeed
        road == Road.Footway -> Walk.maxSpeed
        surface == ROUGH -> 3.0
        else -> maxSpeed
    }
}

/** Penalties are always ≥ 1, so straight-line time stays an admissible estimate. */
enum class Profile(val turnSeconds: Double) {
    Fastest(2.0),
    Easiest(45.0),
    Scenic(5.0),
    Quiet(5.0);

    fun penalty(graph: Graph, edge: Int, climb: Double): Double {
        val traffic = graph.road(edge).traffic
        return when (this) {
            Fastest -> 1.0
            Easiest -> 1.0 + 0.4 * traffic + roughness(graph, edge) +
                stairs(graph, edge) +
                6.0 * climb / maxOf(graph.length(edge), 1.0)
            Scenic -> 1.0 + 0.8 * (2 - graph.scenery(edge)) + 0.3 * traffic
            Quiet -> 1.0 + 0.7 * graph.crowding(edge) + 0.5 * traffic
        }
    }

    private fun roughness(graph: Graph, edge: Int) =
        if (graph.surface(edge) == ROUGH) 1.0 else 0.0

    private fun stairs(graph: Graph, edge: Int) =
        if (graph.road(edge) == Road.Steps) 4.0 else 0.0
}

class Route(
    val points: List<LatLon>,
    val cumulativeMeters: DoubleArray,
    val seconds: Double,
    val rise: Double,
    val maneuvers: List<Maneuver>,
    val cost: Double,
) {
    val meters get() = cumulativeMeters.last()

    /** The point [distance] meters along the route. */
    fun at(distance: Double): LatLon {
        val found = cumulativeMeters.binarySearch(distance)
        val index = (if (found >= 0) found else -found - 2).coerceIn(0, points.size - 2)
        val length = cumulativeMeters[index + 1] - cumulativeMeters[index]
        val share = if (length > 0) (distance - cumulativeMeters[index]) / length else 0.0
        val start = points[index]
        val end = points[index + 1]
        return LatLon(
            lerp(start.latitude, end.latitude, share.coerceIn(0.0, 1.0)),
            lerp(start.longitude, end.longitude, share.coerceIn(0.0, 1.0)),
        )
    }
}

private class Finish(val cost: Double, val via: Int, val arc: Int)

/**
 * A* over directed arcs, so turn costs are exact. Start and goal may lie inside edges:
 * the first and last arc are then entered or left part-way.
 * One router reuses its search arrays, so a country-sized map needs them only once.
 */
class Router(private val graph: Graph) {
    private val cost = DoubleArray(graph.arcCount)
    private val previous = IntArray(graph.arcCount)
    private val settled = BooleanArray(graph.arcCount)

    /** Gives up with null as soon as [wanted] turns false. */
    fun route(
        origin: LatLon,
        destination: LatLon,
        mode: Mode,
        profile: Profile,
        wanted: () -> Boolean = { true },
    ): Route? {
        val start = graph.snap(origin) ?: return null
        val goal = graph.snap(destination) ?: return null
        return route(start, goal, mode, profile, wanted)
    }

    @Synchronized
    internal fun route(
        start: Snap,
        goal: Snap,
        mode: Mode,
        profile: Profile,
        wanted: () -> Boolean = { true },
    ): Route? =
        with(graph) {
            val goalNode = if (isInside(goal)) NONE else endpoint(goal)
            val sameNode = !isInside(start) && goalNode == endpoint(start)
            if (goalNode != NONE && sameNode) return null
            cost.fill(Double.POSITIVE_INFINITY)
            previous.fill(NONE)
            settled.fill(false)
            val queue = ArcQueue()
            val toGoal = Estimate(goal.point, mode)
            var finish = Finish(Double.POSITIVE_INFINITY, NONE, NONE)

            fun relax(arc: Int, via: Int, total: Double) {
                if (total >= cost[arc]) return
                cost[arc] = total
                previous[arc] = via
                val end = arcEnd(arc)
                val remaining = toGoal.seconds(latitudes[end], longitudes[end])
                queue.add(arc, total + remaining)
            }

            fun finishWith(arc: Int, via: Int, total: Double) {
                if (total >= finish.cost) return
                finish = Finish(total, via, arc)
                queue.add(arcCount, total)
            }

            fun enterGoal(arc: Int, via: Int, base: Double) {
                if (goalNode != NONE || arc / 2 != goal.edge) return
                val share = goal.along / length(goal.edge)
                val remaining = if (arc % 2 == 0) share else 1 - share
                finishWith(arc, via, base + arcCost(arc, mode, profile) * remaining)
            }

            if (isInside(start)) {
                val share = start.along / length(start.edge)
                val forward = start.edge * 2
                relax(forward, NONE, arcCost(forward, mode, profile) * (1 - share))
                relax(forward + 1, NONE, arcCost(forward + 1, mode, profile) * share)
                if (isInside(goal) && goal.edge == start.edge) {
                    val direct = forward + if (goal.along >= start.along) 0 else 1
                    val distance = abs(goal.along - start.along) / length(start.edge)
                    finishWith(direct, NONE, arcCost(direct, mode, profile) * distance)
                }
            } else {
                forEachOutgoing(endpoint(start)) {
                    relax(it, NONE, arcCost(it, mode, profile))
                    enterGoal(it, NONE, 0.0)
                }
            }

            while (queue.size > 0) {
                val arc = queue.poll()
                if (arc == arcCount) {
                    val head = if (finish.via == NONE) emptyList() else arcsTo(finish.via)
                    return toRoute(head + finish.arc, start, goal, mode, finish.cost)
                }
                if (settled[arc]) continue
                settled[arc] = true
                if (arc % CHECK_INTERVAL == 0 && !wanted()) return null
                if (arcEnd(arc) == goalNode) {
                    return toRoute(arcsTo(arc), start, goal, mode, cost[arc])
                }
                forEachOutgoing(arcEnd(arc)) { next ->
                    if (next != (arc xor 1)) {
                        val base = cost[arc] + turnCost(arc, next, profile)
                        relax(next, arc, base + arcCost(next, mode, profile))
                        enterGoal(next, arc, base)
                    }
                }
            }
            return null
        }

    private fun arcsTo(last: Int) =
        generateSequence(last) { previous[it].takeIf { arc -> arc != NONE } }
            .toList().reversed()
}

/**
 * Lower bound on travel time to [goal]: flat-earth distance, shrunk a little so it never
 * exceeds the true distance within a country, at top speed.
 */
private class Estimate(private val goal: LatLon, mode: Mode) {
    private val eastScale = cos(Math.toRadians(abs(goal.latitude) + LATITUDE_SPREAD))
    private val perDegree = SAFE_METERS_PER_DEGREE / mode.maxSpeed

    fun seconds(latitude: Float, longitude: Float): Double {
        val north = latitude - goal.latitude
        val east = (longitude - goal.longitude) * eastScale
        return sqrt(north * north + east * east) * perDegree
    }
}

/** Binary min-heap of arcs by priority, without boxing. */
private class ArcQueue {
    private var arcs = IntArray(INITIAL_QUEUE)
    private var priorities = DoubleArray(INITIAL_QUEUE)
    var size = 0
        private set

    fun add(arc: Int, priority: Double) {
        if (size == arcs.size) {
            arcs = arcs.copyOf(size * 2)
            priorities = priorities.copyOf(size * 2)
        }
        var child = size++
        while (child > 0 && priorities[(child - 1) / 2] > priority) {
            move((child - 1) / 2, child)
            child = (child - 1) / 2
        }
        arcs[child] = arc
        priorities[child] = priority
    }

    fun poll(): Int {
        val top = arcs[0]
        size--
        val arc = arcs[size]
        val priority = priorities[size]
        var parent = 0
        while (parent * 2 + 1 < size) {
            var child = parent * 2 + 1
            if (child + 1 < size && priorities[child + 1] < priorities[child]) child++
            if (priorities[child] >= priority) break
            move(child, parent)
            parent = child
        }
        arcs[parent] = arc
        priorities[parent] = priority
        return top
    }

    private fun move(from: Int, to: Int) {
        arcs[to] = arcs[from]
        priorities[to] = priorities[from]
    }
}

internal fun Graph.arcCost(arc: Int, mode: Mode, profile: Profile): Double {
    val edge = arc / 2
    val speed = mode.speed(road(edge), surface(edge))
    val against = mode == Mode.Bike && oneway[edge] && arc % 2 == 1
    if (speed == 0.0 || against) return Double.POSITIVE_INFINITY
    return seconds(arc, mode) * profile.penalty(this, edge, climb(arc))
}

internal fun Graph.turnCost(arrival: Int, departure: Int, profile: Profile): Double {
    if (degree(arcEnd(arrival)) < 3) return 0.0
    val isTurn = Turn.of(turnAngle(arrival, departure)) != null
    return if (isTurn) profile.turnSeconds else 0.0
}

private fun Graph.seconds(arc: Int, mode: Mode): Double {
    val edge = arc / 2
    val walking = length(edge) / mode.speed(road(edge), surface(edge))
    return walking + climb(arc) * mode.climbSeconds
}

private fun Graph.isInside(snap: Snap) =
    snap.along > EDGE_EPSILON && snap.along < length(snap.edge) - EDGE_EPSILON

private fun Graph.endpoint(snap: Snap) =
    if (snap.along <= EDGE_EPSILON) from[snap.edge] else to[snap.edge]

private fun Graph.toRoute(
    chain: List<Int>,
    start: Snap,
    goal: Snap,
    mode: Mode,
    cost: Double,
): Route {
    val points = ArrayList<LatLon>()
    val pieceEnds = IntArray(chain.size)
    var seconds = 0.0
    var rise = 0.0
    chain.forEachIndexed { index, arc ->
        val length = length(arc / 2)
        val from = if (index == 0 && isInside(start)) offset(arc, start.along) else 0.0
        val last = index == chain.lastIndex && isInside(goal)
        val to = if (last) offset(arc, goal.along) else length
        val piece = slice(arcPoints(arc), from, to)
        points += if (points.isEmpty()) piece else piece.drop(1)
        pieceEnds[index] = points.lastIndex
        seconds += (to - from) / length * seconds(arc, mode)
        rise += (to - from) / length * climb(arc)
    }
    val cumulative = DoubleArray(points.size)
    for (i in 1 until points.size) {
        cumulative[i] = cumulative[i - 1] + points[i - 1].distanceTo(points[i])
    }
    val pieceMeters = pieceEnds.map { cumulative[it] }
    val maneuvers = maneuvers(chain, pieceMeters, cumulative.last(), mode)
    return Route(points, cumulative, seconds, rise, maneuvers, cost)
}

private fun Graph.offset(arc: Int, along: Double) =
    if (arc % 2 == 0) along else length(arc / 2) - along

private fun slice(points: List<LatLon>, from: Double, to: Double): List<LatLon> {
    val result = arrayListOf(pointAt(points, from))
    var covered = 0.0
    for (i in 1 until points.size) {
        covered += points[i - 1].distanceTo(points[i])
        if (covered > from && covered < to) result += points[i]
    }
    result += pointAt(points, to)
    return result
}

private fun pointAt(points: List<LatLon>, meters: Double): LatLon {
    var covered = 0.0
    for (i in 1 until points.size) {
        val segment = points[i - 1].distanceTo(points[i])
        if (covered + segment >= meters && segment > 0) {
            val share = (meters - covered) / segment
            return LatLon(
                lerp(points[i - 1].latitude, points[i].latitude, share),
                lerp(points[i - 1].longitude, points[i].longitude, share),
            )
        }
        covered += segment
    }
    return points.last()
}

const val ROUGH = 2
private const val NONE = -1
private const val INITIAL_QUEUE = 1024
private const val CHECK_INTERVAL = 1024
private const val SAFE_METERS_PER_DEGREE = 110_000.0
private const val LATITUDE_SPREAD = 3.0
private const val EDGE_EPSILON = 0.5
