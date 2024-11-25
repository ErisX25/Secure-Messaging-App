/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.registrationv3.ui.restore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.backup.v2.BackupRepository
import org.thoughtcrime.securesms.backup.v2.MessageBackupTier
import org.thoughtcrime.securesms.backup.v2.RestoreV2Event
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.JobTracker
import org.thoughtcrime.securesms.jobs.BackupRestoreJob
import org.thoughtcrime.securesms.jobs.BackupRestoreMediaJob
import org.thoughtcrime.securesms.jobs.ProfileUploadJob
import org.thoughtcrime.securesms.jobs.SyncArchivedMediaJob
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.registration.util.RegistrationUtil
import org.thoughtcrime.securesms.registrationv3.data.RegistrationRepository

class RemoteRestoreViewModel : ViewModel() {

  companion object {
    private val TAG = Log.tag(RemoteRestoreViewModel::class)
  }

  private val store: MutableStateFlow<ScreenState> = MutableStateFlow(
    ScreenState(
      backupTier = SignalStore.backup.backupTier,
      backupTime = SignalStore.backup.lastBackupTime
    )
  )

  val state: StateFlow<ScreenState> = store.asStateFlow()

  init {
    viewModelScope.launch(Dispatchers.IO) {
      val restored = BackupRepository.restoreBackupTier(SignalStore.account.requireAci()) != null
      store.update {
        if (restored) {
          it.copy(
            loadState = ScreenState.LoadState.LOADED,
            backupTier = SignalStore.backup.backupTier,
            backupTime = SignalStore.backup.lastBackupTime
          )
        } else {
          it.copy(
            loadState = ScreenState.LoadState.FAILURE
          )
        }
      }
    }
  }

  fun restore() {
    viewModelScope.launch {
      store.update { it.copy(importState = ImportState.InProgress) }

      withContext(Dispatchers.IO) {
        val jobStateFlow = callbackFlow {
          val listener = JobTracker.JobListener { _, jobState ->
            trySend(jobState)
          }

          AppDependencies
            .jobManager
            .startChain(BackupRestoreJob())
            .then(SyncArchivedMediaJob())
            .then(BackupRestoreMediaJob())
            .enqueue(listener)

          awaitClose {
            AppDependencies.jobManager.removeListener(listener)
          }
        }

        jobStateFlow.collect { state ->
          when (state) {
            JobTracker.JobState.SUCCESS -> {
              Log.i(TAG, "Restore successful")
              SignalStore.registration.markRestoreCompleted()

              if (!RegistrationRepository.isMissingProfileData()) {
                RegistrationUtil.maybeMarkRegistrationComplete()
                AppDependencies.jobManager.add(ProfileUploadJob())
              }

              store.update { it.copy(importState = ImportState.Restored(RegistrationRepository.isMissingProfileData())) }
            }

            JobTracker.JobState.PENDING,
            JobTracker.JobState.RUNNING -> {
              Log.i(TAG, "Restore job states updated: $state")
            }

            JobTracker.JobState.FAILURE,
            JobTracker.JobState.IGNORED -> {
              Log.w(TAG, "Restore failed with $state")

              store.update { it.copy(importState = ImportState.Failed) }
            }
          }
        }
      }
    }
  }

  fun updateRestoreProgress(restoreEvent: RestoreV2Event) {
    store.update { it.copy(restoreProgress = restoreEvent) }
  }

  fun cancel() {
    SignalStore.registration.markSkippedTransferOrRestore()
  }

  fun clearError() {
    store.update { it.copy(importState = ImportState.None, restoreProgress = null) }
  }

  data class ScreenState(
    val backupTier: MessageBackupTier? = null,
    val backupTime: Long = -1,
    val importState: ImportState = ImportState.None,
    val restoreProgress: RestoreV2Event? = null,
    val loadState: LoadState = if (backupTier != null) LoadState.LOADED else LoadState.LOADING
  ) {

    fun isLoaded(): Boolean {
      return loadState == LoadState.LOADED
    }

    fun isLoading(): Boolean {
      return loadState == LoadState.LOADING
    }

    enum class LoadState {
      LOADING, LOADED, FAILURE
    }
  }

  sealed interface ImportState {
    data object None : ImportState
    data object InProgress : ImportState
    data class Restored(val missingProfileData: Boolean) : ImportState
    data object Failed : ImportState
  }
}