package com.sdk.glassessdksample.ui

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
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
        runCatching {
            appendPublicDownloadLog(context, line)
        }.onFailure {
            Log.w(TAG, "Failed to append public file log", it)
        }
    }

    private fun appendPublicDownloadLog(context: Context, line: String) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val name = "heycyan_cmd_log.txt"
        val relativePath = Environment.DIRECTORY_DOWNLOADS
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
        val args = arrayOf(name, "$relativePath/")

        val existingUri = resolver.query(collection, projection, selection, args, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                collection.buildUpon().appendPath(id.toString()).build()
            } else {
                null
            }
        }

        val uri = existingUri ?: resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            }
        ) ?: return

        resolver.openOutputStream(uri, "wa")?.use { output ->
            output.write((line + "\n").toByteArray(Charsets.UTF_8))
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
