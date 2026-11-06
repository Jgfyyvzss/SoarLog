package io.github.jgfyyvzss.soarlog.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.github.jgfyyvzss.soarlog.core.Msg
import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.msg
import io.github.jgfyyvzss.soarlog.core.backup.BackupSchedule
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** A failure that already carries the message to show. */
private class MsgFailure(val shown: Msg) : Exception()

sealed interface BackupOutcome {
    data class Written(val fileName: String) : BackupOutcome
    data object NoFolder : BackupOutcome
    data class Failed(val message: Msg) : BackupOutcome
}

/**
 * Writes timestamped backups into the folder the pilot picked once, through the Storage Access Framework, and
 * keeps the newest [BackupSchedule.KEEP_FILES]. Only files named like backups are ever deleted.
 */
class BackupFolder(
    private val context: Context,
    private val backup: BackupService,
    private val settings: SettingsRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val resolver get() = context.contentResolver

    /** Remembers the folder with a persisted read and write permission. */
    suspend fun choose(tree: Uri) {
        resolver.takePersistableUriPermission(
            tree,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        settings.setBackupFolder(tree.toString())
    }

    /** The folder's display name, or null if none is set or it can no longer be read. */
    suspend fun folderName(uriText: String?): String? = withContext(Dispatchers.IO) {
        val tree = uriText?.let(Uri::parse) ?: return@withContext null
        runCatching {
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
    }

    /** Writes a backup zip now, prunes old ones and records the time. */
    suspend fun backUpNow(): BackupOutcome {
        val s = settings.settings.first()
        val uriText = s.backupFolderUri ?: return BackupOutcome.NoFolder
        val tree = Uri.parse(uriText)
        val started = now()
        return withContext(Dispatchers.IO) {
            runCatching {
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val name = BackupSchedule.fileName(LocalDateTime.now())
                val doc = DocumentsContract.createDocument(resolver, parent, "application/zip", name)
                    ?: throw MsgFailure(msg(MsgKey.BACKUP_FOLDER_REFUSED))
                try {
                    resolver.openOutputStream(doc, "wt")?.use { backup.export(it, includeImages = s.backupImages) }
                        ?: throw MsgFailure(msg(MsgKey.BACKUP_WRITE_FAILED))
                } catch (e: Exception) {
                    // A half-written zip would otherwise count as the newest backup when pruning.
                    runCatching { DocumentsContract.deleteDocument(resolver, doc) }
                    throw e
                }
                prune(tree)
                name
            }.fold(
                onSuccess = { name ->
                    settings.setLastBackupAt(started)
                    BackupOutcome.Written(name)
                },
                onFailure = { BackupOutcome.Failed((it as? MsgFailure)?.shown ?: msg(MsgKey.BACKUP_FAILED, it.message ?: it.javaClass.simpleName)) },
            )
        }
    }

    private fun prune(tree: Uri) {
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val byName = mutableMapOf<String, String>()
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) byName[c.getString(1)] = c.getString(0)
        }
        BackupSchedule.filesToPrune(byName.keys.toList()).forEach { name ->
            byName[name]?.let { id -> DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
        }
    }
}
