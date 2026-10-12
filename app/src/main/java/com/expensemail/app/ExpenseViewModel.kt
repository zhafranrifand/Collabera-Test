package com.expensemail.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

internal data class ExpenseDataState(val snapshot: SyncSnapshot, val warning: String? = null)

internal data class BackupUiState(
    val message: String = "",
    val accountEmail: String? = null,
    val busy: Boolean = false,
    val pendingBackup: ExpenseBackup? = null,
    val preview: BackupMergePreview? = null,
)

/** Owns screen-facing expense state and backup operations across configuration changes. */
class ExpenseViewModel(application: Application) : AndroidViewModel(application) {
    internal val repository = ExpenseRepository(application)

    private val _data = MutableStateFlow(loadData())
    internal val data = _data.asStateFlow()

    private val _backup = MutableStateFlow(BackupUiState(accountEmail = FirebaseAuth.getInstance().currentUser?.email))
    internal val backup = _backup.asStateFlow()

    private val _spendingTarget = MutableStateFlow(repository.loadSpendingTarget())
    internal val spendingTarget = _spendingTarget.asStateFlow()

    fun saveSpendingTarget(amount: Long, period: String) {
        val target = SpendingTarget(amount.coerceAtLeast(0L), if (period == "Weekly") "Weekly" else "Monthly")
        repository.saveSpendingTarget(target)
        _spendingTarget.value = target
    }

    internal fun snapshot(): SyncSnapshot = _data.value.snapshot

    fun refreshFromDisk() {
        _data.value = loadData()
    }

    internal fun commitSnapshot(snapshot: SyncSnapshot) {
        repository.commitSnapshot(snapshot)
        _data.value = ExpenseDataState(snapshot)
    }

    fun saveExpenses(expenses: List<Expense>) {
        repository.saveExpenses(expenses)
        _data.value = _data.value.copy(snapshot = _data.value.snapshot.copy(expenses = expenses), warning = null)
    }

    fun exportBackupJson(): String = repository.encodeBackup(snapshot())

    fun stageBackup(raw: String) {
        runCatching { repository.decodeBackup(raw) }
            .onSuccess { stageBackup(it, "") }
            .onFailure { setBackupMessage("Could not read backup: ${it.message ?: "invalid file"}") }
    }

    fun stageImportedBackup(imported: ExpenseBackup) = stageBackup(imported, "")

    fun beginCloudSignIn() = updateBackup(busy = true, message = "Connecting cloud backup…")

    fun failCloudSignIn(message: String) = updateBackup(busy = false, message = message)

    private fun stageBackup(imported: ExpenseBackup, message: String) {
        val preview = ExpenseBackupCodec.merge(
            ExpenseBackup(snapshot().expenses, snapshot().ledger),
            imported,
        ).preview
        _backup.value = _backup.value.copy(message = message, pendingBackup = imported, preview = preview)
    }

    fun cancelRestore() {
        _backup.value = _backup.value.copy(pendingBackup = null, preview = null)
    }

    fun applyRestore(syncRunning: Boolean) {
        if (syncRunning) {
            setBackupMessage("Wait for Gmail sync to finish before restoring a backup.")
            return
        }
        val imported = _backup.value.pendingBackup ?: return
        runCatching {
            val merged = ExpenseBackupCodec.merge(ExpenseBackup(snapshot().expenses, snapshot().ledger), imported).backup
            commitSnapshot(SyncSnapshot(merged.transactions, merged.syncLedger))
            cancelRestore()
            setBackupMessage("Restore complete. Existing transactions and dismissals were preserved.")
        }.onFailure { setBackupMessage("Could not restore backup: ${it.message ?: "unknown error"}") }
    }

    fun setBackupMessage(message: String) {
        _backup.value = _backup.value.copy(message = message)
    }

    fun signInToCloudBackup(accessToken: String) {
        updateBackup(busy = true, message = "Connecting cloud backup…")
        viewModelScope.launch {
            try {
                val result = FirebaseAuth.getInstance()
                    .signInWithCredential(GoogleAuthProvider.getCredential(null, accessToken)).await()
                val email = result.user?.email
                updateBackup(busy = false, accountEmail = email, message = if (email.isNullOrBlank())
                    "Google sign-in succeeded, but the account email was unavailable."
                else "Cloud backup connected as $email.")
            } catch (error: Exception) {
                val authCode = (error as? FirebaseAuthException)?.errorCode
                val message = when (authCode) {
                    "ERROR_OPERATION_NOT_ALLOWED" -> "Google sign-in is not enabled for this Firebase project. Ask a project owner to enable it."
                    "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" -> "This Google account is already linked another way. Ask the project owner to review Firebase Authentication settings."
                    else -> error.message ?: "Could not connect cloud backup. Try again."
                }
                updateBackup(busy = false, message = message)
            }
        }
    }

    fun disconnectCloudBackup() {
        FirebaseAuth.getInstance().signOut()
        updateBackup(accountEmail = null, message = "Cloud backup disconnected on this device. Saved cloud backups remain available.")
    }

    fun uploadCloudBackup(syncRunning: Boolean) {
        if (syncRunning || _backup.value.busy) return
        updateBackup(busy = true, message = "Encrypting and uploading backup…")
        viewModelScope.launch {
            try {
                val idToken = currentFirebaseIdToken()
                val current = snapshot()
                val backupJson = repository.encodeBackup(current)
                val receipt = withContext(Dispatchers.IO) {
                    val metadata = CloudBackupClient.fetchKey(idToken)
                    val envelope = CloudBackupEnvelopeCodec.encrypt(
                        backupJson.toByteArray(Charsets.UTF_8), metadata, java.time.Instant.now().toString(),
                    )
                    CloudBackupClient.upload(idToken, envelope)
                }
                updateBackup(busy = false, message = "Encrypted cloud backup saved ${receipt.uploadedAt}.")
            } catch (error: Exception) {
                updateBackup(busy = false, message = error.message ?: "Cloud backup could not be uploaded. Try again.")
            }
        }
    }

    fun restoreLatestCloudBackup(syncRunning: Boolean) {
        if (syncRunning || _backup.value.busy) return
        updateBackup(busy = true, message = "Finding your latest cloud backup…")
        viewModelScope.launch {
            try {
                val idToken = currentFirebaseIdToken()
                val imported = withContext(Dispatchers.IO) {
                    val latest = CloudBackupClient.list(idToken).firstOrNull()
                        ?: return@withContext null
                    val response = CloudBackupClient.restore(idToken, latest.id)
                    ExpenseBackupCodec.decode(
                        String(CloudBackupEnvelopeCodec.decrypt(response.envelope, response.contentKey), Charsets.UTF_8),
                    )
                }
                if (imported == null) updateBackup(busy = false, message = "No cloud backups found for this account yet.")
                else stageBackup(imported, "Latest cloud backup loaded. Review the merge before restoring.")
            } catch (error: Exception) {
                updateBackup(busy = false, message = error.message ?: "The cloud backup could not be restored. Try again.")
            } finally {
                if (_backup.value.busy) updateBackup(busy = false)
            }
        }
    }

    private suspend fun currentFirebaseIdToken(): String {
        val user = FirebaseAuth.getInstance().currentUser ?: error("Connect a Google account for cloud backup first.")
        return user.getIdToken(true).await().token ?: error("Firebase did not provide an ID token. Please reconnect cloud backup.")
    }

    private fun updateBackup(
        busy: Boolean? = null,
        accountEmail: String? = _backup.value.accountEmail,
        message: String = _backup.value.message,
    ) {
        _backup.value = _backup.value.copy(
            busy = busy ?: _backup.value.busy,
            accountEmail = accountEmail,
            message = message,
        )
    }

    private fun loadData() = ExpenseDataState(repository.loadSnapshot(), repository.loadWarning)
}
