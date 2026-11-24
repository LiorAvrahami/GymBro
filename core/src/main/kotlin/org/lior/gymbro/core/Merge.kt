package org.lior.gymbro.core

import kotlin.math.max

/**
 * Two copies of the user's data become one, and nothing is lost: the app keeps a copy in its own files and one in the
 * user's folder, and after a reinstall, a restore from Android's backup or a change of folder the two can differ.
 *  - stations: every station of both; a station in both is the one changed last (a deletion is a change, so it stays);
 *  - sets: every set of both, except the ones deleted in either (their ids are kept in deletedUseIds);
 *  - the data's age is the older of the two, its last save the newer.
 */
object Merge {
    fun merge(a: GymData, b: GymData): GymData {
        val stations = LinkedHashMap<String, Station>()
        for (s in a.stations + b.stations) {
            val o = stations[s.id]
            if (o == null || s.updatedAt > o.updatedAt) stations[s.id] = s
        }
        val deleted = (a.deletedUseIds + b.deletedUseIds).toSortedSet()
        val uses = LinkedHashMap<String, StationUse>()
        for (u in a.uses + b.uses) if (u.id !in deleted && u.id !in uses) uses[u.id] = u
        return GymData(
            schemaVersion = max(a.schemaVersion, b.schemaVersion),
            createdAt = listOf(a.createdAt, b.createdAt).filter { it > 0 }.minOrNull() ?: max(a.createdAt, b.createdAt),
            savedAt = max(a.savedAt, b.savedAt),
            saveCount = max(a.saveCount, b.saveCount),
            stations = stations.values.toList(),
            uses = uses.values.sortedBy { it.time },
            deletedUseIds = deleted.toList(),
        )
    }

    /** The same stations, sets and deletions (the save time and count aside). */
    fun sameContent(a: GymData, b: GymData): Boolean =
        a.stations.sortedBy { it.id } == b.stations.sortedBy { it.id } &&
            a.uses.sortedBy { it.id } == b.uses.sortedBy { it.id } &&
            a.deletedUseIds.toSet() == b.deletedUseIds.toSet()

    /** A short description for the log: "3 stations, 41 sets, 2 deleted sets". */
    fun describe(d: GymData?): String =
        if (d == null) "none" else "${d.activeStations.size} stations (+${d.stations.size - d.activeStations.size} deleted), " +
            "${d.uses.size} sets, ${d.deletedUseIds.size} deleted sets, saved ${d.savedAt} (#${d.saveCount}), created ${d.createdAt}"

}
