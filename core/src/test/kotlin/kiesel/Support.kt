package kiesel

internal fun Graph.route(start: Int, goal: Int, mode: Mode, profile: Profile) =
    Router(this).route(snap(point(start))!!, snap(point(goal))!!, mode, profile)

internal fun Graph.route(
    origin: LatLon,
    destination: LatLon,
    mode: Mode,
    profile: Profile,
) =
    Router(this).route(origin, destination, mode, profile)

internal fun assertNear(expected: LatLon, actual: LatLon) {
    kotlin.test.assertEquals(expected.latitude, actual.latitude, 1e-7)
    kotlin.test.assertEquals(expected.longitude, actual.longitude, 1e-7)
}
