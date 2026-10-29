package com.example.chatreader

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 交友小精靈的記憶庫（第一期、第二期共用）
 *
 * - 每個朋友一張「人物卡」：事實（喜好、近況、背景）＋有日期的事件（考試、生日、行程）
 * - 另外記「我的成長」：每次「聊完復盤」的統計
 * - 全部存在手機裡的 files/friend_memory.json，不會上傳；App 裡可以看、可以刪
 * - 只有使用者在確認畫面勾選的東西才會存進來（小精靈不會自己偷記）
 */

/** 一條關於朋友的事實。category：喜好、近況、背景、其他 */
data class Fact(
    val id: String,
    val category: String,
    val text: String,
    val source: String,      // 從哪裡記的，例如「Messenger 聊天」「Facebook」
    val savedAt: Long,
)

/** 一件有日期的事。date = yyyy-MM-dd；kind：考試、生日、行程、其他 */
data class FriendEvent(
    val id: String,
    val title: String,
    val date: String,
    val kind: String,
    val note: String,
    val savedAt: Long,
    var reminded: Boolean = false,
)

data class Person(
    val id: String,
    var name: String,
    val facts: MutableList<Fact>,
    val events: MutableList<FriendEvent>,
    val createdAt: Long,
)

/** 一次「聊完復盤」的紀錄（我的成長） */
data class DebriefLog(
    val time: Long,
    val person: String,
    val myMessages: Int,
    val theirMessages: Int,
    val myQuestions: Int,
    val summary: String,
)

object MemoryStore {
    private const val TAG = "MemoryStore"
    private const val FILE_NAME = "friend_memory.json"

    private val people = mutableListOf<Person>()
    private val debriefs = mutableListOf<DebriefLog>()
    private var file: File? = null

    fun newId(): String = UUID.randomUUID().toString().take(8)

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.TAIWAN).format(Date())

    /** 每次使用前呼叫一次（重複呼叫沒關係） */
    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.applicationContext.filesDir, FILE_NAME)
        load()
    }

    // ───────────────────────── 查詢 ─────────────────────────

    @Synchronized
    fun allPeople(): List<Person> = people.toList()

    @Synchronized
    fun allDebriefs(): List<DebriefLog> = debriefs.toList()

    /** 名字去掉空白，比對用 */
    private fun norm(s: String) = s.replace(" ", "").replace("　", "").trim()

    /**
     * 找人：先找完全一樣的名字；找不到時，名字互相包含且只有一個符合才算（例如「孟澤」和「王孟澤」）。
     * 找不到回傳 null（不會自己建立）。
     */
    @Synchronized
    fun find(name: String): Person? {
        val n = norm(name)
        if (n.isEmpty()) return null
        people.firstOrNull { norm(it.name) == n }?.let { return it }
        if (n.length < 2) return null
        val partial = people.filter { val p = norm(it.name); p.contains(n) || n.contains(p) }
        return partial.singleOrNull()
    }

    @Synchronized
    fun findOrCreate(name: String): Person {
        find(name)?.let { return it }
        val p = Person(newId(), name.trim(), mutableListOf(), mutableListOf(), System.currentTimeMillis())
        people.add(p)
        save()
        return p
    }

    /** 今天以後（含今天）的事件，依日期排序 */
    @Synchronized
    fun upcomingEvents(): List<Pair<Person, FriendEvent>> {
        val t = today()
        return people.flatMap { p -> p.events.filter { it.date >= t }.map { p to it } }
            .sortedBy { it.second.date }
    }

    /** 給 AI 看的記憶摘要（聊天前小抄、回覆建議、復盤用） */
    @Synchronized
    fun memoryText(person: Person?): String {
        if (person == null) return ""
        return buildString {
            person.facts.groupBy { it.category }.forEach { (cat, list) ->
                appendLine("$cat：" + list.joinToString("、") { it.text })
            }
            person.events.sortedBy { it.date }.forEach {
                appendLine("事件：${it.date} ${it.title}（${it.kind}）" + if (it.note.isNotBlank()) "，${it.note}" else "")
            }
        }.trim()
    }

    // ───────────────────────── 新增 ─────────────────────────

    /** 加事實；同一個人、一樣的文字不重複加。回傳實際新增幾條 */
    @Synchronized
    fun addFacts(person: Person, facts: List<Pair<String, String>>, source: String): Int {
        var added = 0
        for ((cat, text) in facts) {
            val t = text.trim()
            if (t.isEmpty() || person.facts.any { it.text == t }) continue
            person.facts.add(Fact(newId(), cat.ifBlank { "其他" }, t, source, System.currentTimeMillis()))
            added++
        }
        if (added > 0) save()
        return added
    }

    /** 加事件；同一個人、同標題同日期不重複加。回傳新增的事件（給提醒排程用） */
    @Synchronized
    fun addEvents(person: Person, events: List<FriendEvent>): List<FriendEvent> {
        val added = mutableListOf<FriendEvent>()
        for (e in events) {
            if (person.events.any { it.title == e.title && it.date == e.date }) continue
            person.events.add(e)
            added.add(e)
        }
        if (added.isNotEmpty()) save()
        return added
    }

    @Synchronized
    fun addDebrief(log: DebriefLog) {
        debriefs.add(log)
        save()
    }

    @Synchronized
    fun markReminded(personId: String, eventId: String) {
        people.firstOrNull { it.id == personId }?.events?.firstOrNull { it.id == eventId }?.reminded = true
        save()
    }

    // ───────────────────────── 刪除 ─────────────────────────

    @Synchronized
    fun deleteFact(personId: String, factId: String) {
        people.firstOrNull { it.id == personId }?.facts?.removeAll { it.id == factId }
        save()
    }

    @Synchronized
    fun deleteEvent(personId: String, eventId: String) {
        people.firstOrNull { it.id == personId }?.events?.removeAll { it.id == eventId }
        save()
    }

    @Synchronized
    fun deletePerson(personId: String) {
        people.removeAll { it.id == personId }
        save()
    }

    // ───────────────────────── 存檔 ─────────────────────────

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            val o = JSONObject(f.readText())
            people.clear()
            val arr = o.optJSONArray("people") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val facts = mutableListOf<Fact>()
                val fa = p.optJSONArray("facts") ?: JSONArray()
                for (j in 0 until fa.length()) {
                    val x = fa.getJSONObject(j)
                    facts.add(Fact(x.optString("id", newId()), x.optString("category"), x.optString("text"),
                        x.optString("source"), x.optLong("savedAt")))
                }
                val events = mutableListOf<FriendEvent>()
                val ea = p.optJSONArray("events") ?: JSONArray()
                for (j in 0 until ea.length()) {
                    val x = ea.getJSONObject(j)
                    events.add(FriendEvent(x.optString("id", newId()), x.optString("title"), x.optString("date"),
                        x.optString("kind"), x.optString("note"), x.optLong("savedAt"), x.optBoolean("reminded")))
                }
                people.add(Person(p.optString("id", newId()), p.optString("name"), facts, events, p.optLong("createdAt")))
            }
            debriefs.clear()
            val da = o.optJSONArray("debriefs") ?: JSONArray()
            for (i in 0 until da.length()) {
                val x = da.getJSONObject(i)
                debriefs.add(DebriefLog(x.optLong("time"), x.optString("person"), x.optInt("myMessages"),
                    x.optInt("theirMessages"), x.optInt("myQuestions"), x.optString("summary")))
            }
        }.onFailure { Log.e(TAG, "讀取記憶失敗", it) }
    }

    private fun save() {
        val f = file ?: return
        val o = JSONObject()
        o.put("people", JSONArray().apply {
            people.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id); put("name", p.name); put("createdAt", p.createdAt)
                    put("facts", JSONArray().apply {
                        p.facts.forEach { x ->
                            put(JSONObject().put("id", x.id).put("category", x.category).put("text", x.text)
                                .put("source", x.source).put("savedAt", x.savedAt))
                        }
                    })
                    put("events", JSONArray().apply {
                        p.events.forEach { x ->
                            put(JSONObject().put("id", x.id).put("title", x.title).put("date", x.date)
                                .put("kind", x.kind).put("note", x.note).put("savedAt", x.savedAt)
                                .put("reminded", x.reminded))
                        }
                    })
                })
            }
        })
        o.put("debriefs", JSONArray().apply {
            debriefs.forEach { d ->
                put(JSONObject().put("time", d.time).put("person", d.person).put("myMessages", d.myMessages)
                    .put("theirMessages", d.theirMessages).put("myQuestions", d.myQuestions).put("summary", d.summary))
            }
        })
        runCatching { f.writeText(o.toString(2)) }.onFailure { Log.e(TAG, "存檔失敗", it) }
    }
}
