package kiesel

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class Turn {
    SharpLeft, Left, SlightLeft, KeepLeft, KeepRight, SlightRight, Right, SharpRight,
    Roundabout, Arrive;

    companion object {
        fun of(angle: Double) = when {
            abs(angle) < 30 -> null
            angle < -135 -> SharpLeft
            angle < -60 -> Left
            angle < 0 -> SlightLeft
            angle <= 60 -> SlightRight
            angle <= 135 -> Right
            else -> SharpRight
        }
    }
}

/** [exit] counts roundabout exits from 1; [meters] is measured from the route start. */
class Maneuver(val turn: Turn, val street: String?, val meters: Double, val exit: Int = 0)

internal fun Graph.maneuvers(
    chain: List<Int>,
    arcEnds: List<Double>,
    total: Double,
    mode: Mode,
): List<Maneuver> {
    val result = ArrayList<Maneuver>()
    var index = 1
    while (index < chain.size) {
        val arrival = chain[index - 1]
        val departure = chain[index]
        if (isRoundabout(departure / 2) && !isRoundabout(arrival / 2)) {
            val exit = (index until chain.size)
                .firstOrNull { !isRoundabout(chain[it] / 2) } ?: chain.size
            val passed = (index until exit - 1).count { hasExit(chain[it], mode) }
            val street = chain.getOrNull(exit)?.let { streetName(it / 2) }
            result += Maneuver(Turn.Roundabout, street, arcEnds[index - 1], passed + 1)
            index = exit + 1
            continue
        }
        turnAt(arrival, departure, mode)?.let {
            result += Maneuver(it, streetName(departure / 2), arcEnds[index - 1])
        }
        index++
    }
    return result + Maneuver(Turn.Arrive, null, total)
}

/** Silent when the way simply bends on; "keep" when two ways fork almost straight. */
private fun Graph.turnAt(arrival: Int, departure: Int, mode: Mode): Turn? {
    val others = alternatives(arrival, mode).filter { it != departure }
        .map { turnAngle(arrival, it) }
    if (others.isEmpty()) return null
    val angle = turnAngle(arrival, departure)
    val turn = Turn.of(angle)
    if (turn == null) {
        val fork = others.firstOrNull { abs(it) < FORK_DEGREES } ?: return null
        return if (angle < fork) Turn.KeepLeft else Turn.KeepRight
    }
    val bendsOn = abs(angle) < BEND_DEGREES &&
        others.all { abs(it) > abs(angle) + BEND_MARGIN_DEGREES }
    return if (bendsOn) null else turn
}

private fun Graph.alternatives(arrival: Int, mode: Mode) = buildList {
    forEachOutgoing(arcEnd(arrival)) {
        val passable = arcCost(it, mode, Profile.Fastest).isFinite()
        if (it != (arrival xor 1) && passable) add(it)
    }
}

private fun Graph.hasExit(arc: Int, mode: Mode) =
    alternatives(arc, mode).any { !isRoundabout(it / 2) }

/** What to say: [meters] is null when the maneuver is due now. */
class Announcement(val maneuver: Maneuver, val meters: Double?, val then: Maneuver?)

class Guidance(
    val maneuver: Maneuver,
    val next: Maneuver?,
    val metersToManeuver: Double,
    val remainingMeters: Double,
    val remainingSeconds: Double,
    val position: LatLon,
    val offRoute: Boolean,
    val arrived: Boolean,
    val announcement: Announcement?,
)

/** Follows a [Route] with position fixes and says when to speak. */
class Navigator(private val route: Route, mode: Mode) {
    private class Fix(
        val segment: Int,
        val along: Double,
        val offset: Double,
        val point: LatLon,
    )

    private val cues = if (mode == Mode.Bike) BIKE_CUES else WALK_CUES
    private val spoken = IntArray(route.maneuvers.size) { -1 }
    private var segment = 0
    private var strays = 0
    private var started = false

    fun update(position: LatLon): Guidance {
        val fix = locate(position)
        segment = fix.segment
        val onRoute = fix.offset <= OFF_ROUTE_METERS
        strays = if (onRoute) 0 else strays + 1
        val index = route.maneuvers.indexOfFirst { it.meters > fix.along - PASSED_METERS }
        val maneuver = route.maneuvers[index]
        val meters = max(0.0, maneuver.meters - fix.along)
        val remaining = route.meters - fix.along
        return Guidance(
            maneuver = maneuver,
            next = route.maneuvers.getOrNull(index + 1),
            metersToManeuver = meters,
            remainingMeters = remaining,
            remainingSeconds = route.seconds * remaining / max(route.meters, 1.0),
            position = if (onRoute) fix.point else position,
            offRoute = strays >= STRAY_FIXES,
            arrived = onRoute && maneuver.turn == Turn.Arrive && meters <= cues.last(),
            announcement = if (onRoute) announcement(index, meters) else null,
        )
    }

    private fun announcement(index: Int, meters: Double): Announcement? {
        val stage = cues.indexOfLast { meters <= it }
        val first = !started
        started = true
        if (!first && stage <= spoken[index]) return null
        spoken[index] = max(stage, spoken[index])
        val maneuver = route.maneuvers[index]
        val then = route.maneuvers.getOrNull(index + 1)
            ?.takeIf { it.meters - maneuver.meters < cues[cues.lastIndex - 1] / 2 }
        val now = stage == cues.lastIndex
        if (now && then != null) spoken[index + 1] = cues.lastIndex - 1
        return Announcement(maneuver, if (now) null else meters, then)
    }

    private fun locate(position: LatLon): Fix {
        val last = route.points.size - 1
        val nearby = (max(0, segment - 2) until min(segment + LOOKAHEAD_SEGMENTS, last))
            .map { project(position, it) }.minBy { it.offset }
        if (nearby.offset <= OFF_ROUTE_METERS) return nearby
        return (0 until last).map { project(position, it) }.minBy { it.offset }
    }

    private fun project(position: LatLon, segment: Int): Fix {
        val start = route.points[segment]
        val end = route.points[segment + 1]
        val projection = project(position, start, end)
        val length = route.cumulativeMeters[segment + 1] - route.cumulativeMeters[segment]
        val along = route.cumulativeMeters[segment] + projection.share * length
        val point = LatLon(
            lerp(start.latitude, end.latitude, projection.share),
            lerp(start.longitude, end.longitude, projection.share),
        )
        return Fix(segment, along, projection.offset, point)
    }
}

/** Distances that trigger a cue, far to near; the last one means "now". */
private val WALK_CUES = listOf(60.0, 12.0)
private val BIKE_CUES = listOf(400.0, 120.0, 25.0)
private const val FORK_DEGREES = 35.0
private const val BEND_DEGREES = 60.0
private const val BEND_MARGIN_DEGREES = 40.0
private const val PASSED_METERS = 5.0
private const val OFF_ROUTE_METERS = 35.0
private const val STRAY_FIXES = 2
private const val LOOKAHEAD_SEGMENTS = 40
