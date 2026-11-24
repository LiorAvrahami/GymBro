package org.lior.gymbro.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * [GymData] to JSON and back. Reading is lenient: a missing field gets its default, so older files stay readable after
 * fields are added. Uses only the org.json calls that Android's own copy has.
 */
object JsonCodec {

    fun toJson(d: GymData): JSONObject = JSONObject().apply {
        put("format", "gymbro-data")
        put("schemaVersion", d.schemaVersion)
        put("createdAt", d.createdAt)
        put("savedAt", d.savedAt)
        put("saveCount", d.saveCount)
        put("stations", JSONArray().apply { d.stations.forEach { put(stationToJson(it)) } })
        put("uses", JSONArray().apply { d.uses.forEach { put(useToJson(it)) } })
        put("deletedUseIds", JSONArray().apply { d.deletedUseIds.forEach { put(it) } })
    }

    fun fromJson(o: JSONObject): GymData {
        val stations = o.optJSONArray("stations")?.let { a -> (0 until a.length()).map { stationFromJson(a.getJSONObject(it)) } }
        val uses = o.optJSONArray("uses")?.let { a -> (0 until a.length()).map { useFromJson(a.getJSONObject(it)) } }
        return GymData(
            schemaVersion = o.optInt("schemaVersion", GymData.SCHEMA_VERSION),
            createdAt = o.optLong("createdAt", 0),
            savedAt = o.optLong("savedAt", 0),
            saveCount = o.optLong("saveCount", 0),
            stations = stations ?: emptyList(),
            uses = uses ?: emptyList(),
            deletedUseIds = o.optJSONArray("deletedUseIds")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
        )
    }

    fun encode(d: GymData): String = toJson(d).toString(1)
    fun decode(s: String): GymData = fromJson(JSONObject(s))

    fun stationToJson(s: Station): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("qrValue", s.qrValue ?: JSONObject.NULL)
        put("qrFormat", s.qrFormat ?: JSONObject.NULL)
        put("weightIncrement", s.weightIncrement)
        put("images", JSONArray().apply { s.images.forEach { put(it) } })
        put("createdAt", s.createdAt)
        put("updatedAt", s.updatedAt)
        put("deleted", s.deleted)
    }

    fun stationFromJson(o: JSONObject): Station = Station(
        id = o.getString("id"),
        name = o.optString("name", ""),
        qrValue = optStringOrNull(o, "qrValue"),
        qrFormat = optStringOrNull(o, "qrFormat"),
        weightIncrement = o.optDouble("weightIncrement", Station.DEFAULT_INCREMENT).let { if (it.isNaN() || it <= 0) Station.DEFAULT_INCREMENT else it },
        images = o.optJSONArray("images")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
        createdAt = o.optLong("createdAt", 0),
        updatedAt = o.optLong("updatedAt", o.optLong("createdAt", 0)),
        deleted = o.optBoolean("deleted", false),
    )

    fun useToJson(u: StationUse): JSONObject = JSONObject().apply {
        put("id", u.id)
        put("stationId", u.stationId)
        put("time", u.time)
        put("reps", u.reps)
        put("weight", u.weight)
        put("difficulty", u.difficulty)
        put("stationName", u.stationName)
        put("source", u.source)
        put("openedAt", u.openedAt)
    }

    fun useFromJson(o: JSONObject): StationUse = StationUse(
        id = o.getString("id"),
        stationId = o.getString("stationId"),
        time = o.optLong("time", 0),
        reps = o.optInt("reps", 0),
        weight = o.optDouble("weight", 0.0).let { if (it.isNaN()) 0.0 else it },
        difficulty = o.optInt("difficulty", 0),
        stationName = o.optString("stationName", ""),
        source = o.optString("source", ""),
        openedAt = o.optLong("openedAt", 0),
    )

    private fun optStringOrNull(o: JSONObject, key: String): String? =
        if (!o.has(key) || o.isNull(key)) null else o.optString(key, "").ifEmpty { null }
}
