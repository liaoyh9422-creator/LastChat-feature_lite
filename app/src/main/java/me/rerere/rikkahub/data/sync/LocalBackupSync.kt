package me.rerere.rikkahub.data.sync

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.BackupItem

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.sanitize
import me.rerere.rikkahub.data.db.AppDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "DataSync"
private const val BACKUP_FILE_PREFIX = "LastChat_backup_"
private const val BACKUP_FILE_SUFFIX = ".zip"
private const val DATABASE_SNAPSHOT_PREFIX = "rikka_hub_snapshot_"
private val STALE_BACKUP_TEMP_MAX_AGE_MS = TimeUnit.HOURS.toMillis(24)
private val FILES_DIR_BACKUP_PATHS = listOf(
    "upload",
    "avatars",
    "images",
    "skills",
    "python/wheels",
    "custom_fonts",
    "chat_files",
    "custom_icons",
)

class LocalBackupSync(
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val context: Context,
    private val database: AppDatabase,
) {
    // Removed.

    suspend fun restoreFromLocalFile(file: File, items: List<BackupItem>): RestoreResult =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "restoreFromLocalFile: Starting restore from ${file.absolutePath}")

            if (!file.exists()) {
                throw Exception("Backup file does not exist")
            }

            if (!file.canRead()) {
                throw Exception("Cannot read backup file")
            }

            try {
                restoreFromBackupFile(file, items)
            } catch (e: Exception) {
                Log.e(TAG, "restoreFromLocalFile: Failed to restore from local file", e)
                throw Exception("Restore failed: ${e.message}")
            }
        }

    suspend fun prepareBackupFile(items: List<BackupItem>): File = withContext(Dispatchers.IO) {
        cleanupStaleBackupTempFilesNow()

        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
        val backupFile = File(
            context.cacheDir,
            "$BACKUP_FILE_PREFIX$timestamp$BACKUP_FILE_SUFFIX"
        )
        if (backupFile.exists()) {
            backupFile.delete()
        }

        ZipOutputStream(FileOutputStream(backupFile)).use { zipOut ->
            addVirtualFileToZip(
                zipOut = zipOut,
                name = "settings.json",
                content = json.encodeToString(settingsStore.settingsFlow.value)
            )

            if (items.contains(BackupItem.DATABASE)) {
                val snapshotFile = File(context.cacheDir, "rikka_hub_snapshot_$timestamp")
                if (snapshotFile.exists()) snapshotFile.delete()

                val snapshotOk = runCatching {
                    exportDatabaseSnapshot(snapshotFile)
                }.isSuccess && snapshotFile.exists()

                if (snapshotOk) {
                    addFileToZip(zipOut, snapshotFile, "rikka_hub.db")
                    snapshotFile.delete()
                } else {
                    if (snapshotFile.exists()) snapshotFile.delete()
                    val dbFile = context.getDatabasePath("rikka_hub")
                    if (dbFile.exists()) {
                        addFileToZip(zipOut, dbFile, "rikka_hub.db")
                    }

                    val walFile = File(dbFile.parentFile, "rikka_hub-wal")
                    if (walFile.exists()) {
                        addFileToZip(zipOut, walFile, "rikka_hub-wal")
                    }

                    val shmFile = File(dbFile.parentFile, "rikka_hub-shm")
                    if (shmFile.exists()) {
                        addFileToZip(zipOut, shmFile, "rikka_hub-shm")
                    }
                }
            }

            if (items.contains(BackupItem.FILES)) {
                FILES_DIR_BACKUP_PATHS.forEach { relativePath ->
                    val folder = File(context.filesDir, relativePath)
                    if (folder.exists() && folder.isDirectory) {
                        Log.i(
                            TAG,
                            "prepareBackupFile: Backing up $relativePath from ${folder.absolutePath}"
                        )
                        addDirectoryToZip(zipOut, folder, relativePath)
                    } else {
                        Log.i(
                            TAG,
                            "prepareBackupFile: $relativePath folder does not exist or is not a directory"
                        )
                    }
                }
            }
        }

        backupFile
    }

    suspend fun cleanupStaleBackupTempFiles(
        maxAgeMs: Long = STALE_BACKUP_TEMP_MAX_AGE_MS,
    ) = withContext(Dispatchers.IO) {
        cleanupStaleBackupTempFilesNow(maxAgeMs = maxAgeMs)
    }

    private fun cleanupStaleBackupTempFilesNow(
        maxAgeMs: Long = STALE_BACKUP_TEMP_MAX_AGE_MS,
    ) {
        val cutoff = System.currentTimeMillis() - maxAgeMs.coerceAtLeast(0L)
        context.cacheDir
            .listFiles()
            .orEmpty()
            .asSequence()
            .filter { entry ->
                entry.exists() &&
                    entry.lastModified() < cutoff &&
                    (entry.isBackupZipTempFile() || entry.name.startsWith(DATABASE_SNAPSHOT_PREFIX))
            }
            .forEach { entry ->
                runCatching {
                    if (entry.isDirectory) entry.deleteRecursively() else entry.delete()
                }.onSuccess { deleted ->
                    if (deleted) Log.i(TAG, "cleanupStaleBackupTempFiles: deleted ${entry.name}")
                }.onFailure { err ->
                    Log.w(TAG, "cleanupStaleBackupTempFiles: failed to delete ${entry.name}", err)
                }
            }
    }

    private fun exportDatabaseSnapshot(targetFile: File) {
        val path = targetFile.absolutePath.replace("'", "''")
        database.openHelper.writableDatabase.execSQL("VACUUM INTO '$path'")
    }

    data class RestoreResult(
        val sanitization: DatabaseSanitizer.SanitizationResult,
        val settingsCleanup: BackupCleanupResult
    )

    private suspend fun restoreFromBackupFile(backupFile: File, items: List<BackupItem>): RestoreResult =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "restoreFromBackupFile: Starting restore from ${backupFile.absolutePath}")
            Log.i(TAG, "restoreFromBackupFile: items = $items")
            Log.i(TAG, "restoreFromBackupFile: context.filesDir = ${context.filesDir.absolutePath}")

            var unsupportedZipEntriesBytes: Long = 0
            var settingsCleanupResult = BackupCleanupResult()
            val restoreTempDir = File(context.cacheDir, "restore_temp_${System.currentTimeMillis()}")
            if (!restoreTempDir.exists()) restoreTempDir.mkdirs()

            var sanitizationResult = DatabaseSanitizer.SanitizationResult()

            try {
                ZipInputStream(FileInputStream(backupFile)).use { zipIn ->
                    var entry: ZipEntry?
                    while (zipIn.nextEntry.also { entry = it } != null) {
                        entry?.let { zipEntry ->
                            if (zipEntry.isDirectory) {
                                zipIn.closeEntry()
                                return@let
                            }

                            when (zipEntry.name) {
                                "settings.json" -> {
                                    val settingsJson = zipIn.readBytes().toString(Charsets.UTF_8)
                                    try {
                                        val settings = json.decodeFromString<Settings>(settingsJson)
                                        val (cleanedSettings, cleanupResult) = settings.sanitize(context)
                                        settingsCleanupResult = cleanupResult
                                        settingsStore.update(cleanedSettings)
                                    } catch (e: Exception) {
                                        throw Exception("Failed to restore settings: ${e.message}")
                                    }
                                }

                                "rikka_hub.db", "rikka_hub-wal", "rikka_hub-shm" -> {
                                    if (items.contains(BackupItem.DATABASE)) {
                                        val tempFile = when (zipEntry.name) {
                                            "rikka_hub.db" -> File(restoreTempDir, "rikka_hub")
                                            else -> File(restoreTempDir, zipEntry.name)
                                        }
                                        FileOutputStream(tempFile).use { outputStream ->
                                            zipIn.copyTo(outputStream)
                                        }
                                    }
                                }

                                else -> {
                                    fun skipEntry(reason: String) {
                                        val size = zipEntry.size.coerceAtLeast(0)
                                        Log.i(TAG, "restoreFromBackupFile: Skipping $reason entry ${zipEntry.name} (${size} bytes)")
                                        unsupportedZipEntriesBytes += size
                                    }

                                    fun safeResolveTargetFile(baseDir: File, relativePath: String): File? {
                                        val normalized = relativePath.replace('\\', '/').trimStart('/')
                                        if (normalized.isBlank()) return null
                                        val targetFile = File(baseDir, normalized)
                                        val canonicalBase = runCatching { baseDir.canonicalFile }.getOrNull() ?: return null
                                        val canonicalTarget = runCatching { targetFile.canonicalFile }.getOrNull() ?: return null
                                        val basePath = canonicalBase.path.let { path ->
                                            if (path.endsWith(File.separator)) path else path + File.separator
                                        }
                                        return canonicalTarget.takeIf { it.path.startsWith(basePath) }
                                    }

                                    fun restoreToFilesDirSubfolder(subfolder: String, prefix: String) {
                                        val relativePath = zipEntry.name.removePrefix(prefix)
                                        if (relativePath.isBlank()) return

                                        val baseDir = File(context.filesDir, subfolder)
                                        if (!baseDir.exists()) {
                                            baseDir.mkdirs()
                                        }

                                        val targetFile = safeResolveTargetFile(baseDir, relativePath)
                                        if (targetFile == null) {
                                            skipEntry(reason = "unsafe")
                                            return
                                        }

                                        try {
                                            targetFile.parentFile?.mkdirs()
                                            FileOutputStream(targetFile).use { outputStream ->
                                                zipIn.copyTo(outputStream)
                                            }
                                        } catch (e: Exception) {
                                            throw Exception("Failed to restore file ${zipEntry.name}: ${e.message}")
                                        }
                                    }

                                    if (items.contains(BackupItem.FILES)) {
                                        val supportedPath = FILES_DIR_BACKUP_PATHS.firstOrNull { relativePath ->
                                            zipEntry.name.startsWith(filesDirBackupZipPrefix(relativePath))
                                        }
                                        if (supportedPath != null) {
                                            restoreToFilesDirSubfolder(
                                                subfolder = supportedPath,
                                                prefix = filesDirBackupZipPrefix(supportedPath)
                                            )
                                        } else {
                                            skipEntry(reason = "unsupported")
                                        }
                                    } else {
                                        skipEntry(reason = "unsupported")
                                    }
                                }
                            }

                            zipIn.closeEntry()
                        }
                    }
                }

                val tempDbFile = File(restoreTempDir, "rikka_hub")
                if (tempDbFile.exists()) {
                    try {
                        val (cleanDb, result) = DatabaseSanitizer.sanitize(context, tempDbFile)
                        sanitizationResult = result

                        val finalDbFile = context.getDatabasePath("rikka_hub")
                        if (finalDbFile.exists()) finalDbFile.delete()
                        cleanDb.copyTo(finalDbFile, overwrite = true)

                        val cleanWal = File(cleanDb.path + "-wal")
                        val cleanShm = File(cleanDb.path + "-shm")

                        if (cleanWal.exists()) {
                            cleanWal.copyTo(File(finalDbFile.path + "-wal"), overwrite = true)
                        } else {
                            File(finalDbFile.path + "-wal").delete()
                        }

                        if (cleanShm.exists()) {
                            cleanShm.copyTo(File(finalDbFile.path + "-shm"), overwrite = true)
                        } else {
                            File(finalDbFile.path + "-shm").delete()
                        }
                    } catch (e: Exception) {
                        throw Exception("Database sanitization failed: ${e.message}")
                    }
                }

                val totalCleanupResult = settingsCleanupResult.copy(
                    unsupportedZipEntriesBytes = unsupportedZipEntriesBytes
                )

                RestoreResult(
                    sanitization = sanitizationResult,
                    settingsCleanup = totalCleanupResult
                )
            } finally {
                restoreTempDir.deleteRecursively()
            }
        }
}

private fun addFileToZip(zipOut: ZipOutputStream, file: File, entryName: String) {
    FileInputStream(file).use { fis ->
        val zipEntry = ZipEntry(entryName)
        zipOut.putNextEntry(zipEntry)
        fis.copyTo(zipOut)
        zipOut.closeEntry()
    }
}

private fun addDirectoryToZip(zipOut: ZipOutputStream, dir: File, entryPrefix: String) {
    if (!dir.exists() || !dir.isDirectory) return
    val prefix = entryPrefix.trim('/')
    if (prefix.isBlank()) return

    dir.walkTopDown()
        .filter { it.isFile }
        .forEach { file ->
            val relPath = runCatching { file.relativeTo(dir).path.replace('\\', '/') }.getOrNull()
                ?: return@forEach
            if (relPath.isBlank()) return@forEach
            runCatching {
                addFileToZip(zipOut, file, "$prefix/$relPath")
            }.onFailure { err ->
                Log.w(TAG, "addDirectoryToZip: Skip $prefix/$relPath: ${err.message}")
            }
        }
}

private fun filesDirBackupZipPrefix(relativePath: String): String {
    return "${relativePath.trim('/')}/"
}

private fun addVirtualFileToZip(zipOut: ZipOutputStream, name: String, content: String) {
    val zipEntry = ZipEntry(name)
    zipOut.putNextEntry(zipEntry)
    zipOut.write(content.toByteArray())
    zipOut.closeEntry()
}

private fun File.isBackupZipTempFile(): Boolean {
    return name.startsWith(BACKUP_FILE_PREFIX) && name.endsWith(BACKUP_FILE_SUFFIX)
}

// Removed.