package com.zhenxiang.superimage.work

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.*

private typealias InputAndProgress = Pair<RealESRGANWorker.InputData, RealESRGANWorker.Progress>

class RealESRGANWorkerManager(private val context: Context) {

    private val workManager = WorkManager.getInstance(context)
    private val currentWorkerLiveData = MutableLiveData<Pair<UUID, RealESRGANWorker.InputData>?>()
    private val workInfosLiveData = MutableLiveData<List<WorkInfo>>()

    val workProgressFlow: StateFlow<InputAndProgress?>

    init {
        val processLifecycle = ProcessLifecycleOwner.get()
        processLifecycle.lifecycleScope.launch(Dispatchers.IO) {
            getTempDir(context).deleteRecursively()
        }

        workManager.getWorkInfosForUniqueWorkLiveData(UNIQUE_WORK_ID).observeForever {
            workInfosLiveData.value = it
        }
        workProgressFlow = MutableStateFlow<InputAndProgress?>(null).apply {
            currentWorkerLiveData.observeForever { currentWorker ->
                tryEmit(workInfosLiveData.value?.let { infos -> processWorkInfo(infos, currentWorker) })
            }
            workInfosLiveData.observeForever { infos ->
                tryEmit(currentWorkerLiveData.value?.let { currentWorker -> processWorkInfo(infos, currentWorker) })
            }
        }
    }

    fun createTempImageFile(): File {
        val tempDir = getTempDir(context)
        if (!tempDir.isDirectory) {
            tempDir.delete()
            tempDir.mkdir()
        }
        return File(tempDir, SystemClock.elapsedRealtime().toString()).apply {
            if (exists()) {
                delete()
            }
            createNewFile()
        }
    }

    fun beginWork(input: RealESRGANWorker.InputData) {
        // TODO: Check if worker is already running
        val request = OneTimeWorkRequestBuilder<RealESRGANWorker>().setInputData(input.toWorkData()).build()
        currentWorkerLiveData.value = Pair(request.id, input)
        workManager.enqueueUniqueWork(UNIQUE_WORK_ID, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Delete a temp image file
     * @return whether the file isn't being used by [RealESRGANWorker]
     */
    fun deleteTempImageFile(file: File): Boolean {
        val isFileUsedInWork = workProgressFlow.value?.let {
            it.second is RealESRGANWorker.Progress.Running && it.first.tempFileName == file.name
        } ?: false
        file.delete()
        return isFileUsedInWork
    }

    companion object {
        const val UNIQUE_WORK_ID = "real_esrgan"

        private fun getTempDir(context: Context) = File(context.noBackupFilesDir, "temp_images")

        private fun processWorkInfo(workInfos: List<WorkInfo>, currentWorker: Pair<UUID, RealESRGANWorker.InputData>?): InputAndProgress? {
            val workInfo = workInfos.firstOrNull()
            return if (workInfo != null && currentWorker != null && workInfo.id == currentWorker.first) {
                RealESRGANWorker.Progress.fromWorkInfo(workInfo)?.let {
                    Pair(currentWorker.second, it)
                }
            } else {
                null
            }
        }

        fun getTempFile(context: Context, fileName: String): File {
            return File(getTempDir(context), fileName)
        }
    }
}