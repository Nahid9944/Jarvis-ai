package com.example.tools

import android.content.Context
import com.example.permissions.JarvisPermissionManager
import com.example.permissions.PermissionSpec
import org.json.JSONObject

object ToolRouter {

    suspend fun routeAndExecute(
        toolName: String,
        args: JSONObject,
        context: Context?,
        caller: String = "voice",
        executorDelegate: suspend (String, JSONObject) -> JSONObject
    ): JSONObject {
        return ToolExecutionPipeline.execute(
            toolName = toolName,
            args = args,
            context = context,
            caller = caller,
            executor = executorDelegate
        )
    }

    fun canExecute(toolName: String, context: Context): Pair<Boolean, String?> {
        val missing = getMissingPermissions(toolName, context)
        return if (missing.isEmpty()) {
            Pair(true, null)
        } else {
            Pair(false, "অনুমতি প্রয়োজন: ${missing.first().titleBn}")
        }
    }

    fun getMissingPermissions(toolName: String, context: Context): List<PermissionSpec> {
        val spec = ToolRegistry.get(toolName)
        return JarvisPermissionManager.getMissingPermissions(context, toolName, spec)
    }
}
