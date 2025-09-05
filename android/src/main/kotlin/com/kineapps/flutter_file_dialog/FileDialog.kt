// Copyright (c) 2020 KineApps. All rights reserved.
//
// This source code is licensed under the BSD-style license found in the
// LICENSE file in the root directory of this source tree.

package com.kineapps.flutter_file_dialog

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val LOG_TAG = "FileDialog"

private const val REQUEST_CODE_PICK_DIR = 19110
private const val REQUEST_CODE_PICK_FILE = 19111
private const val REQUEST_CODE_SAVE_FILE = 19112

private const val PREFS_NAME = "flutter_file_dialog_prefs"
private const val KEY_TREE_URI = "persisted_tree_uri"

// https://developer.android.com/guide/topics/providers/document-provider
// https://developer.android.com/reference/android/content/Intent.html#ACTION_CREATE_DOCUMENT
// https://android.googlesource.com/platform/development/+/master/samples/ApiDemos/src/com/example/android/apis/content/DocumentsSample.java
class FileDialog(
        private var activity: Activity?
) : PluginRegistry.ActivityResultListener {

    private var pendingResult: MethodChannel.Result? = null
    private var fileExtensionsFilter: Array<String>? = null
    private var copyPickedFileToCacheDir: Boolean = true

    // file to be saved
    private var sourceFile: File? = null
    private var isSourceFileTemp: Boolean = false

    fun setActivity(activity: Activity?) {
        this.activity = activity
    }

    // -------------------------
    // Persistence helpers
    // -------------------------
    private fun prefs(): android.content.SharedPreferences? {
        val a = activity ?: return null
        return a.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun getPersistedTreeUri(): Uri? {
        val a = activity ?: return null
        val s = prefs()?.getString(KEY_TREE_URI, null) ?: return null
        val uri = Uri.parse(s)

        // Make sure we still hold the persistable permission (user may revoke)
        val has = a.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        return if (has) uri else null
    }

    private fun persistTreeUri(uri: Uri, takeFlags: Int) {
        val a = activity ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                a.contentResolver.takePersistableUriPermission(
                    uri,
                    takeFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                )
            }
        } catch (se: SecurityException) {
            Log.w(LOG_TAG, "takePersistableUriPermission failed: ${se.message}")
        }
        prefs()?.edit()?.putString(KEY_TREE_URI, uri.toString())?.apply()
        Log.d(LOG_TAG, "Persisted tree URI: $uri")
    }

    fun pickDirectory(result: MethodChannel.Result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            finishWithError(
                    "minimum_target",
                    "pickDirectory() available only on Android 21 and above",
                    ""
            )
            return
        }

        if (activity == null) {
            finishWithError(
                "internal_error",
                "No activity is available",
                "")
            return
        }

        Log.d(LOG_TAG, "pickDirectory - IN")

        if (!setPendingResult(result)) {
            finishWithAlreadyActiveError(result)
            return
        }

        // If we already have a persisted folder, return it immediately (no UI)
        getPersistedTreeUri()?.let { persisted ->
            Log.d(LOG_TAG, "pickDirectory - returning persisted: $persisted")
            finishSuccessfully(persisted.toString())
            Log.d(LOG_TAG, "pickDirectory - OUT (persisted)")
            return
        }

        // Otherwise prompt once and ask for persistable permission
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        activity?.startActivityForResult(intent, REQUEST_CODE_PICK_DIR)

        Log.d(LOG_TAG, "pickDirectory - OUT")
    }

    fun isPickDirectorySupported(result: MethodChannel.Result) {
        result.success(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
    }

    fun pickFile(result: MethodChannel.Result,
                 fileExtensionsFilter: Array<String>?,
                 mimeTypesFilter: Array<String>?,
                 localOnly: Boolean,
                 copyFileToCacheDir: Boolean
    ) {
        Log.d(LOG_TAG, "pickFile - IN, fileExtensionsFilter=$fileExtensionsFilter, mimeTypesFilter=$mimeTypesFilter, localOnly=$localOnly, copyFileToCacheDir=$copyFileToCacheDir")

        if (activity == null) {
            finishWithError(
                "internal_error",
                "No activity is available",
                "")
            return
        }

        if (!setPendingResult(result)) {
            finishWithAlreadyActiveError(result)
            return
        }

        this.fileExtensionsFilter = fileExtensionsFilter
        this.copyPickedFileToCacheDir = copyFileToCacheDir

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            if (localOnly) {
                putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            }
            applyMimeTypesFilterToIntent(mimeTypesFilter, this)
        }

        activity?.startActivityForResult(intent, REQUEST_CODE_PICK_FILE)

        Log.d(LOG_TAG, "pickFile - OUT")
    }

    fun saveFile(result: MethodChannel.Result,
                 sourceFilePath: String?,
                 data: ByteArray?,
                 fileName: String?,
                 mimeTypesFilter: Array<String>?,
                 localOnly: Boolean
    ) {
        Log.d(LOG_TAG, "saveFile - IN, sourceFilePath=$sourceFilePath, " +
                "data=${data?.size} bytes, fileName=$fileName, " +
                "mimeTypesFilter=$mimeTypesFilter, localOnly=$localOnly")

        if (!setPendingResult(result)) {
            finishWithAlreadyActiveError(result)
            return
        }

        if (sourceFilePath != null) {
            isSourceFileTemp = false
            // get source file
            sourceFile = File(sourceFilePath)
            if (!sourceFile!!.exists()) {
                finishWithError(
                        "file_not_found",
                        "Source file is missing",
                        sourceFilePath)
                return
            }
        } else {
            // write data to a temporary file
            isSourceFileTemp = true
            sourceFile = File.createTempFile(fileName!!, "")
            sourceFile!!.writeBytes(data!!)
        }

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_TITLE, fileName ?: sourceFile!!.name)
            if (localOnly) {
                putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            }
            applyMimeTypesFilterToIntent(mimeTypesFilter, this)
            // recommend persistable flags here as well (provider may allow take later)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }

        if (activity == null) {
            finishWithError(
                "internal_error",
                "No activity is available",
                "")
            return
        }

        activity?.startActivityForResult(intent, REQUEST_CODE_SAVE_FILE)

        Log.d(LOG_TAG, "saveFile - OUT")
    }

    private fun applyMimeTypesFilterToIntent(mimeTypesFilter: Array<String>?, intent: Intent) {
        if (mimeTypesFilter != null) {
            if (mimeTypesFilter.size == 1) {
                intent.type = mimeTypesFilter.first()
            } else {
                intent.type = "*/*"
                intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypesFilter)
            }
        } else {
            intent.type = "*/*"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (activity == null) {
            finishWithError(
                "internal_error",
                "No activity is available",
                "")
            return true
        }

        when (requestCode) {
            REQUEST_CODE_PICK_DIR -> {
                if (resultCode == Activity.RESULT_OK && data?.data != null) {
                    val sourceFileUri = data.data!!
                    Log.d(LOG_TAG, "Picked directory: $sourceFileUri")

                    // Take & persist permission, then remember for future calls
                    val takeFlags = data.flags
                    persistTreeUri(sourceFileUri, takeFlags)

                    finishSuccessfully(sourceFileUri.toString())
                } else {
                    Log.d(LOG_TAG, "Cancelled")
                    finishSuccessfully(null)
                }
                return true
            }
            REQUEST_CODE_PICK_FILE -> {
                if (resultCode == Activity.RESULT_OK && data?.data != null) {
                    val sourceFileUri = data.data
                    Log.d(LOG_TAG, "Picked file: $sourceFileUri")
                    val destinationFileName = getFileNameFromPickedDocumentUri(sourceFileUri)
                    if (destinationFileName != null && validateFileExtension(destinationFileName)) {
                        if (copyPickedFileToCacheDir) {
                            copyFileToCacheDirOnBackground(
                                    context = activity!!,
                                    sourceFileUri = sourceFileUri!!,
                                    destinationFileName = destinationFileName)
                        } else {
                            finishSuccessfully(sourceFileUri!!.toString())
                        }
                    } else {
                        finishWithError(
                                "invalid_file_extension",
                                "Invalid file type was picked",
                                getFileExtension(destinationFileName))
                    }
                } else {
                    Log.d(LOG_TAG, "Cancelled")
                    finishSuccessfully(null)
                }
                return true
            }
            REQUEST_CODE_SAVE_FILE -> {
                if (resultCode == Activity.RESULT_OK && data?.data != null) {
                    val destinationFileUri = data.data!!
                    // Some providers also allow taking persistable permission on created file
                    val takeFlags = data.flags
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                            activity?.contentResolver?.takePersistableUriPermission(
                                destinationFileUri,
                                takeFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            )
                        }
                    } catch (se: SecurityException) {
                        Log.w(LOG_TAG, "takePersistableUriPermission (file) failed: ${se.message}")
                    }

                    saveFileOnBackground(this.sourceFile!!, destinationFileUri)
                } else {
                    Log.d(LOG_TAG, "Cancelled")
                    if (isSourceFileTemp) {
                        Log.d(LOG_TAG, "Deleting source file: ${sourceFile?.path}")
                        sourceFile?.delete()
                    }
                    finishSuccessfully(null)
                }
                return true
            }
            else -> return false
        }
    }

    private fun copyFileToCacheDirOnBackground(
            context: Context,
            sourceFileUri: Uri,
            destinationFileName: String) {
        val uiScope = CoroutineScope(Dispatchers.Main)
        uiScope.launch {
            try {
                Log.d(LOG_TAG, "Launch...")
                Log.d(LOG_TAG, "Copy on background...")
                val filePath = withContext(Dispatchers.IO) {
                    copyFileToCacheDir(context, sourceFileUri, destinationFileName)
                }
                Log.d(LOG_TAG, "...copied on background, result: $filePath")
                finishSuccessfully(filePath)
                Log.d(LOG_TAG, "...launch")
            } catch (e: Exception) {
                Log.e(LOG_TAG, "copyFileToCacheDirOnBackground failed", e)
                finishWithError("file_copy_failed", e.localizedMessage, e.toString())
            }
        }
    }

    private fun copyFileToCacheDir(
            context: Context,
            sourceFileUri: Uri,
            destinationFileName: String): String {
        // get destination file on cache dir
        val destinationFile = File(context.cacheDir.path, destinationFileName).apply {
            if (exists()) {
                Log.d(LOG_TAG, "Deleting existing destination file '$path'")
                delete()
            }
        }

        // copy file to cache dir
        Log.d(LOG_TAG, "Copying '$sourceFileUri' to '${destinationFile.path}'")
        var copiedBytes: Long
        context.contentResolver.openInputStream(sourceFileUri).use { inputStream ->
            destinationFile.outputStream().use { outputStream ->
                copiedBytes = inputStream!!.copyTo(outputStream)
            }
        }

        Log.d(LOG_TAG, "Successfully copied file to '${destinationFile.absolutePath}, bytes=$copiedBytes'")

        return destinationFile.absolutePath
    }

    private fun getFileNameFromPickedDocumentUri(uri: Uri?): String? {
        if (uri == null) {
            return null
        }
        var fileName: String? = null
        activity?.contentResolver?.query(uri, null, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                fileName = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            }
        }
        return cleanupFileName(fileName)
    }

    private fun cleanupFileName(fileName: String?): String? {
        // https://stackoverflow.com/questions/2679699/what-characters-allowed-in-file-names-on-android
        return fileName?.replace(Regex("[\\\\/:*?\"<>|\\[\\]]"), "_")
    }

    private fun getFileExtension(fileName: String?): String? {
        return fileName?.substringAfterLast('.', "")
    }

    private fun validateFileExtension(filePath: String): Boolean {
        val validFileExtensions = fileExtensionsFilter
        if (validFileExtensions.isNullOrEmpty()) {
            return true
        }
        val fileExtension = getFileExtension(filePath) ?: return false
        for (extension in validFileExtensions) {
            if (fileExtension.equals(extension, true)) {
                return true
            }
        }
        return false
    }

    private fun saveFileOnBackground(
            sourceFile: File,
            destinationFileUri: Uri
    ) {
        val uiScope = CoroutineScope(Dispatchers.Main)
        uiScope.launch {
            try {
                Log.d(LOG_TAG, "Saving file on background...")
                val filePath = withContext(Dispatchers.IO) {
                    saveFile(sourceFile, destinationFileUri)
                }
                Log.d(LOG_TAG, "...saved file on background, result: $filePath")
                finishSuccessfully(filePath)
            } catch (e: SecurityException) {
                Log.e(LOG_TAG, "saveFileOnBackground", e)
                finishWithError("security_exception", e.localizedMessage, e.toString())
            } catch (e: Exception) {
                Log.e(LOG_TAG, "saveFileOnBackground failed", e)
                finishWithError("save_file_failed", e.localizedMessage, e.toString())
            } finally {
                if (isSourceFileTemp) {
                    Log.d(LOG_TAG, "Deleting source file: ${sourceFile.path}")
                    sourceFile.delete()
                }
            }
        }
    }

    private fun saveFile(
            sourceFile: File,
            destinationFileUri: Uri
    ): String {
        Log.d(LOG_TAG, "Saving file '${sourceFile.path}' to '${destinationFileUri.path}'")
        sourceFile.inputStream().use { inputStream ->
            activity?.contentResolver?.openOutputStream(destinationFileUri).use { outputStream ->
                if (outputStream == null) {
                    throw IllegalStateException("openOutputStream returned null for $destinationFileUri")
                }
                // Do not force-cast; some providers don't return FileOutputStream.
                // Many providers create a fresh file; if you need truncation semantics,
                // open with "wt" mode via DocumentsContract.openDocument (not shown).
                inputStream.copyTo(outputStream)
                outputStream.flush()
            }
        }
        Log.d(LOG_TAG, "Saved file to '${destinationFileUri.path}'")
        return destinationFileUri.path!!
    }

    private fun setPendingResult(
        result: MethodChannel.Result
    ): Boolean {
        if (pendingResult != null) {
            return false
        }
        pendingResult = result
        return true
    }

    private fun clearPendingResult() {
        pendingResult = null
    }

    private fun finishWithAlreadyActiveError(result: MethodChannel.Result) {
        Log.w(LOG_TAG, "File dialog is already active")
    }

    private fun finishSuccessfully(filePath: String?) {
        pendingResult?.let { result ->
            clearPendingResult()
            result.success(filePath)
        }
    }

    private fun finishWithError(errorCode: String, errorMessage: String?, errorDetails: String?) {
        pendingResult?.let { result ->
            clearPendingResult()
            result.error(errorCode, errorMessage, errorDetails)
        }
    }
}
