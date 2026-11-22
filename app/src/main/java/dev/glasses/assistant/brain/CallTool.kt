package dev.glasses.assistant.brain

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * Phone calls by voice: find a contact by name and call them, or call a number.
 * Uses Android's own phone app for the call itself.
 */
class CallTool(private val context: Context) : Tool {
    override val name = "phone_call"
    override val description =
        "Call someone from the user's contacts by name, or a phone number. Call only when the user asked for a " +
            "call. If several contacts match, or a contact has several numbers and it's unclear which, ask the user " +
            "which one (pass number to call a specific one). Say who you're calling."
    override val inputSchema = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("name", JSONObject().put("type", "string").put("description", "Contact name, e.g. \"Mom\" or \"Jake\""))
            .put("number", JSONObject().put("type", "string").put("description", "A phone number to call directly")))

    private class Match(val name: String, val number: String, val kind: String)

    override suspend fun run(input: JSONObject): ToolResult {
        val number = input.optString("number").trim()
        val who = input.optString("name").trim()
        if (number.isNotEmpty()) return ToolResult.Text(call(number, who.ifEmpty { number }))
        if (who.isEmpty()) return ToolResult.Text("Ask the user who to call.")
        if (!granted(Manifest.permission.READ_CONTACTS)) return ToolResult.Text(
            "The app can't see contacts. Tell the user to allow Contacts: stop and start the assistant in the app.")

        val matches = find(who)
        if (matches.isEmpty()) return ToolResult.Text("No contact matches \"$who\".")
        val people = matches.map { it.name }.distinct()
        if (people.size > 1) return ToolResult.Text(
            "Several contacts match: ${people.take(6).joinToString()}. Ask the user which one.")
        val numbers = matches.distinctBy { it.number.filter(Char::isDigit) }
        val pick = numbers.firstOrNull { it.kind == "mobile" } ?: numbers.first()
        if (numbers.size > 1 && numbers.count { it.kind == pick.kind } > 1) return ToolResult.Text(
            "${people[0]} has several numbers: " + numbers.joinToString { "${it.kind} ${it.number}" } +
                ". Ask the user which one, then call it with number.")
        return ToolResult.Text(call(pick.number, "${pick.name} (${pick.kind})"))
    }

    private fun call(number: String, label: String): String {
        if (!granted(Manifest.permission.CALL_PHONE)) {
            return "The app isn't allowed to make calls. Tell the user to allow Phone: stop and start the assistant in the app."
        }
        return try {
            context.getSystemService(TelecomManager::class.java)
                .placeCall(Uri.fromParts("tel", number, null), Bundle())
            "Calling $label."
        } catch (e: SecurityException) {
            "The phone refused to start the call: ${e.message}"
        }
    }

    private fun find(name: String): List<Match> {
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(name))
        val cols = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
        )
        val out = mutableListOf<Match>()
        context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext() && out.size < 20) {
                val kind = when (c.getInt(2)) {
                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "mobile"
                    ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "home"
                    ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "work"
                    else -> "other"
                }
                out += Match(c.getString(0) ?: "?", c.getString(1) ?: continue, kind)
            }
        }
        // An exact name match wins over partial ones ("Jo" shouldn't offer "Joanna" when "Jo" exists).
        val exact = out.filter { it.name.equals(name, ignoreCase = true) }
        return exact.ifEmpty { out }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
}
