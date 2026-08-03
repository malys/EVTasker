package com.mg4.tasker.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/** Read-only view of the phone book downloaded by the vehicle's PBAP client. */
object ContactDirectory {
    data class Entry(val number: String, val name: String, val label: String)

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun entries(context: Context): List<Entry> {
        if (!hasPermission(context)) return emptyList()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL
        )
        return try {
            val result = mutableListOf<Entry>()
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null
            )?.use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberColumn = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeColumn = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
                val labelColumn = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LABEL)
                while (cursor.moveToNext()) {
                    val number = normalize(cursor.getString(numberColumn) ?: "")
                    if (number.isBlank()) continue
                    val name = cursor.getString(nameColumn)?.trim().orEmpty().ifBlank { number }
                    val customLabel = cursor.getString(labelColumn)
                    val type = cursor.getInt(typeColumn)
                    val typeLabel = ContactsContract.CommonDataKinds.Phone
                        .getTypeLabel(context.resources, type, customLabel).toString()
                    result += Entry(number, name, "$name — $typeLabel — $number")
                }
            }
            result.distinctBy { it.number }.sortedBy { it.name.lowercase() }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    fun normalize(value: String): String =
        value.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
}
