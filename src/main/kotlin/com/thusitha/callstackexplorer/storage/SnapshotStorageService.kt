package com.thusitha.callstackexplorer.storage

import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.thusitha.callstackexplorer.capture.SnapshotModel
import com.thusitha.callstackexplorer.capture.SnapshotSummary
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Persists [SnapshotModel]s as one JSON file per snapshot under
 * `<project>/.idea/callStackExplorer/snapshots/`, plus an `index.json` for
 * cheap listing without deserializing every snapshot body.
 */
@Service(Service.Level.PROJECT)
class SnapshotStorageService(private val project: Project) {

    private val log = Logger.getInstance(SnapshotStorageService::class.java)

    private val gson = GsonBuilder()
        .registerTypeAdapter(Instant::class.java, InstantTypeAdapter())
        .setPrettyPrinting()
        .create()

    private val baseDir: Path
        get() = Path.of(project.basePath ?: ".", ".idea", "callStackExplorer", "snapshots")

    private val indexFile: Path
        get() = baseDir.resolve("index.json")

    @Synchronized
    fun save(snapshot: SnapshotModel) {
        Files.createDirectories(baseDir)
        val file = baseDir.resolve("${snapshot.id}.json")
        Files.writeString(file, gson.toJson(snapshot))

        val index = readIndex().toMutableList()
        index.removeAll { it.id == snapshot.id }
        index.add(0, SnapshotSummary(snapshot.id, snapshot.label, snapshot.capturedAt, snapshot.effectiveKind))
        writeIndex(index)
    }

    @Synchronized
    fun list(): List<SnapshotSummary> = readIndex().sortedByDescending { it.capturedAt }

    fun load(id: String): SnapshotModel? {
        val file = baseDir.resolve("$id.json")
        if (!Files.exists(file)) return null
        return try {
            gson.fromJson(Files.readString(file), SnapshotModel::class.java)
        } catch (e: Exception) {
            log.warn("Failed to load snapshot $id", e)
            null
        }
    }

    fun delete(id: String) = deleteAll(listOf(id))

    /** Deletes several snapshots with a single index rewrite. */
    @Synchronized
    fun deleteAll(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val doomed = ids.toSet()
        doomed.forEach { Files.deleteIfExists(baseDir.resolve("$it.json")) }
        writeIndex(readIndex().filter { it.id !in doomed })
    }

    @Synchronized
    fun rename(id: String, newLabel: String) {
        val snapshot = load(id) ?: return
        save(snapshot.copy(label = newLabel))
    }

    /** Keeps only the [limit] most recent snapshots, deleting the oldest first. */
    @Synchronized
    fun pruneToLimit(limit: Int) {
        if (limit <= 0) return
        val index = readIndex().sortedByDescending { it.capturedAt }
        if (index.size <= limit) return
        deleteAll(index.drop(limit).map { it.id })
    }

    private fun readIndex(): List<SnapshotSummary> {
        if (!Files.exists(indexFile)) return emptyList()
        return try {
            val type = com.google.gson.reflect.TypeToken.getParameterized(List::class.java, SnapshotSummary::class.java).type
            gson.fromJson<List<SnapshotSummary>>(Files.readString(indexFile), type) ?: emptyList()
        } catch (e: Exception) {
            log.warn("Failed to read snapshot index", e)
            emptyList()
        }
    }

    private fun writeIndex(index: List<SnapshotSummary>) {
        Files.createDirectories(baseDir)
        Files.writeString(indexFile, gson.toJson(index))
    }

    companion object {
        fun getInstance(project: Project): SnapshotStorageService = project.getService(SnapshotStorageService::class.java)
    }
}

private class InstantTypeAdapter : TypeAdapter<Instant>() {
    override fun write(out: JsonWriter, value: Instant?) {
        if (value == null) out.nullValue() else out.value(value.toString())
    }

    override fun read(reader: JsonReader): Instant? {
        val text = reader.nextString() ?: return null
        return Instant.parse(text)
    }
}
