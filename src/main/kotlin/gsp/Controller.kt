package gsp

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Paths

/** A message at the bottom of the window, optionally with one button (for example Undo). */
class Toast(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null, val seconds: Int = 5)

/** Holds the tables, the Google connection and the sync, and is shared by all screens. */
class Controller(private val scope: CoroutineScope) {
    private val root = Paths.get(System.getenv("APPDATA") ?: System.getProperty("user.home"), "GsheetPlus")
    val backups = Backups(root.resolve("data"), root.resolve("backups"))

    /** True when a backup was put back at this start. */
    val restoredAtStart: Boolean = backups.applyPending()
    val ws = Workspace(root.resolve("data"))
    val auth = GoogleAuth()
    val api = SheetsApi(auth)
    val engine = SyncEngine(ws, api)
    val attachmentSync = AttachmentSync(ws, GoogleAttachments(auth, api))

    val dataFolder: java.nio.file.Path get() = root
    val settings = AppSettings(root.resolve("settings.json"))
    val dark = mutableStateOf(settings.dark)
    val syncSeconds = mutableStateOf(settings.syncSeconds)
    val pictureRows = mutableStateOf(settings.pictureRows)

    /** Locked with a PIN until [unlock] succeeds. */
    val locked = mutableStateOf(settings.pinHash != null)
    val hasPin = mutableStateOf(settings.pinHash != null)
    val quickAdd = mutableStateOf(false)

    /** Set by the open table: receives files dropped on the window. */
    @Volatile
    var dropHandler: ((List<java.io.File>) -> Unit)? = null

    fun unlock(pin: String): Boolean {
        val ok = PinLock.check(pin, settings.pinSalt, settings.pinHash)
        if (ok) locked.value = false
        return ok
    }

    fun setPin(pin: String?) {
        if (pin == null) {
            settings.pinSalt = null
            settings.pinHash = null
        } else {
            val salt = PinLock.newSalt()
            settings.pinSalt = salt
            settings.pinHash = PinLock.hash(pin, salt)
        }
        settings.save()
        hasPin.value = pin != null
    }

    fun lockNow() {
        if (settings.pinHash != null) locked.value = true
    }

    val reportHeader = mutableStateOf(ReportHeader(settings.reportBusiness, settings.reportLine2, settings.reportLogo))

    fun setReportHeader(header: ReportHeader) {
        reportHeader.value = header
        settings.reportBusiness = header.business
        settings.reportLine2 = header.line2
        settings.reportLogo = header.logoPath
        settings.save()
    }

    val dailyBackup = mutableStateOf(settings.dailyBackup)

    fun setDailyBackup(value: Boolean) {
        dailyBackup.value = value
        settings.dailyBackup = value
        settings.save()
    }

    fun backupNow(quiet: Boolean = false) {
        background("Backup") {
            ws.flush()
            val made = if (quiet) backups.daily() else backups.create()
            if (!quiet && made != null) say("Backup saved: ${made.fileName}")
        }
    }

    /** Writes a page to the reports folder and opens it in the browser. */
    fun openPage(name: String, html: String) {
        background("Open page") {
            val dir = root.resolve("reports")
            java.nio.file.Files.createDirectories(dir)
            val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)
            val file = dir.resolve("$safe.html")
            java.nio.file.Files.writeString(file, html)
            java.awt.Desktop.getDesktop().browse(file.toUri())
        }
    }

    val tableView = mutableStateOf(settings.tableView)
    val tableZoom = mutableStateOf(settings.tableZoom)
    val tableSort = mutableStateOf(settings.tableSort)

    fun setTableLook(view: String = tableView.value, zoom: Float = tableZoom.value, sort: String = tableSort.value) {
        tableView.value = view
        tableZoom.value = zoom.coerceIn(0.7f, 1.6f)
        tableSort.value = sort
        settings.tableView = view
        settings.tableZoom = tableZoom.value
        settings.tableSort = sort
        settings.save()
    }

    fun setPictureRows(value: Int) {
        pictureRows.value = value
        settings.pictureRows = value
        settings.save()
    }

    fun setDark(value: Boolean) {
        dark.value = value
        settings.dark = value
        settings.save()
    }

    fun setSyncSeconds(value: Int) {
        syncSeconds.value = value
        settings.syncSeconds = value
        settings.save()
    }

    val toast = mutableStateOf<Toast?>(null)
    val syncing = mutableStateOf(false)
    val status = mutableStateOf("")
    val signedIn = mutableStateOf(auth.isSignedIn())

    private val pushJobs = HashMap<Long, Job>()
    private val syncLock = Mutex()

    fun say(text: String) {
        toast.value = Toast(text)
    }

    /** Runs [block] on a background thread and reports failures as a message. */
    fun background(label: String? = null, block: suspend () -> Unit): Job = scope.launch(Dispatchers.IO) {
        try {
            block()
        } catch (e: NotSignedIn) {
            signedIn.value = false
            say("Sign in with Google first.")
        } catch (e: Exception) {
            say(explain(e, label))
        }
    }

    fun explain(e: Exception, label: String? = null): String {
        val text = when (e) {
            is java.io.IOException -> "Offline - changes saved on this PC"
            is ApiException -> "Google: ${e.message}"
            else -> e.message ?: e.toString()
        }
        return if (label != null) "$label: $text" else text
    }

    fun signIn(onDone: () -> Unit = {}) {
        background("Sign-in") {
            say("Waiting for Google in your browser...")
            auth.signIn()
            signedIn.value = true
            toast.value = null
            onDone()
        }
    }

    fun signOut() {
        auth.signOut()
        signedIn.value = false
    }

    /** Sends and receives changes of a linked table now. */
    fun syncNow(tableId: Long, announce: Boolean = true) {
        if (ws.link(tableId) == null) return
        scope.launch(Dispatchers.IO) { runSync(tableId, announce) }
    }

    /** Syncs a moment after the last edit. */
    fun scheduleSync(tableId: Long, afterMs: Long = 1500) {
        if (ws.link(tableId) == null || !signedIn.value) return
        pushJobs[tableId]?.cancel()
        pushJobs[tableId] = scope.launch(Dispatchers.IO) {
            delay(afterMs)
            runSync(tableId, false)
        }
    }

    private suspend fun runSync(tableId: Long, announce: Boolean) {
        syncLock.withLock {
            syncing.value = true
            try {
                val result = engine.sync(tableId)
                var uploadError: String? = null
                try {
                    attachmentSync.run(tableId)
                } catch (e: NotSignedIn) {
                    throw e
                } catch (e: Exception) {
                    uploadError = explain(e, "Pictures and files")
                }
                status.value = "Synced " + java.time.LocalTime.now().withNano(0)
                if (uploadError != null) say(uploadError)
                else if (announce || result.conflicts > 0 || result.columnMoves > 0 || result.orderFromSheet || result.notes.isNotEmpty()) say(result.message())
            } catch (e: NotSignedIn) {
                signedIn.value = false
                status.value = "Sign in to sync"
            } catch (e: Exception) {
                status.value = explain(e)
            } finally {
                syncing.value = false
            }
        }
    }

    /** Saves a change and syncs soon. */
    fun <T> edit(tableId: Long, block: () -> T): T {
        val result = block()
        scheduleSync(tableId)
        return result
    }

    /** Deletes a row with an Undo offer; the Sheet row is removed only after the offer. */
    fun deleteRow(tableId: Long, rowId: Long) {
        val snapshot = ws.deleteRow(tableId, rowId) ?: return
        var undone = false
        toast.value = Toast("Row deleted", "Undo", {
            undone = true
            ws.restoreRow(tableId, snapshot)
            scheduleSync(tableId)
        }, seconds = 6)
        scope.launch {
            delay(6_500)
            if (!undone) ws.discardSnapshot(snapshot)
        }
        scheduleSync(tableId, 7_000)
    }
}
