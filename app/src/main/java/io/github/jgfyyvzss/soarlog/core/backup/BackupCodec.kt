package io.github.jgfyyvzss.soarlog.core.backup

import io.github.jgfyyvzss.soarlog.core.Msg
import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.msg
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface DecodeResult {
    data class Ok(val file: BackupFile) : DecodeResult
    data class Refused(val message: Msg) : DecodeResult
}

/** Reads and writes the backup file with stable output, so an export, import and export round trip is identical. */
object BackupCodec {
    @OptIn(ExperimentalSerializationApi::class)
    private val writer = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        explicitNulls = true
    }

    private val reader = Json { ignoreUnknownKeys = true }

    /**
     * Upgrades from each older zip format version to the next, applied in order; the .json versions before 3 are
     * refused with the way back through an older app. Version 4 only adds fields that default to empty (no airfield
     * codes), so 3 to 4 only sets the version.
     */
    private val upgrades: Map<Int, (JsonObject) -> JsonObject> = mapOf(
        3 to { json -> JsonObject(json + ("format_version" to JsonPrimitive(4))) },
    )

    /** Sorts every array by id (opening balances by role, club links by id), as the stable output requires. */
    fun sorted(file: BackupFile): BackupFile = file.copy(
        launchTypes = file.launchTypes.sortedBy { it.id },
        clubs = file.clubs.sortedBy { it.id },
        airfields = file.airfields.map { it.copy(clubIds = it.clubIds.sorted()) }.sortedBy { it.id },
        gliders = file.gliders.sortedBy { it.id },
        flights = file.flights.sortedBy { it.id },
        witnessSignoffs = file.witnessSignoffs.sortedBy { it.id },
        endorsements = file.endorsements.sortedBy { it.id },
        openingBalances = file.openingBalances.sortedBy { it.role },
        importBatches = file.importBatches.sortedBy { it.id },
    )

    fun encode(file: BackupFile): String = writer.encodeToString(BackupFile.serializer(), sorted(file)) + "\n"

    fun decode(text: String): DecodeResult {
        val root = runCatching { reader.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return DecodeResult.Refused(msg(MsgKey.BACKUP_NOT_JSON))
        val format = runCatching { root["format"]?.jsonPrimitive?.content }.getOrNull()
        if (format != BACKUP_FORMAT) return DecodeResult.Refused(msg(MsgKey.BACKUP_NOT_BACKUP))
        val version = runCatching { root["format_version"]?.jsonPrimitive?.intOrNull }.getOrNull()
            ?: return DecodeResult.Refused(msg(MsgKey.BACKUP_NO_VERSION))
        if (version > BACKUP_FORMAT_VERSION) return DecodeResult.Refused(msg(MsgKey.BACKUP_NEWER, version))
        if (version < 1) return DecodeResult.Refused(msg(MsgKey.BACKUP_UNKNOWN_VERSION, version))
        if (version < FIRST_ZIP_FORMAT_VERSION) return DecodeResult.Refused(msg(MsgKey.BACKUP_OLD_FORMAT, LAST_JSON_BACKUP_APP))

        var json = root
        for (v in version until BACKUP_FORMAT_VERSION) {
            val step = upgrades[v] ?: return DecodeResult.Refused(msg(MsgKey.BACKUP_NO_UPGRADE, v))
            json = step(json)
        }
        return runCatching { DecodeResult.Ok(reader.decodeFromJsonElement(BackupFile.serializer(), json)) }
            .getOrElse { DecodeResult.Refused(msg(MsgKey.BACKUP_DAMAGED, it.message?.take(200) ?: "")) }
    }

    /** The file with its export time cleared, for comparing two exports. */
    fun withoutExportTime(text: String): String {
        val root = reader.parseToJsonElement(text).jsonObject
        return JsonObject(root + ("exported_at" to reader.parseToJsonElement("\"\""))).toString()
    }
}
