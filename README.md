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

Whole countries, e.g. all of Germany: search, map and routing across the country.

1. In the app: Maps → **Download** opens the
   [map packs release](https://github.com/tn3w/kiesel/releases/tag/maps).
2. Download `kiesel-<region>.zip` in the browser. Packs over GitHub's 2 GiB file limit
   come in parts, `kiesel-<region>-1of3.zip` … `-3of3.zip`: download all of them.
3. Open them with Kiesel from the download, or Maps → **Import** (select all parts at
   once). Parts can be imported in any order; the region appears once all are in.

Android's DownloadManager needs the `INTERNET` permission, so the browser does the
download. Several regions can be installed; tap one to switch.

A pack (or each part) is a zip with one folder per region, entries stored uncompressed
so import runs at disk speed:

| Entry | Content |
|---|---|
| `graph` | routing graph, spatial index, turn bearings, places, street names |
| `heights` | SRTM ground heights, 3″ grid |
| `tiles.pmtiles` | OpenMapTiles vector tiles, unused layers dropped |

A file too big for one part is split into pieces `<file>.<i>of<n>`, each 1.9 GB but the
last; the app writes every piece straight to its offset, so nothing is copied twice.

`graph` and `heights` are raw little-endian arrays (byte count, values, 8-byte padding);
`graph` starts with a format number, and packs of an older format fail to load and must
be downloaded again. The app memory-maps every array on its own (files may exceed
2 GB), so a map lives in the page cache, not on the Java heap: names and search keys
are UTF-8 bytes searched in place.

| Germany | |
|---|---|
| Download | 4.7 GB in 3 parts (graph 1.9 GB, heights 0.2 GB, tiles 2.6 GB) |
| Graph | 20 M junctions, 26 M edges, 3.2 M places |
| Import, Pixel 7a | 19 s (flash writes ~300 MB/s: 15 s is the floor) |
| Load | 10 ms, 13 MB heap |
| Search, Pixel 7a | 0.25–0.5 s |
| Route, Pixel 7a | Zeulenroda → Gera (30 km): < 0.1 s per profile |
| | Zeulenroda → München (320–410 km): walk 0.5–15 s, bike 4–14 s |

The router keeps per-arc search state (9 B per arc) in pages created only where the
search goes; node and edge numbers follow a Z-order curve, so nearby arcs share pages.
Search state may use half the heap, enough for Berlin → München. Beyond 20 km the
straight-line estimate is weighted (walk ×1.3 fastest, ×1.6 others; bike ×1.25 more):
routes may cost that much more than the best one but in practice stay within ~1.5 %,
while searching a far smaller area. Shorter routes are exact.

## Build

```sh
gradle :core:buildPacks -Pregions=europe/germany,europe/liechtenstein
gradle :app:assembleRelease    # signed with the debug key, installable
gradle :core:test
```

`buildPacks` runs `core/src/main/kotlin/kiesel/Builder.kt`: downloads the Geofabrik
extract, builds tiles with Planetiler (on the build's JVM, 21+; reused while newer than
the extract), imports the graph with SRTM climb, samples heights and writes
`build/packs/kiesel-<region>.zip` or its parts. Downloads are cached in `build/maps`.

The importer keeps node tables, ways and the graph under construction in primitive
arrays, parses with all cores and frees its node tables before compiling. Germany
(4.9 GB extract): import 5 min, under 8 GB live in the 12 GB `buildPacks` heap; whole
build 16 min on 16 cores.

The **Build map packs** workflow (Actions → Run workflow, regions as input) builds each
region on its own runner (disk freed for country extracts), uploads it to the `maps`
release and removes parts left from earlier builds of that region.

Judge speed on the release build: debuggable builds are several times slower on ART.

## Code

- `core`: plain Kotlin, unit-tested.
  - `Builder.kt`: region pack builder, splits packs into parts.
  - `OsmImport.kt`: PBF → ways, places, scenic areas, roundabouts; Z-order numbering.
  - `Graph.kt`: compact junction graph, spatial index, turn bearings, place search.
  - `Columns.kt`: pack file format; writes arrays, maps each back as a buffer view;
    `Texts` for strings searched in place.
  - `Router.kt`: A* over directed arcs with turn costs, primitive heap, paged state.
  - `Guidance.kt`: maneuvers and the `Navigator` (progress, cues, off-route).
  - `Elevation.kt`, `Heights.kt`: SRTM tiles at build time, compact grid in packs.
- `app`: Jetpack Compose + MapLibre.
  - `AppModel.kt`: regions, parallel import of packs and parts, location, planning,
    navigation.
  - `MapView.kt`: map, style colors, route/pin layers, puck, camera, locate + compass.
  - `Screen.kt`: theme, bottom sheet (search, routes, trip, maps), turn banner.
  - `Language.kt`: localized instructions, distances, durations, voice.
  - `assets/style.json`: map style with `@color` tokens filled per theme.

Map data © OpenStreetMap contributors, © OpenMapTiles. Heights: SRTM via AWS Terrain
Tiles.
