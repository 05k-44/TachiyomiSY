package eu.kanade.tachiyomi.data.backup.full

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.backup.BackupConst
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.BackupRestoreService
import eu.kanade.tachiyomi.data.backup.full.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.full.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.full.models.BackupManga
import eu.kanade.tachiyomi.data.backup.full.models.BackupMergedMangaReference
import eu.kanade.tachiyomi.data.backup.full.models.BackupSavedSearch
import eu.kanade.tachiyomi.data.backup.full.models.BackupSerializer
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.all.MergedSource
import eu.kanade.tachiyomi.util.chapter.NoChaptersException
import eu.kanade.tachiyomi.util.system.acquireWakeLock
import eu.kanade.tachiyomi.util.system.isServiceRunning
import exh.EXHMigrations
import exh.MERGED_SOURCE_ID
import exh.eh.EHentaiThrottleManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import okio.buffer
import okio.gzip
import okio.source
import rx.Observable
import timber.log.Timber
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Restores backup from a JSON file.
 */
@OptIn(ExperimentalSerializationApi::class)
class FullBackupRestoreService : Service() {

    companion object {

        fun isItRunning(context: Context): Boolean =
            context.isServiceRunning(FullBackupRestoreService::class.java)

        /**
         * Starts a service to restore a backup from Json
         *
         * @param context context of application
         * @param uri path of Uri
         */
        fun start(context: Context, uri: Uri, online: Boolean) {
            if (!BackupRestoreService.isRunning(context)) {
                val intent = Intent(context, FullBackupRestoreService::class.java).apply {
                    putExtra(BackupConst.EXTRA_URI, uri)
                    putExtra(BackupConst.EXTRA_TYPE, online)
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    context.startService(intent)
                } else {
                    context.startForegroundService(intent)
                }
            }
        }

        /**
         * Stops the service.
         *
         * @param context the application context.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, FullBackupRestoreService::class.java))

            BackupNotifier(context).showRestoreError(context.getString(R.string.restoring_backup_canceled))
        }
    }

    /**
     * Wake lock that will be held until the service is destroyed.
     */
    private lateinit var wakeLock: PowerManager.WakeLock

    private var job: Job? = null

    // SY -->
    private val throttleManager = EHentaiThrottleManager()
    // SY <--

    /**
     * The progress of a backup restore
     */
    private var restoreProgress = 0

    /**
     * Amount of manga in Json file (needed for restore)
     */
    private var restoreAmount = 0

    /**
     * Mapping of source ID to source name from backup data
     */
    private var sourceMapping: Map<Long, String> = emptyMap()

    /**
     * List containing errors
     */
    private val errors = mutableListOf<Pair<Date, String>>()

    private lateinit var fullBackupManager: FullBackupManager
    private lateinit var notifier: BackupNotifier

    private val db: DatabaseHelper by injectLazy()

    private val trackManager: TrackManager by injectLazy()

    override fun onCreate() {
        super.onCreate()

        notifier = BackupNotifier(this)
        wakeLock = acquireWakeLock(javaClass.name)

        startForeground(Notifications.ID_RESTORE_PROGRESS, notifier.showRestoreProgress().build())
    }

    override fun stopService(name: Intent?): Boolean {
        destroyJob()
        return super.stopService(name)
    }

    override fun onDestroy() {
        destroyJob()
        super.onDestroy()
    }

    private fun destroyJob() {
        job?.cancel()
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    /**
     * This method needs to be implemented, but it's not used/needed.
     */
    override fun onBind(intent: Intent): IBinder? = null

    /**
     * Method called when the service receives an intent.
     *
     * @param intent the start intent from.
     * @param flags the flags of the command.
     * @param startId the start id of this command.
     * @return the start value of the command.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uri = intent?.getParcelableExtra<Uri>(BackupConst.EXTRA_URI) ?: return START_NOT_STICKY
        val online = intent.getBooleanExtra(BackupConst.EXTRA_TYPE, true)

        // SY -->
        throttleManager.resetThrottle()
        // SY <--

        // Cancel any previous job if needed.
        job?.cancel()
        val handler = CoroutineExceptionHandler { _, exception ->
            Timber.e(exception)
            writeErrorLog()

            notifier.showRestoreError(exception.message)

            stopSelf(startId)
        }
        job = GlobalScope.launch(handler) {
            if (!restoreBackup(uri, online)) {
                notifier.showRestoreError(getString(R.string.restoring_backup_canceled))
            }
        }
        job?.invokeOnCompletion {
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    /**
     * Restores data from backup file.
     *
     * @param uri backup file to restore
     */
    private fun restoreBackup(uri: Uri, online: Boolean): Boolean {
        val startTime = System.currentTimeMillis()

        // Initialize manager
        fullBackupManager = FullBackupManager(this)

        val backupString = contentResolver.openInputStream(uri)!!.source().gzip().buffer().use { it.readByteArray() }
        val backup = fullBackupManager.parser.decodeFromByteArray(BackupSerializer, backupString)

        restoreAmount = backup.backupManga.size + 1 /* SY --> */ + 1 /* SY <-- */ // +1 for categories, +1 for saved searches
        restoreProgress = 0
        errors.clear()

        // Restore categories
        if (backup.backupCategories.isNotEmpty()) {
            restoreCategories(backup.backupCategories)
        }

        // SY -->
        if (backup.backupSavedSearches.isNotEmpty()) {
            restoreSavedSearches(backup.backupSavedSearches)
        }
        // SY <--

        // Store source mapping for error messages
        sourceMapping = backup.backupExtensions.map { it.sourceId to it.name }.toMap()

        // Restore individual manga, sort by merged source so that merged source manga go last and merged references get the proper ids
        backup.backupManga /* SY --> */.sortedBy { it.source == MERGED_SOURCE_ID } /* SY <-- */.forEach {
            if (job?.isActive != true) {
                return false
            }

            restoreManga(it, backup.backupCategories, online)
        }

        val endTime = System.currentTimeMillis()
        val time = endTime - startTime

        val logFile = writeErrorLog()

        notifier.showRestoreComplete(time, errors.size, logFile.parent, logFile.name)
        return true
    }

    private fun restoreCategories(backupCategories: List<BackupCategory>) {
        db.inTransaction {
            fullBackupManager.restoreCategories(backupCategories)
        }

        restoreProgress += 1
        showRestoreProgress(restoreProgress, restoreAmount, getString(R.string.categories))
    }

    // SY -->
    private fun restoreSavedSearches(backupSavedSearches: List<BackupSavedSearch>) {
        fullBackupManager.restoreSavedSearches(backupSavedSearches)

        restoreProgress += 1
        showRestoreProgress(restoreProgress, restoreAmount, getString(R.string.saved_searches))
    }
    // SY <--

    private fun restoreManga(backupManga: BackupManga, backupCategories: List<BackupCategory>, online: Boolean) {
        var manga = backupManga.getMangaImpl()
        val chapters = backupManga.getChaptersImpl()
        val categories = backupManga.categories
        val history = backupManga.history
        val tracks = backupManga.getTrackingImpl()
        // SY -->
        val mergedMangaReferences = backupManga.mergedMangaReferences
        // SY <--

        // SY -->
        manga = EXHMigrations.migrateBackupEntry(manga)
        // SY <--

        try {
            val source = fullBackupManager.sourceManager.get(manga.source)
            if (source != null || !online) {
                restoreMangaData(manga, source, chapters, categories, history, tracks, backupCategories, mergedMangaReferences, online)
            } else {
                val sourceName = sourceMapping[manga.source] ?: manga.source.toString()
                errors.add(Date() to "${manga.title} - ${getString(R.string.source_not_found_name, sourceName)}")
            }
        } catch (e: Exception) {
            errors.add(Date() to "${manga.title} - ${e.message}")
        }

        restoreProgress += 1
        showRestoreProgress(restoreProgress, restoreAmount, manga.title)
    }

    /**
     * Returns a manga restore observable
     *
     * @param manga manga data from json
     * @param source source to get manga data from
     * @param chapters chapters data from json
     * @param categories categories data from json
     * @param history history data from json
     * @param tracks tracking data from json
     */
    private fun restoreMangaData(
        manga: Manga,
        source: Source?,
        chapters: List<Chapter>,
        categories: List<Int>,
        history: List<BackupHistory>,
        tracks: List<Track>,
        backupCategories: List<BackupCategory>,
        mergedMangaReferences: List<BackupMergedMangaReference>,
        online: Boolean
    ) {
        val dbManga = fullBackupManager.getMangaFromDatabase(manga)

        db.inTransaction {
            if (dbManga == null) {
                // Manga not in database
                restoreMangaFetch(source, manga, chapters, categories, history, tracks, backupCategories, mergedMangaReferences, online)
            } else { // Manga in database
                // Copy information from manga already in database
                fullBackupManager.restoreMangaNoFetch(manga, dbManga)
                // Fetch rest of manga information
                restoreMangaNoFetch(source, manga, chapters, categories, history, tracks, backupCategories, mergedMangaReferences, online)
            }
        }
    }

    /**
     * [Observable] that fetches manga information
     *
     * @param manga manga that needs updating
     * @param chapters chapters of manga that needs updating
     * @param categories categories that need updating
     */
    private fun restoreMangaFetch(
        source: Source?,
        manga: Manga,
        chapters: List<Chapter>,
        categories: List<Int>,
        history: List<BackupHistory>,
        tracks: List<Track>,
        backupCategories: List<BackupCategory>,
        mergedMangaReferences: List<BackupMergedMangaReference>,
        online: Boolean
    ) {
        fullBackupManager.restoreMangaFetchObservable(source, manga, online)
            .doOnError {
                errors.add(Date() to "${manga.title} - ${it.message}")
            }
            .filter { it.id != null }
            .flatMap {
                if (online && source != null) {
                    // SY -->
                    if (source !is MergedSource) {
                        chapterFetchObservable(source, it, chapters)
                            // Convert to the manga that contains new chapters.
                            .map { manga }
                    } else {
                        Observable.just(manga)
                    }
                    // SY <--
                } else {
                    fullBackupManager.restoreChaptersForMangaOffline(it, chapters)
                    Observable.just(manga)
                }
            }
            .doOnNext {
                restoreExtraForManga(it, categories, history, tracks, backupCategories, mergedMangaReferences)
            }
            .flatMap {
                trackingFetchObservable(it, tracks)
            }
            .subscribe()
    }

    private fun restoreMangaNoFetch(
        source: Source?,
        backupManga: Manga,
        chapters: List<Chapter>,
        categories: List<Int>,
        history: List<BackupHistory>,
        tracks: List<Track>,
        backupCategories: List<BackupCategory>,
        mergedMangaReferences: List<BackupMergedMangaReference>,
        online: Boolean
    ) {
        Observable.just(backupManga)
            .flatMap { manga ->
                if (online && source != null) {
                    if (/* SY --> */ source !is MergedSource && /* SY <-- */ !fullBackupManager.restoreChaptersForManga(manga, chapters)) {
                        chapterFetchObservable(source, manga, chapters)
                            .map { manga }
                    } else {
                        Observable.just(manga)
                    }
                } else {
                    fullBackupManager.restoreChaptersForMangaOffline(manga, chapters)
                    Observable.just(manga)
                }
            }
            .doOnNext {
                restoreExtraForManga(it, categories, history, tracks, backupCategories, mergedMangaReferences)
            }
            .flatMap { manga ->
                trackingFetchObservable(manga, tracks)
            }
            .subscribe()
    }

    private fun restoreExtraForManga(manga: Manga, categories: List<Int>, history: List<BackupHistory>, tracks: List<Track>, backupCategories: List<BackupCategory>, mergedMangaReferences: List<BackupMergedMangaReference>) {
        // Restore categories
        fullBackupManager.restoreCategoriesForManga(manga, categories, backupCategories)

        // Restore history
        fullBackupManager.restoreHistoryForManga(history)

        // Restore tracking
        fullBackupManager.restoreTrackForManga(manga, tracks)

        // SY -->
        // Restore merged manga references if its a merged manga
        fullBackupManager.restoreMergedMangaReferencesForManga(manga, mergedMangaReferences)
        // SY <--
    }

    /**
     * [Observable] that fetches chapter information
     *
     * @param source source of manga
     * @param manga manga that needs updating
     * @return [Observable] that contains manga
     */
    private fun chapterFetchObservable(source: Source, manga: Manga, chapters: List<Chapter>): Observable<Pair<List<Chapter>, List<Chapter>>> {
        return fullBackupManager.restoreChapterFetchObservable(source, manga, chapters /* SY --> */, throttleManager /* SY <-- */)
            // If there's any error, return empty update and continue.
            .onErrorReturn {
                val errorMessage = if (it is NoChaptersException) {
                    getString(R.string.no_chapters_error)
                } else {
                    it.message
                }
                errors.add(Date() to "${manga.title} - $errorMessage")
                Pair(emptyList(), emptyList())
            }
    }

    /**
     * [Observable] that refreshes tracking information
     * @param manga manga that needs updating.
     * @param tracks list containing tracks from restore file.
     * @return [Observable] that contains updated track item
     */
    private fun trackingFetchObservable(manga: Manga, tracks: List<Track>): Observable<Track> {
        return Observable.from(tracks)
            .flatMap { track ->
                val service = trackManager.getService(track.sync_id)
                if (service != null && service.isLogged) {
                    service.refresh(track)
                        .doOnNext { db.insertTrack(it).executeAsBlocking() }
                        .onErrorReturn {
                            errors.add(Date() to "${manga.title} - ${it.message}")
                            track
                        }
                } else {
                    errors.add(Date() to "${manga.title} - ${getString(R.string.tracker_not_logged_in, service?.name)}")
                    Observable.empty()
                }
            }
    }

    /**
     * Called to update dialog in [BackupConst]
     *
     * @param progress restore progress
     * @param amount total restoreAmount of manga
     * @param title title of restored manga
     */
    private fun showRestoreProgress(
        progress: Int,
        amount: Int,
        title: String
    ) {
        notifier.showRestoreProgress(title, progress, amount)
    }

    /**
     * Write errors to error log
     */
    private fun writeErrorLog(): File {
        try {
            if (errors.isNotEmpty()) {
                val destFile = File(externalCacheDir, "tachiyomi_restore.txt")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

                destFile.bufferedWriter().use { out ->
                    errors.forEach { (date, message) ->
                        out.write("[${sdf.format(date)}] $message\n")
                    }
                }
                return destFile
            }
        } catch (e: Exception) {
            // Empty
        }
        return File("")
    }
}
