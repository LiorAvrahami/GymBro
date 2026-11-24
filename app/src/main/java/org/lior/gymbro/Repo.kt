package org.lior.gymbro

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.documentfile.provider.DocumentFile
import org.lior.gymbro.core.GymData
import org.lior.gymbro.core.JsonCodec
import org.lior.gymbro.core.Merge
import org.lior.gymbro.core.Station
import org.lior.gymbro.core.StationUse
import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors

/**
 * The user's data and where it lives.
 *
 * The data is the most important thing the app has (user), so it is kept twice:
 *  - the app's own copy, in its private files (gymbro_data.json and images/): fast, but Android deletes it with the app;
 *  - the user's copy, in a folder the user chose once (Storage Access Framework, e.g. Documents/GymBro): the same files
 *    plus a daily backup (backups/) and the log. Android never deletes this folder, not on an update and not on
 *    uninstall; choosing the same folder after a reinstall brings everything back.
 * At startup the two copies are merged (nothing is lost; see [Merge]) and each is brought up to date. Every save writes
 * the app's copy at once and the folder's copy right after, on a background thread.
 */
object Repo {
    const val DATA_FILE = "gymbro_data.json"
    private const val DATA_NEW = "gymbro_data.new.json"
    const val LOG_FILE = "gymbro_log.jsonl"
    const val IMAGES = "images"
    private const val BACKUPS = "backups"
    private const val SUBDIR = "GymBro"
    private const val KEEP_BACKUPS = 60
    private const val PREFS = "gymbro"
    private const val P_TREE = "folder_tree"
    private const val P_SUB = "folder_sub"
    private const val TAG = "repo"

    private lateinit var app: Context
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "gymbro-io") }

    @Volatile
    var data: GymData = GymData(createdAt = System.currentTimeMillis())
        private set

    /** The result of the last write to the user's folder (shown on the statistics page, and exported). */
    @Volatile var lastFolderWriteOk: Boolean? = null
        private set
    @Volatile var lastFolderWriteAt: Long = 0
        private set
    @Volatile var lastFolderError: String? = null
        private set
    @Volatile var loadReport: String = ""
        private set
    private var lastLogSync = 0L

    private val internalFile get() = File(app.filesDir, DATA_FILE)
    val imagesDir: File get() = File(app.filesDir, IMAGES).also { it.mkdirs() }
    fun imageFile(name: String) = File(imagesDir, name)

    // ---------------------------------------------------------------- the user's folder

    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val treeUri: Uri? get() = prefs.getString(P_TREE, null)?.let { Uri.parse(it) }
    private val subdir: String? get() = prefs.getString(P_SUB, null)
    private var dirCache: DocumentFile? = null

    val hasFolder: Boolean get() = treeUri != null

    /** A readable name of the folder, e.g. "primary:Documents/GymBro". */
    fun folderDescription(): String {
        val t = treeUri ?: return "(none chosen)"
        val path = try { Uri.decode(t.lastPathSegment ?: t.toString()) } catch (_: Exception) { t.toString() }
        return if (subdir != null) "$path/$subdir" else path
    }

    /** The data folder, or null when none was chosen or it cannot be reached (the permission was lost, the card is out). */
    @Synchronized
    fun folder(): DocumentFile? {
        dirCache?.let { if (it.exists()) return it }
        val t = treeUri ?: return null
        return try {
            val root = DocumentFile.fromTreeUri(app, t)
            if (root == null || !root.exists() || !root.canWrite()) {
                AppLog.w(TAG, "the data folder cannot be reached", "tree" to t.toString(), "exists" to root?.exists(), "canWrite" to root?.canWrite())
                null
            } else {
                val sub = subdir
                val dir = if (sub == null) root else (root.findFile(sub) ?: root.createDirectory(sub))
                dirCache = dir
                dir
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "the data folder failed", e, "tree" to t.toString())
            null
        }
    }

    fun folderReachable(): Boolean = folder() != null

    private fun child(dir: DocumentFile, sub: String): DocumentFile? =
        dir.findFile(sub)?.takeIf { it.isDirectory } ?: dir.createDirectory(sub)

    // ---------------------------------------------------------------- startup

    fun init(context: Context) {
        app = context.applicationContext
        val t0 = SystemClock.elapsedRealtime()
        val internal = readInternal()
        val folder = folder()
        val external = folder?.let { readFolderData(it) }
        reconcile(internal, external, folder, "startup")
        AppLog.i(TAG, "loaded", "ms" to SystemClock.elapsedRealtime() - t0, "report" to loadReport,
            "stations" to data.stations.size, "uses" to data.uses.size, "folder" to folderDescription())
    }

    /**
     * Makes the app's copy and the folder's copy one again (see [Merge]: nothing is lost, deletions are kept) and writes
     * whichever of them lacks something.
     */
    private fun reconcile(internal: GymData?, external: GymData?, folder: DocumentFile?, why: String) {
        val merged = when {
            internal == null && external == null -> null
            internal == null -> external
            external == null -> internal
            else -> Merge.merge(internal, external)
        }
        val writeApp = merged != null && (internal == null || !Merge.sameContent(merged, internal))
        val writeFolder = merged != null && folder != null && (external == null || !Merge.sameContent(merged, external))
        data = merged ?: GymData(createdAt = System.currentTimeMillis())
        loadReport = "$why: app copy: ${Merge.describe(internal)}; folder copy: " +
            (if (folder == null) "no folder" else Merge.describe(external)) +
            " -> ${Merge.describe(merged)}; writes: app=$writeApp folder=$writeFolder"
        AppLog.i(TAG, "reconciled", "report" to loadReport)
        if (writeApp) writeInternal(data)
        if (external != null && folder != null) pullImages(folder)
        if (folder != null) {
            val d = data
            io.execute {
                if (writeFolder) writeFolder(d, "reconcile ($why)")
                pushImages()
            }
        }
    }

    /**
     * The user chose a folder (setup, or "change folder"). A folder that holds GymBro's data (or is called GymBro) is used
     * as it is; otherwise a GymBro folder is made inside it. Data found there is merged with the app's. Returns what was
     * found, for the setup page.
     */
    fun attachFolder(tree: Uri): String {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            app.contentResolver.takePersistableUriPermission(tree, flags)
        } catch (e: Exception) {
            AppLog.e(TAG, "could not keep the folder permission", e, "tree" to tree.toString())
        }
        val root = DocumentFile.fromTreeUri(app, tree) ?: throw IllegalStateException("cannot open the folder")
        val sub: String? = when {
            root.findFile(DATA_FILE) != null || root.findFile(DATA_NEW) != null -> null
            root.name.equals(SUBDIR, ignoreCase = true) -> null
            else -> SUBDIR
        }
        val old = treeUri
        val editor = prefs.edit().putString(P_TREE, tree.toString())
        if (sub == null) editor.remove(P_SUB) else editor.putString(P_SUB, sub)
        editor.commit()
        synchronized(this) { dirCache = null }
        if (old != null && old != tree) {
            try { app.contentResolver.releasePersistableUriPermission(old, flags) } catch (_: Exception) {}
        }
        val folder = folder() ?: throw IllegalStateException("cannot write in the folder")
        val external = readFolderData(folder)
        val internal = readInternal()
        AppLog.i(TAG, "folder attached", "tree" to tree.toString(), "name" to root.name, "sub" to sub,
            "foundData" to (external != null), "previous" to old?.toString())
        reconcile(internal, external, folder, "folder chosen")
        val d = data
        return when {
            external == null -> "GymBro will keep your data in ${folderDescription()}."
            internal == null || Merge.sameContent(d, external) ->
                "Found your GymBro data in this folder: ${d.activeStations.size} stations, ${d.uses.size} sets. It is loaded."
            else -> "Found GymBro data in this folder and joined it with the data on this phone: " +
                "${d.activeStations.size} stations, ${d.uses.size} sets in all."
        }
    }

    // ---------------------------------------------------------------- reading

    private fun readInternal(): GymData? {
        val f = internalFile
        if (!f.exists()) return null
        return try {
            JsonCodec.decode(f.readText())
        } catch (e: Exception) {
            AppLog.e(TAG, "the app's copy cannot be read; it is set aside", e, "bytes" to f.length())
            f.renameTo(File(f.parentFile, "gymbro_data.broken-${System.currentTimeMillis()}.json"))
            null
        }
    }

    private fun readFolderData(dir: DocumentFile): GymData? {
        for (name in listOf(DATA_FILE, DATA_NEW)) {
            val f = dir.findFile(name) ?: continue
            try {
                val text = app.contentResolver.openInputStream(f.uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                val d = JsonCodec.decode(text)
                AppLog.i(TAG, "read the folder's data", "file" to name, "bytes" to text.length, "savedAt" to d.savedAt)
                return d
            } catch (e: Exception) {
                AppLog.e(TAG, "the folder's data cannot be read", e, "file" to name)
            }
        }
        return null
    }

    // ---------------------------------------------------------------- writing

    private fun writeInternal(d: GymData) {
        val t0 = SystemClock.elapsedRealtime()
        val text = JsonCodec.encode(d)
        val tmp = File(app.filesDir, "$DATA_FILE.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(internalFile)) {
            internalFile.delete()
            tmp.renameTo(internalFile)
        }
        AppLog.i(TAG, "saved the app's copy", "bytes" to text.length, "ms" to SystemClock.elapsedRealtime() - t0, "saveCount" to d.saveCount)
    }

    /** Writes the data to the folder: a new file first, then it takes the old one's place (never a half-written file). */
    private fun writeFolder(d: GymData, why: String) {
        val t0 = SystemClock.elapsedRealtime()
        try {
            val dir = folder() ?: throw IllegalStateException("the data folder cannot be reached")
            val bytes = JsonCodec.encode(d).toByteArray()
            dir.findFile(DATA_NEW)?.delete()
            val nf = dir.createFile("application/octet-stream", DATA_NEW) ?: throw IllegalStateException("cannot create $DATA_NEW")
            app.contentResolver.openOutputStream(nf.uri, "w")!!.use { it.write(bytes) }
            dir.findFile(DATA_FILE)?.delete()
            if (!nf.renameTo(DATA_FILE)) throw IllegalStateException("cannot rename $DATA_NEW to $DATA_FILE")
            backup(dir, bytes)
            lastFolderWriteOk = true
            lastFolderError = null
            AppLog.i(TAG, "saved the folder's copy", "why" to why, "bytes" to bytes.size, "ms" to SystemClock.elapsedRealtime() - t0)
        } catch (e: Exception) {
            lastFolderWriteOk = false
            lastFolderError = e.toString()
            synchronized(this) { dirCache = null }
            AppLog.e(TAG, "could not save the folder's copy", e, "why" to why)
        }
        lastFolderWriteAt = System.currentTimeMillis()
    }

    /** One backup a day in backups/, the newest [KEEP_BACKUPS] kept. */
    private fun backup(dir: DocumentFile, bytes: ByteArray) {
        val b = child(dir, BACKUPS) ?: return
        val name = "gymbro_data_${LocalDate.now()}.json"
        val existing = b.findFile(name)
        val f = existing ?: b.createFile("application/octet-stream", name) ?: return
        app.contentResolver.openOutputStream(f.uri, "wt")!!.use { it.write(bytes) }
        if (existing == null) {
            AppLog.i(TAG, "daily backup made", "file" to name)
            val all = b.listFiles().filter { it.name?.startsWith("gymbro_data_") == true }.sortedBy { it.name }
            all.dropLast(KEEP_BACKUPS).forEach { old -> AppLog.i(TAG, "old backup removed", "file" to old.name); old.delete() }
        }
    }

    /** Photos the folder has and the app lacks (after a reinstall) come to the app. */
    private fun pullImages(dir: DocumentFile) {
        val wanted = data.stations.flatMap { it.images }.filter { !imageFile(it).exists() }
        if (wanted.isEmpty()) return
        val imgs = dir.findFile(IMAGES) ?: return
        var n = 0
        for (name in wanted) {
            val f = imgs.findFile(name) ?: continue
            try {
                app.contentResolver.openInputStream(f.uri)!!.use { input -> imageFile(name).outputStream().use { input.copyTo(it) } }
                n++
            } catch (e: Exception) {
                AppLog.e(TAG, "could not copy a photo from the folder", e, "file" to name)
            }
        }
        AppLog.i(TAG, "photos copied from the folder", "copied" to n, "wanted" to wanted.size)
    }

    /** Photos the app has and the folder lacks go to the folder. */
    private fun pushImages() {
        try {
            val dir = folder() ?: return
            val imgs = child(dir, IMAGES) ?: return
            val there = imgs.listFiles().mapNotNull { it.name }.toSet()
            var n = 0
            for (f in imagesDir.listFiles().orEmpty()) {
                if (f.name in there) continue
                writeImageToFolder(imgs, f)
                n++
            }
            if (n > 0) AppLog.i(TAG, "photos copied to the folder", "copied" to n)
        } catch (e: Exception) {
            AppLog.e(TAG, "could not copy photos to the folder", e)
        }
    }

    private fun writeImageToFolder(imgs: DocumentFile, f: File) {
        val nf = imgs.createFile("image/jpeg", f.name) ?: throw IllegalStateException("cannot create ${f.name}")
        app.contentResolver.openOutputStream(nf.uri, "w")!!.use { out -> f.inputStream().use { it.copyTo(out) } }
        if (nf.name != f.name) nf.renameTo(f.name)
    }

    /** Copies the log to the folder, at most once a minute unless [force]. */
    fun syncLog(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastLogSync < 60_000) return
        lastLogSync = now
        io.execute {
            try {
                val dir = folder() ?: return@execute
                val bytes = AppLog.snapshotBytes()
                val f = dir.findFile(LOG_FILE) ?: dir.createFile("application/octet-stream", LOG_FILE) ?: return@execute
                app.contentResolver.openOutputStream(f.uri, "wt")!!.use { it.write(bytes) }
            } catch (e: Exception) {
                AppLog.e(TAG, "could not copy the log to the folder", e)
            }
        }
    }

    @Synchronized
    private fun save(d: GymData, why: String) {
        val saved = d.copy(savedAt = System.currentTimeMillis(), saveCount = d.saveCount + 1)
        data = saved
        try {
            writeInternal(saved)
        } catch (e: Exception) {
            AppLog.e(TAG, "could not save the app's copy", e, "why" to why)
        }
        io.execute { writeFolder(saved, why) }
        syncLog()
    }

    /** Waits until every write queued so far is done (the export waits for it). */
    fun flush() {
        io.submit(Runnable {}).get()
    }

    // ---------------------------------------------------------------- changes

    fun newId(): String = UUID.randomUUID().toString()

    /** Keeps a photo (a square JPEG made by the camera page): moved into the app's images, copied to the folder. */
    fun addImage(tmp: File): String {
        val name = "img_${System.currentTimeMillis()}_${UUID.randomUUID().toString().substring(0, 6)}.jpg"
        val dest = imageFile(name)
        if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
        AppLog.i(TAG, "photo kept", "file" to name, "bytes" to dest.length())
        io.execute {
            try {
                val dir = folder() ?: return@execute
                child(dir, IMAGES)?.let { writeImageToFolder(it, dest) }
            } catch (e: Exception) {
                AppLog.e(TAG, "could not copy the photo to the folder", e, "file" to name)
            }
        }
        return name
    }

    fun addStation(s: Station) {
        AppLog.i(TAG, "station added", "station" to JsonCodec.stationToJson(s))
        save(data.copy(stations = data.stations + s), "station added")
    }

    fun updateStation(s: Station) {
        val before = data.station(s.id)
        AppLog.i(TAG, "station changed", "before" to before?.let { JsonCodec.stationToJson(it) }, "after" to JsonCodec.stationToJson(s))
        save(data.copy(stations = data.stations.map { if (it.id == s.id) s else it }), "station changed")
    }

    fun deleteStation(id: String) {
        val s = data.station(id) ?: return
        AppLog.i(TAG, "station deleted", "station" to JsonCodec.stationToJson(s))
        save(data.copy(stations = data.stations.map { if (it.id == id) it.copy(deleted = true, updatedAt = System.currentTimeMillis()) else it }), "station deleted")
    }

    /** Clears a QR code from whatever station has it (it is moving to another station). */
    fun clearQr(value: String, exceptId: String?) {
        val changed = data.stations.filter { it.qrValue == value && it.id != exceptId }
        if (changed.isEmpty()) return
        AppLog.i(TAG, "QR code taken off stations", "qr" to value, "stations" to changed.map { it.name })
        save(data.copy(stations = data.stations.map {
            if (it.qrValue == value && it.id != exceptId) it.copy(qrValue = null, qrFormat = null, updatedAt = System.currentTimeMillis()) else it
        }), "QR moved")
    }

    fun addUse(u: StationUse) {
        AppLog.i(TAG, "set saved", "use" to JsonCodec.useToJson(u))
        save(data.copy(uses = data.uses + u), "set saved")
    }

    fun deleteUse(id: String) {
        val u = data.uses.firstOrNull { it.id == id } ?: return
        AppLog.i(TAG, "set deleted", "use" to JsonCodec.useToJson(u))
        save(data.copy(uses = data.uses.filter { it.id != id }, deletedUseIds = data.deletedUseIds + id), "set deleted")
    }

    // ---------------------------------------------------------------- diagnostics

    /** The files of the app's copy and of the folder, with sizes (for the export). */
    fun fileListing(): Pair<List<String>, List<String>> {
        val internal = app.filesDir.walkTopDown().filter { it.isFile }.map {
            "${it.relativeTo(app.filesDir).path}\t${it.length()}\t${it.lastModified()}"
        }.toList()
        val external = mutableListOf<String>()
        try {
            folder()?.let { dir ->
                fun walk(d: DocumentFile, prefix: String) {
                    for (f in d.listFiles()) {
                        if (f.isDirectory) walk(f, "$prefix${f.name}/")
                        else external.add("$prefix${f.name}\t${f.length()}\t${f.lastModified()}")
                    }
                }
                walk(dir, "")
            }
        } catch (e: Exception) {
            external.add("error: $e")
        }
        return internal to external
    }
}
