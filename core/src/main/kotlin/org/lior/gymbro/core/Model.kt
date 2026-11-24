package org.lior.gymbro.core

/**
 * The user's gym data. Everything the app knows about the user's training is in one [GymData]: it is saved as one JSON
 * file (see [JsonCodec]) next to the station photos, in a folder the user owns, so it outlives the app.
 */

/** A machine or place in the gym. */
data class Station(
    val id: String,
    val name: String,
    /** The raw value of the station's QR code or barcode; null when the station has none. */
    val qrValue: String? = null,
    /** The barcode format the scanner reported for [qrValue] (e.g. "QR_CODE"). */
    val qrFormat: String? = null,
    /** How much one step of the machine's weight stack is (kg). */
    val weightIncrement: Double = DEFAULT_INCREMENT,
    /** Photo file names (square JPEGs in the images folder). The first is the station's picture. */
    val images: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    /** Deleted stations stay in the data so that their history stays readable. */
    val deleted: Boolean = false,
) {
    val hasQr: Boolean get() = !qrValue.isNullOrEmpty()
    val mainImage: String? get() = images.firstOrNull()

    companion object {
        const val DEFAULT_INCREMENT = 5.0
    }
}

/** One set done on a station ("StationUse" in the layout draft). */
data class StationUse(
    val id: String,
    val stationId: String,
    /** When the set was submitted (epoch ms). */
    val time: Long,
    val reps: Int,
    val weight: Double,
    val difficulty: Int,
    /** The station's name when the set was done (kept for the history and the diagnostics export). */
    val stationName: String = "",
    /** How the station page was opened: "qr" (scanned) or "grid" (tapped). */
    val source: String = "",
    /** When the station page was opened (epoch ms), 0 when unknown. */
    val openedAt: Long = 0,
)

data class GymData(
    val schemaVersion: Int = SCHEMA_VERSION,
    val createdAt: Long,
    /** When this data was last saved (epoch ms). */
    val savedAt: Long = 0,
    /** How many times this data has been saved since it was created. */
    val saveCount: Long = 0,
    val stations: List<Station> = emptyList(),
    val uses: List<StationUse> = emptyList(),
    /** The ids of deleted sets, so that a merge with an older copy does not bring them back (see [Merge]). */
    val deletedUseIds: List<String> = emptyList(),
) {
    fun station(id: String): Station? = stations.firstOrNull { it.id == id }
    fun stationByQr(value: String): Station? = stations.firstOrNull { !it.deleted && it.qrValue == value }
    val activeStations: List<Station> get() = stations.filter { !it.deleted }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

object Limits {
    const val REPS_MIN = 6
    const val REPS_MAX = 15
    const val REPS_DEFAULT = 10
    const val DIFFICULTY_MIN = 1
    const val DIFFICULTY_MAX = 10
    const val DIFFICULTY_DEFAULT = 5
}
