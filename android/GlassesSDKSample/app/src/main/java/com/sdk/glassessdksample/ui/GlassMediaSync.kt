package com.sdk.glassessdksample.ui

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.oudmon.ble.base.communication.file.FileHandle
import com.oudmon.ble.base.communication.file.SimpleCallback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GlassMediaSync(private val context: Context) {
    private val client = OkHttpClient()

    suspend fun fetchJpgNames(baseIp: String): Set<String> = withContext(Dispatchers.IO) {
        val body = getText("http://$baseIp/files/media.config")
        parseJpgNames(body)
    }

    suspend fun saveAndDelete(baseIp: String, fileName: String): SyncResult = withContext(Dispatchers.IO) {
        val galleryUri = save(baseIp, fileName)
        val deleteOk = delete(fileName)
        if (deleteOk) {
            SyncResult.SavedAndDeleted(fileName, galleryUri)
        } else {
            SyncResult.SavedDeleteFailed(fileName, galleryUri)
        }
    }

    suspend fun save(baseIp: String, fileName: String): Uri = withContext(Dispatchers.IO) {
        val encodedName = fileName.encodePath()
        val fileUrl = "http://$baseIp/files/$encodedName"
        val tempFile = File(context.cacheDir, "heycyan_${fileName.substringAfterLast('/')}").apply {
            parentFile?.mkdirs()
        }

        try {
            downloadToFile(fileUrl, tempFile)
            saveToGallery(tempFile, galleryDisplayName(fileName))
        } finally {
            tempFile.delete()
        }
    }

    suspend fun delete(fileName: String): Boolean = deleteRemote(fileName)

    private fun getText(url: String): String {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GET $url failed: ${response.code}")
            }
            return response.body?.string().orEmpty()
        }
    }

    private fun parseJpgNames(body: String): Set<String> {
        val names = linkedSetOf<String>()
        val regex = Regex("""[A-Za-z0-9_./\\-]+\.jpe?g""", RegexOption.IGNORE_CASE)
        body.lineSequence().forEach { line ->
            regex.findAll(line).forEach { match ->
                names.add(match.value.replace('\\', '/').trimStart('/'))
            }
        }
        return names
    }

    private fun downloadToFile(url: String, outFile: File) {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GET $url failed: ${response.code}")
            }
            val body = response.body ?: throw IOException("GET $url returned no body")
            body.byteStream().use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        if (outFile.length() <= 0L) {
            throw IOException("Downloaded file is empty: $url")
        }
    }

    private fun saveToGallery(source: File, displayName: String): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToGalleryModern(source, displayName)
        } else {
            saveToGalleryLegacy(source, displayName)
        }
    }

    private fun galleryDisplayName(remoteFileName: String): String {
        val baseName = remoteFileName.substringAfterLast('/').substringBeforeLast('.')
        val timestamp = Regex("""(\d{8})(\d{6})""").find(baseName)?.let { match ->
            "${match.groupValues[1]}T${match.groupValues[2]}"
        } ?: SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).format(Date())
        return "$timestamp.jpg"
    }

    private fun saveToGalleryModern(source: File, displayName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HeyCyan")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create MediaStore row for $displayName")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("Failed to open MediaStore output stream for $displayName")

            val completeValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, completeValues, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun saveToGalleryLegacy(source: File, displayName: String): Uri {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "HeyCyan"
        ).apply { mkdirs() }
        val destination = uniqueFile(dir, displayName)
        source.copyTo(destination, overwrite = true)
        MediaScannerConnection.scanFile(
            context,
            arrayOf(destination.absolutePath),
            arrayOf("image/jpeg"),
            null
        )
        return Uri.fromFile(destination)
    }

    private fun uniqueFile(dir: File, displayName: String): File {
        val baseName = displayName.substringBeforeLast('.', displayName)
        val extension = displayName.substringAfterLast('.', "")
        var candidate = File(dir, displayName)
        var index = 1
        while (candidate.exists()) {
            val suffix = if (extension.isBlank()) "" else ".$extension"
            candidate = File(dir, "${baseName}_$index$suffix")
            index++
        }
        return candidate
    }

    private suspend fun deleteRemote(fileName: String): Boolean {
        val result = CompletableDeferred<Boolean>()
        val callback = object : SimpleCallback() {
            override fun onDeletePlate() {
                Log.i(TAG, "BLE delete succeeded: $fileName")
                result.complete(true)
            }

            override fun onDeletePlateError(code: Int) {
                Log.w(TAG, "BLE delete failed: $fileName code=$code")
                result.complete(false)
            }
        }

        val handle = FileHandle.getInstance()
        return try {
            handle.initRegister()
            handle.registerCallback(callback)
            handle.executeFileDelete(fileName.substringAfterLast('/'))
            withTimeoutOrNull(15000L) { result.await() } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "BLE delete threw: $fileName", e)
            false
        } finally {
            runCatching { handle.removeCallback(callback) }
        }
    }

    private fun String.encodePath(): String {
        return replace('\\', '/')
            .split('/')
            .filter { it.isNotBlank() }
            .joinToString("/") { segment ->
                URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }
    }

    sealed class SyncResult {
        data class SavedAndDeleted(val fileName: String, val uri: Uri) : SyncResult()
        data class SavedDeleteFailed(val fileName: String, val uri: Uri) : SyncResult()
    }

    companion object {
        private const val TAG = "GlassMediaSync"
    }
}
