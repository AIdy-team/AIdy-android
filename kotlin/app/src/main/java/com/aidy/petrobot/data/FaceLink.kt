package com.aidy.petrobot.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本体の顔の登録 (見分ける人) と Aidy の名前の窓口 (docs/本体アプリ連携_会話表情予定モジュール_仕様.txt の 7〜9 章)。
 *
 *  GET  /api/faces                     登録している人の一覧 (メンバー・声で覚えた人)
 *  POST /api/faces/enroll/start        スマホのカメラで登録を始める {name, yomi, profile}
 *  POST /api/faces/enroll/frame        1 枚送る {sid, step, jpg(base64)} → 本体が見て、使えたか・次の向きを返す
 *  POST /api/faces/enroll/finish       {sid} 登録を確定 / POST /api/faces/enroll/cancel {sid}
 *  POST /api/faces/update              {id, name, yomi, profile} 名前・どんな人かを直す
 *  POST /api/faces/delete              {id, confirm:true} 消す (アプリは必ず確かめてから送る)
 *  GET/POST /api/aidy/profile          Aidy の名前 {name, yomi}
 */
object FaceLink {
    /** どんな人か (どれも空でよい)。 */
    data class Profile(
        val relation: String = "",
        val birthday: String = "",
        val likes: String = "",
        val memo: String = "",
    ) {
        fun json(): JSONObject = JSONObject().put("relation", relation).put("birthday", birthday).put("likes", likes).put("memo", memo)

        companion object {
            fun of(o: JSONObject?) = if (o == null) Profile() else
                Profile(o.optString("relation"), o.optString("birthday"), o.optString("likes"), o.optString("memo"))
        }
    }

    /** 登録している人 1 人。kind = "member" (登録した人) / "learned" (声の自己紹介で覚えた人)。 */
    data class Person(
        val id: String,
        val name: String,
        val yomi: String,
        val kind: String,
        val count: Int,
        val made: Long,
        val seen: Long,
        val source: String,
        val profile: Profile,
    )

    /** 登録の手順 1 つ (本体の enroll.py の STEPS と同じ)。 */
    data class Step(val key: String, val label: String, val say: String)

    /** 本体が決めた手順 (start の返事で上書きする)。 */
    val DefaultSteps = listOf(
        Step("front", "枠の中で正面を見てね", "画面の枠に顔を入れて、まっすぐ見てね。"),
        Step("side1", "ゆっくり横を向いてね", "そのまま、ゆっくり横を向いてね。"),
        Step("side2", "反対の横を向いてね", "次は、反対の横を向いてね。"),
        Step("up", "少し上を向いてね", "少し上を向いてね。"),
        Step("down", "少し下を向いてね", "少し下を向いてね。"),
        Step("front2", "もう一度正面を見てね", "最後に、もう一度まっすぐ見てね。"),
    )

    data class Session(val sid: String, val steps: List<Step>, val perStep: Int, val timeoutSec: Int)

    /** 1 枚送った結果。step = 本体が次に欲しい向き。 */
    data class FrameResult(
        val ok: Boolean,
        val reason: String,
        val step: String,
        val got: Int,
        val total: Int,
        val need: Int,
        val done: Boolean,
        val timeout: Boolean,
    )

    class LinkError(msg: String) : Exception(msg)

    var people by mutableStateOf<List<Person>>(emptyList())
    var aidyName by mutableStateOf("")
    var aidyYomi by mutableStateOf("")
    /** 一覧が変わったら増える (本体の知らせ faces で増やす)。 */
    var version by mutableIntStateOf(0)
    var supported by mutableStateOf<Boolean?>(null)

    private suspend fun post(path: String, body: JSONObject): JSONObject {
        if (!AidyDeviceLink.isConfigured) throw LinkError("本体とつながっていません")
        val r = try {
            AidyDeviceLink.call("POST", path, body)
        } catch (e: Exception) {
            throw LinkError(AidyDeviceLink.errorMessage(null, e))
        }
        return check(r)
    }

    private fun check(r: AidyDeviceLink.Resp): JSONObject {
        if (r.code == 404 && r.error() == null) { supported = false; throw LinkError("本体がまだこの機能に対応していません (本体を新しくしてね)") }
        if (r.code != 200) throw LinkError(r.error() ?: AidyDeviceLink.errorMessage(r.code))
        supported = true
        return runCatching { r.json() }.getOrElse { JSONObject() }
    }

    suspend fun refresh() {
        if (!AidyDeviceLink.isConfigured) throw LinkError("本体とつながっていません")
        val r = try { AidyDeviceLink.call("GET", "/api/faces") } catch (e: Exception) { throw LinkError(AidyDeviceLink.errorMessage(null, e)) }
        val o = check(r)
        val arr = o.optJSONArray("people") ?: JSONArray()
        people = (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONObject(i) ?: return@mapNotNull null
            Person(
                p.optString("id"), p.optString("name"), p.optString("yomi"), p.optString("kind", "member"),
                p.optInt("count"), p.optLong("made"), p.optLong("seen"), p.optString("source"), Profile.of(p.optJSONObject("profile")),
            )
        }
        version++
    }

    suspend fun start(name: String, yomi: String, profile: Profile): Session {
        val o = post("/api/faces/enroll/start", JSONObject().put("name", name).put("yomi", yomi).put("profile", profile.json()).put("source", "phone"))
        val arr = o.optJSONArray("steps")
        val steps = if (arr == null || arr.length() == 0) DefaultSteps else (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            Step(s.optString("key"), s.optString("label"), s.optString("say"))
        }
        val sid = o.optString("sid")
        if (sid.isEmpty()) throw LinkError("本体が登録を始められませんでした")
        return Session(sid, steps, o.optInt("per_step", 3), o.optInt("timeout", 60))
    }

    suspend fun frame(sid: String, step: String, jpgBase64: String): FrameResult {
        val o = post("/api/faces/enroll/frame", JSONObject().put("sid", sid).put("step", step).put("jpg", jpgBase64))
        return FrameResult(
            o.optBoolean("ok"), o.optString("reason"), o.optString("step", step), o.optInt("got"), o.optInt("total"),
            o.optInt("need", 18), o.optBoolean("done"), o.optBoolean("timeout"),
        )
    }

    suspend fun finish(sid: String): Person? {
        val o = post("/api/faces/enroll/finish", JSONObject().put("sid", sid))
        runCatching { refresh() }
        val id = o.optJSONObject("person")?.optString("id")
        return people.firstOrNull { it.id == id }
    }

    suspend fun cancel(sid: String) {
        runCatching { post("/api/faces/enroll/cancel", JSONObject().put("sid", sid)) }
    }

    suspend fun update(id: String, name: String, yomi: String, profile: Profile) {
        post("/api/faces/update", JSONObject().put("id", id).put("name", name).put("yomi", yomi).put("profile", profile.json()))
        runCatching { refresh() }
    }

    suspend fun delete(id: String) {
        post("/api/faces/delete", JSONObject().put("id", id).put("confirm", true))
        runCatching { refresh() }
    }

    suspend fun loadAidyName() {
        if (!AidyDeviceLink.isConfigured) throw LinkError("本体とつながっていません")
        val r = try { AidyDeviceLink.call("GET", "/api/aidy/profile") } catch (e: Exception) { throw LinkError(AidyDeviceLink.errorMessage(null, e)) }
        val o = check(r)
        aidyName = o.optString("name")
        aidyYomi = o.optString("yomi")
    }

    suspend fun saveAidyName(name: String, yomi: String) {
        val o = post("/api/aidy/profile", JSONObject().put("name", name).put("yomi", yomi))
        aidyName = o.optString("name", name)
        aidyYomi = o.optString("yomi", yomi)
    }
}
