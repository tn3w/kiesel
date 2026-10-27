package kiesel

import java.io.File
import java.io.OutputStream
import java.net.URI
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds one compressed pack per region: map tiles, routing and search graph, heights;
 * split into parts when it is too big for one download.
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
    val folder = File(work, name).apply { mkdirs() }
    val heights = Heights.of(graph, elevation::at)
    val files = mapOf(
        "graph" to File(folder, "graph").write(graph::write),
        "heights" to File(folder, "heights").write(heights::write),
        "tiles.pmtiles" to tiles,
    )
    for (part in zipParts(name, files, output)) {
        println("$region → ${part.path} (${part.length() / 1_000_000} MB)")
    }
}

private fun File.write(content: (OutputStream) -> Unit) =
    apply { outputStream().buffered().use(content) }

private class Piece(val entry: String, val file: File, val offset: Long, val size: Long)

/**
 * Zips [files] into kiesel-<name>.zip, or kiesel-<name>-<i>of<n>.zip beyond GitHub's
 * 2 GiB asset limit; a file too big for one zip goes in pieces "<file>.<i>of<n>".
 */
private fun zipParts(name: String, files: Map<String, File>, output: File): List<File> {
    val pieces = files.flatMap { (entry, file) ->
        val count = ((file.length() + PIECE_BYTES - 1) / PIECE_BYTES).coerceAtLeast(1)
        (0 until count).map {
            val suffix = if (count == 1L) "" else ".${it + 1}of$count"
            val offset = it * PIECE_BYTES
            val size = minOf(PIECE_BYTES, file.length() - offset)
            Piece("$name/$entry$suffix", file, offset, size)
        }
    }
    val parts = ArrayList<MutableList<Piece>>()
    for (piece in pieces.sortedByDescending { it.size }) {
        val part = parts.firstOrNull { it.sumOf(Piece::size) + piece.size <= PIECE_BYTES }
            ?: ArrayList<Piece>().also(parts::add)
        part += piece
    }
    val previous = Regex("kiesel-$name(-\\d+of\\d+)?\\.zip")
    output.listFiles { file -> previous.matches(file.name) }?.forEach { it.delete() }
    return parts.mapIndexed { index, part ->
        val suffix = if (parts.size == 1) "" else "-${index + 1}of${parts.size}"
        File(output, "kiesel-$name$suffix.zip").also { writeZip(it, part) }
    }
}

/** Entries are stored, not deflated, so the app copies them at disk speed. */
private fun writeZip(zip: File, pieces: List<Piece>) =
    ZipOutputStream(zip.outputStream().buffered(1 shl 20)).use { output ->
        val target = Channels.newChannel(output)
        for (piece in pieces) {
            val bytes = FileChannel.open(piece.file.toPath()).use {
                it.map(FileChannel.MapMode.READ_ONLY, piece.offset, piece.size)
            }
            output.putNextEntry(ZipEntry(piece.entry).apply {
                method = ZipEntry.STORED
                size = piece.size
                crc = CRC32().apply { update(bytes.duplicate()) }.value
            })
            while (bytes.hasRemaining()) target.write(bytes)
            output.closeEntry()
        }
    }

private fun buildTiles(extract: File, work: File, tiles: File): File {
    if (tiles.lastModified() > extract.lastModified()) return tiles
    val planetiler = download(PLANETILER, File(work, "planetiler.jar"))
    val data = File(work, "planetiler").path
    val command = listOf(
        File(System.getProperty("java.home"), "bin/java").path,
        "-Xmx8g", "-jar", planetiler.path,
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

/** Size of each piece of a split file but the last; the app writes pieces in place. */
const val PIECE_BYTES = 1_900_000_000L

private const val GEOFABRIK = "https://download.geofabrik.de"
private const val PLANETILER =
    "https://github.com/onthegomap/planetiler/releases/download/v0.10.2/planetiler.jar"
private const val LANGUAGES = "en,de,es,fr,pt,it,ru,tr,ar,ur,hi,bn,zh,zh-Hans,ja,ko,id,vi"
private const val UNUSED_LAYERS = "housenumber,aeroway,aerodrome_label,landuse"
