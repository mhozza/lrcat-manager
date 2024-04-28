package com.mhozza

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import kotlin.math.max

val WINDOWS_LINUX_DISK_MAPPING = mapOf(
    "E:" to "/media/dataX"
)

fun Connection.sql(sql: String, onResult: ResultSet.() -> Unit) {
    val stmt = createStatement()
    val rs = stmt.executeQuery(sql)

    while (rs.next()) {
        rs.onResult()
    }
}

fun String.maybeConvertToLinuxPath(): String {
    // TODO disable mapping with fag or use config.
    for (mapping in WINDOWS_LINUX_DISK_MAPPING) {
        if (this.startsWith(mapping.key)) {
            return this.replaceFirst(mapping.key, mapping.value)
        }
    }
    println("Directory '$this' not mapped to anything.")
    return this
}

fun Connection.getRootFolders(): Map<Int, Path> {
    val sql = "SELECT id_local, absolutePath FROM AgLibraryRootFolder"

    return buildMap {
        sql(sql) {
            val path = Path.of(getString("absolutePath").maybeConvertToLinuxPath())
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
            if (rating == null && other.rating == null) null else max(rating ?: 0 , other.rating ?: 0)
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

fun connect() {
    var conn: Connection? = null
    try {
        val url = "jdbc:sqlite:/home/mio/t/2024.lrcat.db"
        conn = DriverManager.getConnection(url)
        println("Connection to SQLite has been established.")

        val rootFolders = conn.getRootFolders()
        val folders = conn.getFolders(rootFolders)
        val metadata = conn.getPhotosMetadata()
        val photos = conn.getPhotos(folders, metadata)
        for (unpickedPhoto in photos.filter { it.metadata.picked != Picked.PICKED && it.metadata.rating == null }) {
            println(unpickedPhoto.path)
        }
    } catch (e: SQLException) {
        println(e.message)
    } finally {
        try {
            conn?.close()
        } catch (ex: SQLException) {
            println(ex.message)
        }
    }
}

fun main() {
    connect()
}
