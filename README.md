# Kiesel

Offline walking and cycling navigation for Android. No `INTERNET` permission: maps come
as region packs you download in the browser and import into the app.

## Features

- **Map**: MapLibre GPU vector map. Calm light/dark styles, smooth zoom, rotation and
  tilt, compass when turned. Labels in the app language where OSM has them.
- **Profiles**: Fastest · Easiest (flat, smooth, few turns, no stairs) · Scenic (parks,
  woods, water) · Quiet (away from busy streets). Walk or Bike (respects one-ways).
- **Navigation**: tilted follow camera turning with the phone's compass, puck snapped
  to the route and held still against GPS jitter, turn banner with "then" preview,
  roundabout exits, forks ("keep left"), silent on natural bends, rerouting after two
  off-route fixes, arrival detection, lock-screen instruction.
- **Elevation**: height profile with ascent and descent for every route.
- **Voice**: on-device Android TTS (any engine: Google, SherpaTTS, RHVoice, eSpeak NG).
  Speaks the app language when an offline voice for it is installed, otherwise the
  language of the engine's default voice, with directions in that language. Picks the
  best voice, ducks music. Cues: walk 60 m / now, bike 400 m / 120 m / now.
- **Search**: accent-insensitive ("zurich" finds "Zürich"), cities first, then nearest.
  Tap a map label to pick it; hold the map to drop a pin (or set a start without GPS).
- **Languages**: English, German, Spanish, French, Portuguese, Italian, Russian,
  Turkish, Arabic, Urdu, Hindi, Bengali, Chinese, Japanese, Korean, Indonesian,
  Vietnamese. Follows the system language; per-app choice in Android settings
  (Android 13+). RTL supported. Units and times via ICU.

Install a natural voice under Android settings → Text-to-speech output: pick the engine
and download a voice (Google: Install voice data; SherpaTTS/RHVoice: in their app).
Without Google services (GrapheneOS), install SherpaTTS or RHVoice from F-Droid.

## Maps

1. In the app: Maps → **Download** opens the
   [map packs release](https://github.com/tn3w/kiesel/releases/tag/maps).
2. Download `kiesel-<region>.zip` in the browser.
3. Open it with Kiesel from the download, or Maps → **Import**.

Android's DownloadManager needs the `INTERNET` permission, so the browser does the
download. Several regions can be installed; tap one to switch.

A pack is a zip with one folder per region:

| Entry | Content | Compression |
|---|---|---|
| `graph` | routing graph, spatial index, turn bearings, places | deflate (~50 %) |
| `heights` | SRTM ground heights, 3″ grid | deflate |

`graph` and `heights` are raw little-endian arrays (byte count, values, 8-byte padding).
The app memory-maps them, so a map lives in the page cache, not on the Java heap: only
place names and the router's per-arc search arrays (13 B per arc) use heap. Switzerland:
graph mapped in 0.1 s, +34 MB heap, +89 MB router. Packs from before this format must be
downloaded again.
| `tiles.pmtiles` | OpenMapTiles vector tiles, unused layers dropped | gzip tiles |

## Build

```sh
gradle :core:buildPacks -Pregions=europe/switzerland,europe/liechtenstein
gradle :app:assembleRelease    # signed with the debug key, installable
gradle :core:test
```

`buildPacks` runs `core/src/main/kotlin/kiesel/Builder.kt`: downloads the Geofabrik
extract, builds tiles with Planetiler (on the build's JVM, 21+), imports the graph
with SRTM climb, samples heights and writes `build/packs/kiesel-<region>.zip`.
Downloads are cached in `build/maps`.

Import memory stays within the 6 GB `buildPacks` heap for the largest regions (Bayern,
Nordrhein-Westfalen, Baden-Württemberg, Niedersachsen, Netherlands): coordinates are
kept as OSM's 1e-7° integers, the importer's node tables are freed before the graph
compiles, and compiling avoids boxed collections. Switzerland peaks at 1.7 GB live.

The **Build map packs** workflow (Actions → Run workflow, regions as input) builds the
packs on GitHub and uploads them to the `maps` release.

Judge speed on the release build: debuggable builds route 3–5× slower on ART.
Zürich → Bern (114 km, walk) on the emulator: first route 1.1 s, all four profiles
5.4 s. The selected profile shows first; the others are cancelled when
navigation starts.

## Code

- `core`: plain Kotlin, unit-tested.
  - `Builder.kt`: region pack builder.
  - `OsmImport.kt`: PBF → ways, places, scenic areas, roundabouts.
  - `Graph.kt`: compact junction graph, spatial index, turn bearings, place search.
  - `Columns.kt`: pack file format; writes arrays, maps them back as buffer views.
  - `Router.kt`: A* over directed arcs with turn costs, primitive heap, reusable arrays.
  - `Guidance.kt`: maneuvers and the `Navigator` (progress, cues, off-route).
  - `Elevation.kt`, `Heights.kt`: SRTM tiles at build time, compact grid in packs.
- `app`: Jetpack Compose + MapLibre.
  - `AppModel.kt`: regions, location, planning, navigation.
  - `MapView.kt`: map, style colors, route/pin layers, puck, camera, locate + compass.
  - `Screen.kt`: theme, bottom sheet (search, routes, trip, maps), turn banner.
  - `Language.kt`: localized instructions, distances, durations, voice.
  - `assets/style.json`: map style with `@color` tokens filled per theme.

Map data © OpenStreetMap contributors, © OpenMapTiles. Heights: SRTM via AWS Terrain
Tiles.
