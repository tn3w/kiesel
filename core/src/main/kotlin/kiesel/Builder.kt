package kiesel

import java.io.File
import java.io.ObjectOutputStream
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds one compressed pack per region: map tiles, routing and search graph, heights.
 * Usage: <work directory> <output directory> <Geofabrik region>...
 * Example: build/maps build/packs europe/switzerland europe/liechtenstein
 */
fun main(args: Array<String>) {
    val work = File(args[0]).apply { mkdirs() }
    val output = File(args[1]).apply { mkdirs() }
    args.drop(2).forEach { buildPack(it, work, output) }
}

private fun buildPack(region: String, work: File, output: File) {
    val name = region.substringAfterLast('/')
    val source = "$GEOFABRIK/$region-latest.osm.pbf"
    val extract = download(source, File(work, "$name.osm.pbf"))
    val tiles = buildTiles(extract, work, File(work, "$name.pmtiles"))
    val elevation = Elevation(File(work, "elevation"))
    val graph = importExtract({ extract.inputStream() }, elevation::at)
    val heights = Heights.of(graph, elevation::at)
    val pack = File(output, "kiesel-$name.zip")
    ZipOutputStream(pack.outputStream().buffered()).use { zip ->
        zip.putObject("$name/graph", graph)
        zip.putObject("$name/heights", heights)
        zip.putFile("$name/tiles.pmtiles", tiles)
    }
    println("$region → ${pack.path} (${pack.length() / 1_000_000} MB)")
}

private fun buildTiles(extract: File, work: File, tiles: File): File {
    val planetiler = download(PLANETILER, File(work, "planetiler.jar"))
    val data = File(work, "planetiler").path
    val command = listOf(
        "java", "-Xmx8g", "-jar", planetiler.path,
        "--osm-path=${extract.path}",
        "--output=${tiles.path}",
        "--download_dir=$data/sources",
        "--tmpdir=$data/tmp",
        "--tile_weights=$data/tile_weights.tsv.gz",
        "--download",
        "--force",
        "--languages=$LANGUAGES",
        "--exclude_layers=$UNUSED_LAYERS",
        "--exclude_ids=true",
    )
    val exit = ProcessBuilder(command).inheritIO().start().waitFor()
    check(exit == 0) { "Planetiler failed for ${extract.name}" }
    return tiles
}

private fun download(url: String, file: File): File {
    if (file.exists()) return file
    val partial = File(file.path + ".part")
    URI(url).toURL().openStream().use { input ->
        partial.outputStream().use(input::copyTo)
    }
    partial.renameTo(file)
    return file
}

private fun ZipOutputStream.putObject(name: String, value: Any) {
    putNextEntry(ZipEntry(name))
    ObjectOutputStream(this).run {
        writeObject(value)
        flush()
    }
    closeEntry()
}

private fun ZipOutputStream.putFile(name: String, file: File) {
    putNextEntry(ZipEntry(name))
    file.inputStream().use { it.copyTo(this) }
    closeEntry()
}

private const val GEOFABRIK = "https://download.geofabrik.de"
private const val PLANETILER =
    "https://github.com/onthegomap/planetiler/releases/download/v0.10.2/planetiler.jar"
private const val LANGUAGES = "en,de,es,fr,pt,it,ru,tr,ar,ur,hi,bn,zh,zh-Hans,ja,ko,id,vi"
private const val UNUSED_LAYERS = "housenumber,aeroway,aerodrome_label,landuse"
