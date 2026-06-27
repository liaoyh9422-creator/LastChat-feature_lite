package me.rerere.rikkahub.data.backup

import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.datastore.BackupItem
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.sync.LocalBackupSync

class BackupCoordinator(
    private val settingsStore: SettingsStore,
    private val localBackupSync: LocalBackupSync,
    private val backupTaskMutex: BackupTaskMutex,
) {
    suspend fun exportToFile() = backupTaskMutex.mutex.withLock {
        val settingsSnapshot = settingsStore.settingsFlow.value
        if (settingsSnapshot.init) throw IllegalStateException("Settings not ready")
        localBackupSync.prepareBackupFile(listOf(BackupItem.DATABASE, BackupItem.FILES))
    }

    suspend fun restoreFromLocalFile(file: java.io.File): LocalBackupSync.RestoreResult = backupTaskMutex.mutex.withLock {
        val settingsSnapshot = settingsStore.settingsFlow.value
        if (settingsSnapshot.init) throw IllegalStateException("Settings not ready")
        localBackupSync.restoreFromLocalFile(
            file = file,
            items = listOf(BackupItem.DATABASE, BackupItem.FILES),
        )
    }
}