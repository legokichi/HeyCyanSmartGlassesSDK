package com.sdk.glassessdksample.ui

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object HeyCyanLogger {
    private const val TAG = "HeyCyanCmd"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    fun info(context: Context, command: String, message: String) {
        write(context, "info", command, message, null)
    }

    fun warn(context: Context, command: String, message: String, throwable: Throwable? = null) {
        write(context, "warn", command, message, throwable)
    }

    fun error(context: Context, command: String, message: String, throwable: Throwable? = null) {
        write(context, "error", command, message, throwable)
    }

    private fun write(
        context: Context,
        level: String,
        command: String,
        message: String,
        throwable: Throwable?
    ) {
        val line = jsonLine(level, command, message, throwable)
        when (level) {
            "error" -> Log.e(TAG, line, throwable)
            "warn" -> Log.w(TAG, line, throwable)
            else -> Log.i(TAG, line)
        }
        runCatching {
            val dir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
            File(dir, "heycyan.log").appendText(line + "\n")
        }.onFailure {
            Log.w(TAG, "Failed to append file log", it)
        }
    }

    private fun jsonLine(
        level: String,
        command: String,
        message: String,
        throwable: Throwable?
    ): String {
        val fields = linkedMapOf(
            "ts" to dateFormat.format(Date()),
            "level" to level,
            "command" to command,
            "message" to message
        )
        throwable?.let {
            fields["error"] = it.javaClass.simpleName
            fields["errorMessage"] = it.message.orEmpty()
        }
        return fields.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"${key.escapeJson()}\":\"${value.escapeJson()}\""
        }
    }

    private fun String.escapeJson(): String {
        return buildString {
            this@escapeJson.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }
    }
}
