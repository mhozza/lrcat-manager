package com.mhozza

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.pair
import com.github.ajalt.clikt.parameters.types.path
import mu.KotlinLogging
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import kotlin.math.max

private val logger = KotlinLogging.logger {}

fun Connection.sql(sql: String, onResult: ResultSet.() -> Unit) {
    val stmt = createStatement()
    val rs = stmt.executeQuery(sql)

    while (rs.next()) {
        rs.onResult()
    }
}

fun String.mapDirectories(directoryMapping: Map<String, String>): String {
    for (mapping in directoryMapping) {
        if (this.startsWith(mapping.key)) {
            return this.replaceFirst(mapping.key, mapping.value)
        }
    }
    println("Directory '$this' not mapped to anything.")
    return this
}

fun Connection.getRootFolders(directoryMapping: Map<String, String>): Map<Int, Path> {
    val sql = "SELECT id_local, absolutePath FROM AgLibraryRootFolder"

    return buildMap {
        sql(sql) {
            val path = Path.of(getString("absolutePath").mapDirectories(directoryMapping))
            put(getInt("id_local"), path)
        }
    }
}

fun Connection.getFolders(rootFolders: Map<Int, Path>): Map<Int, Path> {
    val sql = "SELECT id_local, parentId, pathFromRoot, rootFolder FROM AgLibraryFolder"

    return buildMap {
        sql(sql) {
            val rootPath = rootFolders.getValue(getInt("rootFolder"))
            val path = rootPath.resolve(getString("pathFromRoot"))
            put(getInt("id_local"), path)
        }
    }
}

enum class Picked {
    UNPICKED,
    PICKED,
    REJECTED;

    companion object {
        fun fromInt(picked: Int): Picked = when (picked) {
            0 -> UNPICKED
            1 -> PICKED
            else -> REJECTED
        }
    }
}

data class PhotoMetadata(val picked: Picked, val rating: Int?) {
    fun combineWith(other: PhotoMetadata): PhotoMetadata {
        return PhotoMetadata(
            when {
                picked == Picked.PICKED || other.picked == Picked.PICKED -> Picked.PICKED
                picked == Picked.UNPICKED || other.picked == Picked.UNPICKED -> Picked.UNPICKED
                else -> Picked.REJECTED
            },
            if (rating == null && other.rating == null) null else max(rating ?: 0, other.rating ?: 0)
        )
    }

}

fun ResultSet.getIntOrNull(colName: String): Int? {
    val res = getInt(colName)
    return if (wasNull()) null else res
}

fun Connection.getPhotosMetadata(): Map<Int, PhotoMetadata> {
    val sql = "SELECT rootFile, pick, rating FROM Adobe_images"

    return buildMap {
        sql(sql) {
            val photoId = getInt("rootFile")
            val existingMetadata = get(photoId)
            val newMetadata = PhotoMetadata(
                Picked.fromInt(getInt("pick")),
                rating = getIntOrNull("rating")
            )

            val metadata = existingMetadata?.combineWith(newMetadata) ?: newMetadata

            put(
                photoId, metadata
            )
        }
    }
}

data class Photo(val id: Int, val path: Path, val metadata: PhotoMetadata)

fun Connection.getPhotos(folders: Map<Int, Path>, metadata: Map<Int, PhotoMetadata>): List<Photo> {
    val sql = "SELECT id_local, folder, originalFilename FROM AgLibraryFile"

    return buildList {
        sql(sql) {
            val id = getInt("id_local")
            val folder = folders.getValue(getInt("folder"))
            val path = folder.resolve(getString("originalFilename"))
            val photoMetadata = metadata.getValue(id)
            add(Photo(id, path, photoMetadata))
        }
    }
}

fun withSqlite3Db(path: Path, block: Connection.() -> Unit) {
    var conn: Connection? = null
    try {
        val url = "jdbc:sqlite:$path"
        conn = DriverManager.getConnection(url)
        logger.info { "Connection to SQLite has been established." }
        conn.block()
    } catch (e: SQLException) {
        logger.error { e.message }
    } finally {
        try {
            conn?.close()
        } catch (ex: SQLException) {
            logger.error { ex.message }
        }
    }
}

class LrcatManager : CliktCommand() {
    private val path: Path by argument().path(mustExist = true, mustBeReadable = true, canBeDir = false)
    private val directoryMapping: List<Pair<String, String>> by option("-m", "--durectory-mapping").pair().multiple()

    override fun run() {
        withSqlite3Db(path) {
            val rootFolders = getRootFolders(directoryMapping.toMap())
            val folders = getFolders(rootFolders)
            val metadata = getPhotosMetadata()
            val photos = getPhotos(folders, metadata)
            for (unpickedPhoto in photos.filter { it.metadata.picked != Picked.PICKED && it.metadata.rating == null }) {
                println(unpickedPhoto.path)
            }
        }
    }
}

fun main(args: Array<String>) {
    LrcatManager().main(args)
}
