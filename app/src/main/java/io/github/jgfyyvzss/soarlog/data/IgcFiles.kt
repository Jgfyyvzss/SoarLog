package io.github.jgfyyvzss.soarlog.data

import android.content.ContentResolver
import android.net.Uri
import io.github.jgfyyvzss.soarlog.core.igc.IgcLibrary
import io.github.jgfyyvzss.soarlog.core.igc.StoredIgc
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The received IGC files, for the logbook import and the WeGlide upload alike. Disk work runs on the IO dispatcher,
 * and [files] always holds the current list, newest first.
 */
class IgcFiles(
    private val library: IgcLibrary,
    scope: CoroutineScope,
    /** A folder of files stored the old way, moved into [library] at start-up. */
    moveFrom: File? = null,
) {
    private val _files = MutableStateFlow<List<StoredIgc>>(emptyList())
    val files: StateFlow<List<StoredIgc>> = _files

    init {
        // Old files move across and expired ones go at start-up, before anything shows the list.
        scope.launch {
            moveFrom?.let { old -> io { runCatching { library.moveFrom(old) } } }
            refresh()
        }
    }

    suspend fun add(name: String, bytes: ByteArray): StoredIgc = io { library.add(name, bytes) }.also { refresh() }

    /** Reads a picked or shared file and stores it; null if it cannot be read or is far too large for a flight log. */
    suspend fun add(resolver: ContentResolver, uri: Uri): StoredIgc? {
        val bytes = io {
            runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    while (out.size() <= MAX_BYTES) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray().takeIf { it.size <= MAX_BYTES }
                }
            }.getOrNull()
        } ?: return null
        return add(Documents.displayName(resolver, uri), bytes)
    }

    suspend fun get(id: String): StoredIgc? = io { library.get(id) }
    suspend fun bytes(id: String): ByteArray? = io { library.bytes(id) }

    suspend fun update(id: String, change: (StoredIgc) -> StoredIgc) {
        io { library.update(id, change) }
        refresh()
    }

    suspend fun remove(id: String) {
        io { library.remove(id) }
        refresh()
    }

    private suspend fun refresh() {
        _files.value = io { library.list() }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        /** A day of one-second fixes is about 7 MB; anything far larger is not a flight log. */
        const val MAX_BYTES = 32 * 1024 * 1024
    }
}
