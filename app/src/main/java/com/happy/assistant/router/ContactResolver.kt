package com.happy.assistant.router

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.happy.assistant.core.HappyLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spoken name to phone number.
 *
 * Returns every plausible person rather than one, because the spec requires
 * Happy to ask "do you mean Rohit Sharma or Rohit Verma" instead of guessing and
 * calling the wrong one.
 */
@Singleton
class ContactResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: HappyLog,
) {

    data class Contact(val name: String, val number: String)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** Best matches first. Empty when nothing was close enough. */
    fun resolve(spokenName: String): List<Contact> {
        if (!hasPermission()) {
            log.w(TAG, "contacts permission not granted")
            return emptyList()
        }
        val all = load()
        val scored = all.mapNotNull { contact ->
            val whole = Fuzzy.score(spokenName, contact.name)
            // Also try the first name on its own, so "call rohit" finds Rohit Sharma.
            val first = contact.name.substringBefore(' ')
            val partial = if (first != contact.name) Fuzzy.score(spokenName, first) else null
            val best = listOfNotNull(whole, partial).minOrNull()
            best?.let { it to contact }
        }
        if (scored.isEmpty()) {
            log.d(TAG, "no contact matched \"$spokenName\"")
            return emptyList()
        }
        val bestScore = scored.minOf { it.first }
        // Everything equally good is genuinely ambiguous and the caller must ask.
        return scored.filter { it.first == bestScore }
            .map { it.second }
            .distinctBy { it.number.filter(Char::isDigit).takeLast(NUMBER_TAIL) }
    }

    /** Every contact with a number. Used for reverse lookup from the call log. */
    fun all(): List<Contact> = if (hasPermission()) load() else emptyList()

    private fun load(): List<Contact> {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val out = mutableListOf<Contact>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                null,
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(projection[0])
                val numberIdx = cursor.getColumnIndex(projection[1])
                if (nameIdx < 0 || numberIdx < 0) return emptyList()
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx)?.trim().orEmpty()
                    val number = cursor.getString(numberIdx)?.trim().orEmpty()
                    if (name.isNotEmpty() && number.isNotEmpty()) out += Contact(name, number)
                }
            }
        } catch (t: Throwable) {
            log.e(TAG, "could not read contacts", t)
            return emptyList()
        }
        return out
    }

    /**
     * Reads a number as digits, grouped.
     *
     * Digits, because "nine eight seven" is a phone number and "nine hundred and
     * eighty seven million" is not. Grouped, because ten digits read as one
     * unbroken run is impossible to write down - the commas become pauses.
     */
    fun spoken(number: String): String {
        val raw = number.filter(Char::isDigit)
        // Only strip the country code when what remains is a full local number.
        // A local number may itself begin 91, and dropping that loses two digits.
        val digits = if (raw.length == 12 && raw.startsWith(COUNTRY_CODE)) raw.drop(2) else raw
        if (digits.isEmpty()) return number
        val groups = if (digits.length == 10) {
            listOf(digits.substring(0, 5), digits.substring(5))
        } else {
            digits.chunked(3)
        }
        return groups.joinToString(", ") { group -> group.toCharArray().joinToString(" ") }
    }

    companion object {
        private const val TAG = "Contacts"
        private const val NUMBER_TAIL = 10
        private const val COUNTRY_CODE = "91"
    }
}
