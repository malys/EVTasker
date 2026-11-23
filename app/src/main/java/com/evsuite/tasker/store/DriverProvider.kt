package com.evsuite.tasker.store

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.evsuite.hardware.DriverContract

/**
 * Read-only view of [DriverStore] for the suite's other apps (CP-098).
 *
 * Exported, but guarded in the manifest by [DriverContract.PERMISSION_READ], a `signature`
 * permission: only an app signed with the suite's key can query it. One row while a driver is
 * set this cycle, an empty cursor otherwise. Every write is refused — the driver is decided
 * by EVTasker's rules alone.
 */
class DriverProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        require(uri.toString() == DriverContract.URI_CURRENT) { "unknown uri" }
        val cursor = MatrixCursor(arrayOf(DriverContract.COLUMN_NAME, DriverContract.COLUMN_SET_AT_MS))
        val driver = context?.let { DriverStore.current(it) }
        if (driver != null) cursor.addRow(arrayOf<Any>(driver.name, driver.setAtMs))
        return cursor
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}
