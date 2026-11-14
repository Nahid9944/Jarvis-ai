package com.example.permissions

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Universal Permission Coordinator & Command Resume Engine
 * 
 * Implements the lifecycle-aware permission coordination loop:
 * [Check Permissions -> Missing: Store Pending Command with UUID -> Launch Settings/Prompt ->
 *  onResume / Callback: Recheck All -> Exactly-Once Execution of Pending Command]
 */
object UniversalPermissionCoordinator {

    private const val TAG = "PermissionCoordinator"

    data class PendingCommand(
        val id: String = UUID.randomUUID().toString(),
        val toolName: String,
        val descriptionBn: String,
        val args: Map<String, Any?> = emptyMap(),
        val requiredPermissionIds: List<String>,
        val missingSpecs: List<PermissionSpec>,
        val createdAt: Long = System.currentTimeMillis(),
        val executeAction: suspend () -> Pair<Boolean, String>
    )

    sealed class CoordinationResult {
        object Ready : CoordinationResult()
        data class PermissionRequired(
            val pendingCommandId: String,
            val missingSpecs: List<PermissionSpec>,
            val messageBn: String
        ) : CoordinationResult()
    }

    private val _pendingCommand = MutableStateFlow<PendingCommand?>(null)
    val pendingCommand: StateFlow<PendingCommand?> = _pendingCommand.asStateFlow()

    private val _lastResumedResult = MutableStateFlow<Pair<String, String>?>(null)
    val lastResumedResult: StateFlow<Pair<String, String>?> = _lastResumedResult.asStateFlow()

    private val executionMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.Main)

    /**
     * Checks if the required permissions for a tool or command are granted.
     * If granted, immediately returns [CoordinationResult.Ready].
     * If missing, stores the command as pending and returns [CoordinationResult.PermissionRequired].
     */
    suspend fun verifyOrHold(
        context: Context,
        toolName: String,
        descriptionBn: String,
        args: Map<String, Any?> = emptyMap(),
        executeAction: suspend () -> Pair<Boolean, String>
    ): CoordinationResult = executionMutex.withLock {
        val registryPermId = com.example.tools.ToolRegistry.get(toolName)?.requiredPermissionId
        val requiredSpecs = PermissionRegistry.getResolvedForTool(toolName, registryPermId)
        val missingSpecs = requiredSpecs.filter { spec ->
            try {
                !spec.isGranted(context)
            } catch (e: Exception) {
                true
            }
        }

        if (missingSpecs.isEmpty()) {
            return CoordinationResult.Ready
        }

        val pending = PendingCommand(
            toolName = toolName,
            descriptionBn = descriptionBn,
            args = args,
            requiredPermissionIds = requiredSpecs.map { it.id },
            missingSpecs = missingSpecs,
            executeAction = executeAction
        )

        _pendingCommand.value = pending
        Log.d(TAG, "Command '${toolName}' held pending [UUID: ${pending.id}]. Missing permissions: ${missingSpecs.map { it.id }}")

        val missingTitles = missingSpecs.joinToString(", ") { it.titleBn }
        val promptMsg = "'$descriptionBn' সম্পন্ন করতে $missingTitles অনুমতি প্রয়োজন।"

        return CoordinationResult.PermissionRequired(
            pendingCommandId = pending.id,
            missingSpecs = missingSpecs,
            messageBn = promptMsg
        )
    }

    /**
     * Called during Activity onResume or after permission resolution.
     * Evaluates whether the currently held pending command has all requirements satisfied.
     * If satisfied, pops the command atomically and executes it exactly once.
     */
    fun onResumeRecheck(context: Context, onResult: ((Boolean, String) -> Unit)? = null) {
        scope.launch {
            val commandToExecute = executionMutex.withLock {
                val current = _pendingCommand.value ?: return@withLock null

                val stillMissing = current.requiredPermissionIds.mapNotNull { id ->
                    PermissionRegistry.get(id)
                }.filter { spec ->
                    try {
                        !spec.isGranted(context)
                    } catch (e: Exception) {
                        true
                    }
                }

                if (stillMissing.isEmpty()) {
                    // Atomically clear to prevent duplicate execution
                    _pendingCommand.value = null
                    current
                } else {
                    // Update remaining missing specs
                    _pendingCommand.value = current.copy(missingSpecs = stillMissing)
                    null
                }
            }

            if (commandToExecute != null) {
                Log.i(TAG, "Permissions granted! Resuming pending command: ${commandToExecute.toolName} [UUID: ${commandToExecute.id}]")
                try {
                    val (success, message) = commandToExecute.executeAction()
                    _lastResumedResult.value = Pair(commandToExecute.id, message)
                    onResult?.invoke(success, message)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed executing resumed command ${commandToExecute.toolName}", e)
                    val errorMsg = "কমান্ড সম্পন্ন করতে ব্যর্থ: ${e.localizedMessage ?: "অজ্ঞাত সমস্যা"}"
                    _lastResumedResult.value = Pair(commandToExecute.id, errorMsg)
                    onResult?.invoke(false, errorMsg)
                }
            }
        }
    }

    /**
     * Request the user to grant the first missing permission for the pending command.
     */
    fun promptNextMissingPermission(context: Context) {
        val current = _pendingCommand.value ?: return
        val nextMissing = current.missingSpecs.firstOrNull() ?: return
        PermissionOrchestrator.openPermissionSettings(context, nextMissing)
    }

    /**
     * Cancels any currently held pending command.
     */
    fun cancelPendingCommand() {
        _pendingCommand.value = null
    }

    fun clearResumedResult() {
        _lastResumedResult.value = null
    }
}
