package dev.solon.bucklerhome

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId

/** A reminder kept by Buckler itself. [due] is local time, or null for "someday". */
data class LocalReminder(
    val id: Long,
    val title: String,
    val due: LocalDateTime?,
    val dueHasTime: Boolean,
    /** When it was ticked off (epoch millis), or null while open. */
    val doneAt: Long? = null,
) {
    val done: Boolean get() = doneAt != null
}

/**
 * Reminders added in Settings → Calendar, for anyone without a synced task list. Stored as JSON
 * in Buckler's preferences; done ones are kept a day so a mis-press can be undone, then dropped.
 */
object LocalReminders {
    private const val PREFS = "bucklerhome"
    private const val KEY = "local_reminders"

    fun all(context: Context): List<LocalReminder> = parse(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null),
    )

    fun open(context: Context, zone: ZoneId = ZoneId.systemDefault()): List<AgendaTask> =
        all(context).filter { !it.done }.map { AgendaTask(it.title, it.due?.atZone(zone), it.dueHasTime, "Buckler") }

    @Synchronized
    fun add(context: Context, title: String, due: LocalDateTime?, dueHasTime: Boolean) {
        val text = title.trim()
        if (text.isEmpty()) return
        save(context, all(context) + LocalReminder(System.currentTimeMillis(), text, due, dueHasTime))
    }

    @Synchronized
    fun setDone(context: Context, id: Long, done: Boolean, now: Long = System.currentTimeMillis()) =
        save(context, all(context).map { if (it.id == id) it.copy(doneAt = if (done) now else null) else it })

    @Synchronized
    fun remove(context: Context, id: Long) = save(context, all(context).filterNot { it.id == id })

    /** Drops reminders ticked off more than a day ago. */
    @Synchronized
    fun tidy(context: Context, now: Long = System.currentTimeMillis()) {
        val all = all(context)
        val keep = all.filterNot { r -> r.doneAt != null && now - r.doneAt > DAY_MS }
        if (keep.size != all.size) save(context, keep)
    }

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    private fun save(context: Context, list: List<LocalReminder>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, toJson(list)).apply()
    }

    fun toJson(list: List<LocalReminder>): String = JSONArray().apply {
        list.forEach { r ->
            put(
                JSONObject().put("id", r.id).put("title", r.title).put("due", r.due?.toString() ?: JSONObject.NULL)
                    .put("time", r.dueHasTime).put("done_at", r.doneAt ?: JSONObject.NULL),
            )
        }
    }.toString()

    fun parse(raw: String?): List<LocalReminder> = runCatching {
        val array = JSONArray(raw ?: return emptyList())
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            LocalReminder(
                id = o.optLong("id"),
                title = o.optString("title"),
                due = o.optString("due").takeIf { !o.isNull("due") && it.isNotBlank() }
                    ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
                dueHasTime = o.optBoolean("time"),
                doneAt = o.optLong("done_at").takeIf { !o.isNull("done_at") && o.has("done_at") },
            )
        }
    }.getOrDefault(emptyList())
}
