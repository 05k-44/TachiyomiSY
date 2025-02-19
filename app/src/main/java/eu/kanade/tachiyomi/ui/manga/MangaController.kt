package eu.kanade.tachiyomi.ui.manga

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.ControllerChangeType
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.snackbar.Snackbar
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.SelectableAdapter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.glide.GlideApp
import eu.kanade.tachiyomi.data.glide.toMangaThumbnail
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.databinding.MangaControllerBinding
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.ui.base.controller.FabController
import eu.kanade.tachiyomi.ui.base.controller.NucleusController
import eu.kanade.tachiyomi.ui.base.controller.ToolbarLiftOnScrollController
import eu.kanade.tachiyomi.ui.base.controller.popControllerWithTag
import eu.kanade.tachiyomi.ui.base.controller.withFadeTransaction
import eu.kanade.tachiyomi.ui.browse.migration.advanced.design.PreMigrationController
import eu.kanade.tachiyomi.ui.browse.source.SourceController
import eu.kanade.tachiyomi.ui.browse.source.SourceController.Companion.SMART_SEARCH_SOURCE_TAG
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceController
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchController
import eu.kanade.tachiyomi.ui.browse.source.latest.LatestUpdatesController
import eu.kanade.tachiyomi.ui.library.ChangeMangaCategoriesDialog
import eu.kanade.tachiyomi.ui.library.ChangeMangaCoverDialog
import eu.kanade.tachiyomi.ui.library.LibraryController
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.main.offsetAppbarHeight
import eu.kanade.tachiyomi.ui.manga.chapter.ChapterItem
import eu.kanade.tachiyomi.ui.manga.chapter.ChaptersAdapter
import eu.kanade.tachiyomi.ui.manga.chapter.ChaptersSettingsSheet
import eu.kanade.tachiyomi.ui.manga.chapter.DeleteChaptersDialog
import eu.kanade.tachiyomi.ui.manga.chapter.DownloadCustomChaptersDialog
import eu.kanade.tachiyomi.ui.manga.chapter.MangaChaptersHeaderAdapter
import eu.kanade.tachiyomi.ui.manga.info.MangaInfoButtonsAdapter
import eu.kanade.tachiyomi.ui.manga.info.MangaInfoHeaderAdapter
import eu.kanade.tachiyomi.ui.manga.info.MangaInfoItemAdapter
import eu.kanade.tachiyomi.ui.manga.merged.EditMergedSettingsDialog
import eu.kanade.tachiyomi.ui.manga.track.TrackController
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.recent.history.HistoryController
import eu.kanade.tachiyomi.ui.recent.updates.UpdatesController
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.chapter.NoChaptersException
import eu.kanade.tachiyomi.util.hasCustomCover
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.getCoordinates
import eu.kanade.tachiyomi.util.view.shrinkOnScroll
import eu.kanade.tachiyomi.util.view.snack
import exh.MERGED_SOURCE_ID
import exh.isEhBasedSource
import exh.metadata.metadata.base.FlatMetadata
import exh.source.EnhancedHttpSource.Companion.getMainSource
import kotlinx.android.synthetic.main.main_activity.root_coordinator
import kotlinx.android.synthetic.main.main_activity.toolbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import reactivecircus.flowbinding.android.view.clicks
import reactivecircus.flowbinding.recyclerview.scrollEvents
import reactivecircus.flowbinding.swiperefreshlayout.refreshes
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import kotlin.math.min

class MangaController :
    NucleusController<MangaControllerBinding, MangaPresenter>,
    ToolbarLiftOnScrollController,
    FabController,
    ActionMode.Callback,
    FlexibleAdapter.OnItemClickListener,
    FlexibleAdapter.OnItemLongClickListener,
    ChangeMangaCoverDialog.Listener,
    ChangeMangaCategoriesDialog.Listener,
    DownloadCustomChaptersDialog.Listener,
    DeleteChaptersDialog.Listener {

    constructor(manga: Manga?, fromSource: Boolean = false, smartSearchConfig: SourceController.SmartSearchConfig? = null, update: Boolean = false) : super(
        Bundle().apply {
            putLong(MANGA_EXTRA, manga?.id ?: 0)
            putBoolean(FROM_SOURCE_EXTRA, fromSource)
            // SY -->
            putParcelable(SMART_SEARCH_CONFIG_EXTRA, smartSearchConfig)
            putBoolean(UPDATE_EXTRA, update)
            // SY <--
        }
    ) {
        this.manga = manga
        if (manga != null) {
            source = Injekt.get<SourceManager>().getOrStub(manga.source)
        }
    }

    constructor(mangaId: Long) : this(
        Injekt.get<DatabaseHelper>().getManga(mangaId).executeAsBlocking()
    )

    @Suppress("unused")
    constructor(bundle: Bundle) : this(bundle.getLong(MANGA_EXTRA))

    var manga: Manga? = null
        private set

    var source: Source? = null
        private set

    private val fromSource = args.getBoolean(FROM_SOURCE_EXTRA, false)

    private val preferences: PreferencesHelper by injectLazy()
    private val coverCache: CoverCache by injectLazy()

    private val toolbarTextColor by lazy { view!!.context.getResourceColor(R.attr.colorOnPrimary) }

    private var mangaInfoAdapter: MangaInfoHeaderAdapter? = null
    // SY >--
    private var mangaInfoItemAdapter: MangaInfoItemAdapter? = null
    private var mangaInfoButtonsAdapter: MangaInfoButtonsAdapter? = null
    private var mangaMetaInfoAdapter: RecyclerView.Adapter<*>? = null
    // SY <--
    private var chaptersHeaderAdapter: MangaChaptersHeaderAdapter? = null
    private var chaptersAdapter: ChaptersAdapter? = null

    // Sheet containing filter/sort/display items.
    private var settingsSheet: ChaptersSettingsSheet? = null

    private var actionFab: ExtendedFloatingActionButton? = null
    private var actionFabScrollListener: RecyclerView.OnScrollListener? = null

    // Snackbar to add manga to library after downloading chapter(s)
    private var addSnackbar: Snackbar? = null

    /**
     * Action mode for multiple selection.
     */
    private var actionMode: ActionMode? = null

    /**
     * Selected items. Used to restore selections after a rotation.
     */
    private val selectedChapters = mutableSetOf<ChapterItem>()

    private val isLocalSource by lazy { presenter.source.id == LocalSource.ID }

    private var lastClickPosition = -1

    private var isRefreshingInfo = false
    private var isRefreshingChapters = false

    // EXH -->
    val smartSearchConfig: SourceController.SmartSearchConfig? = args.getParcelable(
        SMART_SEARCH_CONFIG_EXTRA
    )

    private var editMangaDialog: EditMangaDialog? = null

    private var editMergedSettingsDialog: EditMergedSettingsDialog? = null

    private var currentAnimator: Animator? = null
    // EXH <--

    init {
        setHasOptionsMenu(true)
    }

    override fun getTitle(): String? {
        return manga?.title
    }

    override fun onChangeStarted(handler: ControllerChangeHandler, type: ControllerChangeType) {
        super.onChangeStarted(handler, type)

        // Hide toolbar title on enter
        if (type.isEnter) {
            updateToolbarTitleAlpha()
        }
    }

    override fun onChangeEnded(handler: ControllerChangeHandler, type: ControllerChangeType) {
        super.onChangeEnded(handler, type)
        if (manga == null || source == null) {
            activity?.toast(R.string.manga_not_in_db)
            router.popController(this)
        }
    }

    override fun createPresenter(): MangaPresenter {
        return MangaPresenter(
            manga!!,
            source!!
        )
    }

    override fun inflateView(inflater: LayoutInflater, container: ViewGroup): View {
        binding = MangaControllerBinding.inflate(inflater)
        return binding.root
    }

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)

        if (manga == null || source == null) return
        val adapters: MutableList<RecyclerView.Adapter<out RecyclerView.ViewHolder>?> = mutableListOf()

        // Init RecyclerView and adapter
        // SY -->
        mangaInfoAdapter = MangaInfoHeaderAdapter(this)

        adapters += mangaInfoAdapter

        val mainSource = presenter.source.getMainSource()
        if (mainSource is MetadataSource<*, *>) {
            mangaMetaInfoAdapter = mainSource.getDescriptionAdapter(this)
            mangaMetaInfoAdapter?.let { adapters += it }
        }
        mangaInfoItemAdapter = MangaInfoItemAdapter(this, fromSource)
        adapters += mangaInfoItemAdapter

        if (!preferences.recommendsInOverflow().get() || smartSearchConfig != null) {
            mangaInfoButtonsAdapter = MangaInfoButtonsAdapter(this)
            adapters += mangaInfoButtonsAdapter
        }

        chaptersHeaderAdapter = MangaChaptersHeaderAdapter(this)

        adapters += chaptersHeaderAdapter

        chaptersAdapter = ChaptersAdapter(this, view.context)

        adapters += chaptersAdapter

        binding.recycler.adapter = ConcatAdapter(adapters)
        // SY <--
        binding.recycler.layoutManager = LinearLayoutManager(view.context)
        binding.recycler.setHasFixedSize(true)
        chaptersAdapter?.fastScroller = binding.fastScroller

        actionFabScrollListener = actionFab?.shrinkOnScroll(binding.recycler)

        // Skips directly to chapters list if navigated to from the library
        binding.recycler.post {
            if (!fromSource && preferences.jumpToChapters()) {
                (binding.recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(1, 0)
            }

            // Delayed in case we need to jump to chapters
            binding.recycler.post {
                updateToolbarTitleAlpha()
            }
        }

        binding.recycler.scrollEvents()
            .onEach { updateToolbarTitleAlpha() }
            .launchIn(scope)

        binding.swipeRefresh.refreshes()
            .onEach {
                fetchMangaInfoFromSource(manualFetch = true)
                fetchChaptersFromSource(manualFetch = true)
            }
            .launchIn(scope)

        binding.actionToolbar.offsetAppbarHeight(activity!!)

        settingsSheet = ChaptersSettingsSheet(router, presenter) { group ->
            if (group is ChaptersSettingsSheet.Filter.FilterGroup) {
                updateFilterIconState()
                chaptersAdapter?.notifyDataSetChanged()
            }
        }

        updateFilterIconState()
    }

    private fun updateToolbarTitleAlpha(alpha: Int? = null) {
        val calculatedAlpha = when {
            // Specific alpha provided
            alpha != null -> alpha

            // First item isn't in view, full opacity
            ((binding.recycler.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition() > 0) -> 255

            // Based on scroll amount when first item is in view
            else -> min(binding.recycler.computeVerticalScrollOffset(), 255)
        }

        activity?.toolbar?.setTitleTextColor(
            Color.argb(
                calculatedAlpha,
                toolbarTextColor.red,
                toolbarTextColor.green,
                toolbarTextColor.blue
            )
        )
    }

    private fun updateFilterIconState() {
        chaptersHeaderAdapter?.setHasActiveFilters(settingsSheet?.filters?.hasActiveFilters() == true)
    }

    override fun configureFab(fab: ExtendedFloatingActionButton) {
        actionFab = fab
        fab.setText(R.string.action_start)
        fab.setIconResource(R.drawable.ic_play_arrow_24dp)
        fab.clicks()
            .onEach {
                val item = presenter.getNextUnreadChapter()
                if (item != null) {
                    // Create animation listener
                    val revealAnimationListener: Animator.AnimatorListener = object : AnimatorListenerAdapter() {
                        override fun onAnimationStart(animation: Animator?) {
                            openChapter(item.chapter, true)
                        }
                    }

                    // Get coordinates and start animation
                    actionFab?.getCoordinates()?.let { coordinates ->
                        if (!binding.revealView.showRevealEffect(
                                coordinates.x,
                                coordinates.y,
                                revealAnimationListener
                            )
                        ) {
                            openChapter(item.chapter)
                        }
                    }
                } else {
                    view?.context?.toast(R.string.no_next_chapter)
                }
            }
            .launchIn(scope)
    }

    override fun cleanupFab(fab: ExtendedFloatingActionButton) {
        actionFabScrollListener?.let { binding.recycler.removeOnScrollListener(it) }
        actionFab = null
    }

    override fun onDestroyView(view: View) {
        destroyActionModeIfNeeded()
        binding.actionToolbar.destroy()
        mangaInfoAdapter = null
        chaptersHeaderAdapter = null
        chaptersAdapter = null
        settingsSheet = null
        // SY -->
        mangaInfoButtonsAdapter = null
        mangaInfoItemAdapter = null
        mangaMetaInfoAdapter = null
        // SY <--
        addSnackbar?.dismiss()
        updateToolbarTitleAlpha(255)
        super.onDestroyView(view)
    }

    override fun onActivityResumed(activity: Activity) {
        if (view == null) return

        // Check if animation view is visible
        if (binding.revealView.isVisible) {
            // Show the unreveal effect
            actionFab?.getCoordinates()?.let { coordinates ->
                binding.revealView.hideRevealEffect(coordinates.x, coordinates.y, 1920)
            }
        }

        super.onActivityResumed(activity)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.manga, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        // Hide options for local manga
        menu.findItem(R.id.action_share).isVisible = !isLocalSource
        menu.findItem(R.id.download_group).isVisible = !isLocalSource

        // Hide options for non-library manga
        menu.findItem(R.id.action_edit_categories).isVisible = presenter.manga.favorite && presenter.getCategories().isNotEmpty()
        /* SY --> menu.findItem(R.id.action_edit_cover).isVisible = presenter.manga.favorite SY <-- */
        /* SY --> menu.findItem(R.id.action_migrate).isVisible = presenter.manga.favorite SY <-- */

        // SY -->
        if (presenter.manga.favorite) menu.findItem(R.id.action_edit).isVisible = true
        if (preferences.recommendsInOverflow().get()) menu.findItem(R.id.action_recommend).isVisible = true
        menu.findItem(R.id.action_merged).isVisible = presenter.manga.source == MERGED_SOURCE_ID
        menu.findItem(R.id.action_toggle_dedupe).isVisible = false // presenter.manga.source == MERGED_SOURCE_ID
        menu.findItem(R.id.action_merge).isVisible = presenter.manga.favorite
        // SY <--
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_share -> shareManga()
            R.id.download_next, R.id.download_next_5, R.id.download_next_10,
            R.id.download_custom, R.id.download_unread, R.id.download_all
            -> downloadChapters(item.itemId)

            // SY -->
            R.id.action_edit -> {
                editMangaDialog = EditMangaDialog(
                    this,
                    presenter.manga
                )
                editMangaDialog?.showDialog(router)
            }

            R.id.action_recommend -> {
                openRecommends()
            }
            R.id.action_merged -> {
                editMergedSettingsDialog = EditMergedSettingsDialog(
                    this,
                    presenter.manga
                )
                editMergedSettingsDialog?.showDialog(router)
            }
            R.id.action_toggle_dedupe -> {
                presenter.dedupe = !presenter.dedupe
                presenter.toggleDedupe()
            }
            R.id.action_merge -> {
                openSmartSearch()
            }
            // SY <--

            R.id.action_edit_categories -> onCategoriesClick()
            // SY --> R.id.action_edit_cover -> handleChangeCover() // SY <--
            // SY --> R.id.action_migrate -> migrateManga() // SY <--
        }
        return super.onOptionsItemSelected(item)
    }

    private fun updateRefreshing() {
        binding.swipeRefresh.isRefreshing = isRefreshingInfo || isRefreshingChapters
    }

    // Manga info - start

    // SY -->
    fun onNextMetaInfo(flatMetadata: FlatMetadata) {
        val mainSource = presenter.source.getMainSource()
        if (mainSource is MetadataSource<*, *>) {
            presenter.meta = flatMetadata.raise(mainSource.metaClass)
            mangaMetaInfoAdapter?.notifyDataSetChanged()
        }
    }
    // SY <--

    /**
     * Check if manga is initialized.
     * If true update header with manga information,
     * if false fetch manga information
     *
     * @param manga manga object containing information about manga.
     * @param source the source of the manga.
     */
    fun onNextMangaInfo(manga: Manga, source: Source) {
        if (manga.initialized) {
            // Update view.
            mangaInfoAdapter?.update(manga, source)
            mangaInfoItemAdapter?.update(manga, source, presenter.meta)

            val mangaThumbnail = manga.toMangaThumbnail()
            GlideApp.with(activity!!)
                .load(mangaThumbnail)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .fitCenter()
                .into(binding.expandedImage)
        } else {
            // Initialize manga.
            fetchMangaInfoFromSource()
        }
    }

    /**
     * Start fetching manga information from source.
     */
    private fun fetchMangaInfoFromSource(manualFetch: Boolean = false) {
        isRefreshingInfo = true
        updateRefreshing()

        // Call presenter and start fetching manga information
        presenter.fetchMangaFromSource(manualFetch)
    }

    fun onFetchMangaInfoDone() {
        isRefreshingInfo = false
        updateRefreshing()
    }

    fun onFetchMangaInfoError(error: Throwable) {
        isRefreshingInfo = false
        updateRefreshing()
        activity?.toast(error.message)
    }

    fun onTrackingCount(trackCount: Int) {
        mangaInfoAdapter?.setTrackingCount(trackCount)
    }

    fun openMangaInWebView() {
        val source = presenter.source as? HttpSource ?: return

        val url = try {
            source.mangaDetailsRequest(presenter.manga).url.toString()
        } catch (e: Exception) {
            return
        }

        val activity = activity ?: return
        val intent = WebViewActivity.newIntent(activity, url, source.id, presenter.manga.title)
        startActivity(intent)
    }

    fun shareManga() {
        val context = view?.context ?: return

        val source = presenter.source as? HttpSource ?: return
        try {
            val url = source.mangaDetailsRequest(presenter.manga).url.toString()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }
            startActivity(Intent.createChooser(intent, context.getString(R.string.action_share)))
        } catch (e: Exception) {
            context.toast(e.message)
        }
    }

    fun onFavoriteClick() {
        val manga = presenter.manga

        if (manga.favorite) {
            toggleFavorite()
            activity?.toast(activity?.getString(R.string.manga_removed_library))
            activity?.invalidateOptionsMenu()
        } else {
            addToLibrary(manga)
        }
    }

    fun onTrackingClick() {
        router.pushController(TrackController(manga).withFadeTransaction())
    }

    private fun addToLibrary(manga: Manga) {
        val categories = presenter.getCategories()
        val defaultCategoryId = preferences.defaultCategory()
        val defaultCategory = categories.find { it.id == defaultCategoryId }

        when {
            // Default category set
            defaultCategory != null -> {
                toggleFavorite()
                presenter.moveMangaToCategory(manga, defaultCategory)
                activity?.toast(activity?.getString(R.string.manga_added_library))
                activity?.invalidateOptionsMenu()
            }

            // Automatic 'Default' or no categories
            defaultCategoryId == 0 || categories.isEmpty() -> {
                toggleFavorite()
                presenter.moveMangaToCategory(manga, null)
                activity?.toast(activity?.getString(R.string.manga_added_library))
                activity?.invalidateOptionsMenu()
            }

            // Choose a category
            else -> {
                val ids = presenter.getMangaCategoryIds(manga)
                val preselected = ids.mapNotNull { id ->
                    categories.indexOfFirst { it.id == id }.takeIf { it != -1 }
                }.toTypedArray()

                ChangeMangaCategoriesDialog(this, listOf(manga), categories, preselected)
                    .showDialog(router)
            }
        }
    }

    // SY -->
    fun changeCover() {
        if (manga?.favorite == true) {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "image/*"
            startActivityForResult(
                Intent.createChooser(
                    intent,
                    resources?.getString(R.string.action_edit_cover)
                ),
                REQUEST_EDIT_MANGA_COVER
            )
        } else {
            activity?.toast(R.string.notification_first_add_to_library)
        }
    }

    fun setRefreshing() {
        isRefreshingInfo = true
        updateRefreshing()
    }
    // SY <--

    // EXH -->
    fun openSmartSearch() {
        val smartSearchConfig = SourceController.SmartSearchConfig(presenter.manga.title, presenter.manga.id)

        router?.pushController(
            SourceController(
                Bundle().apply {
                    putParcelable(SourceController.SMART_SEARCH_CONFIG, smartSearchConfig)
                }
            ).withFadeTransaction().tag(SMART_SEARCH_SOURCE_TAG)
        )
    }

    suspend fun mergeWithAnother() {
        try {
            val mergedManga = withContext(Dispatchers.IO + NonCancellable) {
                presenter.smartSearchMerge(presenter.manga, smartSearchConfig?.origMangaId!!)
            }

            router?.popControllerWithTag(SMART_SEARCH_SOURCE_TAG)
            router?.popCurrentController()
            router?.replaceTopController(
                MangaController(
                    mergedManga,
                    true,
                    update = true
                ).withFadeTransaction()
            )
            applicationContext?.toast("Manga merged!")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            else {
                applicationContext?.toast("Failed to merge manga: ${e.message}")
            }
        }
    }
    // EXH <--

    // AZ -->
    fun openRecommends() {
        val recommendsConfig = BrowseSourceController.RecommendsConfig(presenter.manga.originalTitle, presenter.manga.id)

        router?.pushController(
            BrowseSourceController(
                Bundle().apply {
                    putParcelable(BrowseSourceController.RECOMMENDS_CONFIG, recommendsConfig)
                }
            ).withFadeTransaction()
        )
    }
    // AZ <--

    /**
     * Toggles the favorite status and asks for confirmation to delete downloaded chapters.
     */
    private fun toggleFavorite() {
        val isNowFavorite = presenter.toggleFavorite()
        if (activity != null && !isNowFavorite && presenter.hasDownloads()) {
            activity!!.root_coordinator?.snack(activity!!.getString(R.string.delete_downloads_for_manga)) {
                setAction(R.string.action_delete) {
                    presenter.deleteDownloads()
                }
            }
        }

        mangaInfoAdapter?.notifyDataSetChanged()
    }

    fun onCategoriesClick() {
        val manga = presenter.manga
        val categories = presenter.getCategories()

        val ids = presenter.getMangaCategoryIds(manga)
        val preselected = ids.mapNotNull { id ->
            categories.indexOfFirst { it.id == id }.takeIf { it != -1 }
        }.toTypedArray()

        ChangeMangaCategoriesDialog(this, listOf(manga), categories, preselected)
            .showDialog(router)
    }

    override fun updateCategoriesForMangas(mangas: List<Manga>, categories: List<Category>) {
        val manga = mangas.firstOrNull() ?: return

        if (!manga.favorite) {
            toggleFavorite()
            activity?.toast(activity?.getString(R.string.manga_added_library))
            activity?.invalidateOptionsMenu()
        }

        presenter.moveMangaToCategories(manga, categories)
    }

    // SY -->
    fun onThumbnailClick(thumbView: ImageView) {
        if (!presenter.manga.initialized) return
        currentAnimator?.cancel()

        val startBoundsInt = Rect()
        val finalBoundsInt = Rect()
        val globalOffset = Point()

        thumbView.getGlobalVisibleRect(startBoundsInt)
        binding.root.getGlobalVisibleRect(finalBoundsInt, globalOffset)
        startBoundsInt.offset(-globalOffset.x, -globalOffset.y)
        finalBoundsInt.offset(-globalOffset.x, -globalOffset.y)

        val startBounds = RectF(startBoundsInt)
        val finalBounds = RectF(finalBoundsInt)

        val startScale: Float
        if ((finalBounds.width() / finalBounds.height() > startBounds.width() / startBounds.height())) {
            startScale = startBounds.height() / finalBounds.height()
            val startWidth: Float = startScale * finalBounds.width()
            val deltaWidth: Float = (startWidth - startBounds.width()) / 2
            startBounds.left -= deltaWidth.toInt()
            startBounds.right += deltaWidth.toInt()
        } else {
            startScale = startBounds.width() / finalBounds.width()
            val startHeight: Float = startScale * finalBounds.height()
            val deltaHeight: Float = (startHeight - startBounds.height()) / 2f
            startBounds.top -= deltaHeight.toInt()
            startBounds.bottom += deltaHeight.toInt()
        }
        thumbView.alpha = 0f
        actionFab?.isVisible = false
        binding.expandedImage.isVisible = true

        binding.expandedImage.pivotX = 0f
        binding.expandedImage.pivotY = 0f

        currentAnimator = AnimatorSet().apply {
            play(
                ObjectAnimator.ofFloat(
                    binding.expandedImage,
                    View.X,
                    startBounds.left,
                    finalBounds.left
                )
            ).apply {
                with(ObjectAnimator.ofFloat(binding.expandedImage, View.Y, startBounds.top, finalBounds.top))
                with(ObjectAnimator.ofFloat(binding.expandedImage, View.SCALE_X, startScale, 1f))
                with(ObjectAnimator.ofFloat(binding.expandedImage, View.SCALE_Y, startScale, 1f))
            }
            duration = resources?.getInteger(android.R.integer.config_shortAnimTime)?.toLong() ?: 150L
            interpolator = DecelerateInterpolator()
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        currentAnimator = null
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        currentAnimator = null
                    }
                }
            )
            start()
        }

        binding.expandedImage.clicks()
            .onEach {
                currentAnimator?.cancel()

                currentAnimator = AnimatorSet().apply {
                    play(ObjectAnimator.ofFloat(binding.expandedImage, View.X, startBounds.left)).apply {
                        with(ObjectAnimator.ofFloat(binding.expandedImage, View.Y, startBounds.top))
                        with(ObjectAnimator.ofFloat(binding.expandedImage, View.SCALE_X, startScale))
                        with(ObjectAnimator.ofFloat(binding.expandedImage, View.SCALE_Y, startScale))
                    }
                    duration = resources?.getInteger(android.R.integer.config_shortAnimTime)?.toLong() ?: 150L
                    interpolator = DecelerateInterpolator()
                    addListener(
                        object : AnimatorListenerAdapter() {

                            override fun onAnimationEnd(animation: Animator) {
                                thumbView.alpha = 1f
                                binding.expandedImage.isVisible = false
                                actionFab?.isVisible = true
                                currentAnimator = null
                            }

                            override fun onAnimationCancel(animation: Animator) {
                                thumbView.alpha = 1f
                                binding.expandedImage.isVisible = false
                                actionFab?.isVisible = true
                                currentAnimator = null
                            }
                        }
                    )
                    start()
                }
            }
            .launchIn(scope)
    }
    // SY <--

    /**
     * Perform a global search using the provided query.
     *
     * @param query the search query to pass to the search controller
     */
    fun performGlobalSearch(query: String) {
        router.pushController(GlobalSearchController(query).withFadeTransaction())
    }

    /**
     * Perform a search using the provided query.
     *
     * @param query the search query to the parent controller
     */
    fun performSearch(query: String) {
        if (router.backstackSize < 2) {
            return
        }

        when (val previousController = router.backstack[router.backstackSize - 2].controller()) {
            is LibraryController -> {
                router.handleBack()
                previousController.search(query)
            }
            is UpdatesController,
            is HistoryController -> {
                // Manually navigate to LibraryController
                router.handleBack()
                (router.activity as MainActivity).setSelectedNavItem(R.id.nav_library)
                val controller = router.getControllerWithTag(R.id.nav_library.toString()) as LibraryController
                controller.search(query)
            }
            is LatestUpdatesController -> {
                // Search doesn't currently work in source Latest view
                return
            }
            is BrowseSourceController -> {
                router.handleBack()
                previousController.searchWithQuery(query)
            }
        }
    }

    private fun handleChangeCover() {
        val manga = manga ?: return
        if (manga.hasCustomCover(coverCache)) {
            showEditCoverDialog(manga)
        } else {
            openMangaCoverPicker(manga)
        }
    }

    /**
     * Edit custom cover for selected manga.
     */
    private fun showEditCoverDialog(manga: Manga) {
        ChangeMangaCoverDialog(this, manga).showDialog(router)
    }

    override fun openMangaCoverPicker(manga: Manga) {
        if (manga.favorite) {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "image/*"
            startActivityForResult(
                Intent.createChooser(
                    intent,
                    resources?.getString(R.string.file_select_cover)
                ),
                REQUEST_IMAGE_OPEN
            )
        } else {
            activity?.toast(R.string.notification_first_add_to_library)
        }

        destroyActionModeIfNeeded()
    }

    override fun deleteMangaCover(manga: Manga) {
        presenter.deleteCustomCover(manga)
        mangaInfoAdapter?.notifyDataSetChanged()
        destroyActionModeIfNeeded()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_IMAGE_OPEN) {
            val dataUri = data?.data
            if (dataUri == null || resultCode != Activity.RESULT_OK) return
            val activity = activity ?: return
            presenter.editCover(manga!!, activity, dataUri)
        }
        // SY -->
        if (requestCode == REQUEST_EDIT_MANGA_COVER) {
            if (data == null || resultCode != Activity.RESULT_OK) return
            val activity = activity ?: return
            try {
                val uri = data.data ?: return
                if (editMangaDialog != null) editMangaDialog?.updateCover(uri)
                else {
                    presenter.editCoverWithStream(uri)
                }
            } catch (error: IOException) {
                activity.toast(R.string.notification_cover_update_failed)
                Timber.e(error)
            }
        }
        // SY <--
    }

    fun onSetCoverSuccess() {
        mangaInfoAdapter?.notifyDataSetChanged()
        activity?.toast(R.string.cover_updated)
    }

    fun onSetCoverError(error: Throwable) {
        activity?.toast(R.string.notification_cover_update_failed)
        Timber.e(error)
    }

    /**
     * Initiates source migration for the specific manga.
     */
    /* SY private */fun migrateManga() {
        // SY -->
        PreMigrationController.navigateToMigration(
            preferences.skipPreMigration().get(),
            router,
            listOf(presenter.manga.id!!)
        )
        // SY <--
    }

    // Manga info - end

    // Chapters list - start

    fun onNextChapters(chapters: List<ChapterItem>) {
        // If the list is empty and it hasn't requested previously, fetch chapters from source
        // We use presenter chapters instead because they are always unfiltered
        if (!presenter.hasRequested && presenter.chapters.isEmpty()) {
            fetchChaptersFromSource()
        }

        val chaptersHeader = chaptersHeaderAdapter ?: return
        chaptersHeader.setNumChapters(chapters.size)

        val adapter = chaptersAdapter ?: return
        adapter.updateDataSet(chapters)

        if (selectedChapters.isNotEmpty()) {
            adapter.clearSelection() // we need to start from a clean state, index may have changed
            createActionModeIfNeeded()
            selectedChapters.forEach { item ->
                val position = adapter.indexOf(item)
                if (position != -1 && !adapter.isSelected(position)) {
                    adapter.toggleSelection(position)
                }
            }
            actionMode?.invalidate()
        }

        val context = view?.context
        if (context != null && chapters.any { it.read }) {
            actionFab?.text = context.getString(R.string.action_resume)
        }
    }

    private fun fetchChaptersFromSource(manualFetch: Boolean = false) {
        isRefreshingChapters = true
        updateRefreshing()

        presenter.fetchChaptersFromSource(manualFetch)
    }

    fun onFetchChaptersDone() {
        isRefreshingChapters = false
        updateRefreshing()
    }

    fun onFetchChaptersError(error: Throwable) {
        isRefreshingChapters = false
        updateRefreshing()
        if (error is NoChaptersException) {
            activity?.toast(activity?.getString(R.string.no_chapters_error))
        } else {
            activity?.toast(error.message)
        }
    }

    fun onChapterStatusChange(download: Download) {
        chaptersAdapter?.currentItems?.find { it.id == download.chapter.id }?.let {
            chaptersAdapter?.updateItem(it)
            chaptersAdapter?.notifyDataSetChanged()
        }
    }

    fun openChapter(chapter: Chapter, hasAnimation: Boolean = false) {
        val activity = activity ?: return
        val intent = ReaderActivity.newIntent(activity, presenter.manga, chapter)
        if (hasAnimation) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        startActivity(intent)
    }

    override fun onItemClick(view: View?, position: Int): Boolean {
        val adapter = chaptersAdapter ?: return false
        val item = adapter.getItem(position) ?: return false
        return if (actionMode != null && adapter.mode == SelectableAdapter.Mode.MULTI) {
            lastClickPosition = position
            toggleSelection(position)
            true
        } else {
            openChapter(item.chapter)
            false
        }
    }

    override fun onItemLongClick(position: Int) {
        createActionModeIfNeeded()
        when {
            lastClickPosition == -1 -> setSelection(position)
            lastClickPosition > position ->
                for (i in position until lastClickPosition)
                    setSelection(i)
            lastClickPosition < position ->
                for (i in lastClickPosition + 1..position)
                    setSelection(i)
            else -> setSelection(position)
        }
        lastClickPosition = position
        chaptersAdapter?.notifyDataSetChanged()
    }

    fun showSettingsSheet() {
        settingsSheet?.show()
    }

    // SELECTIONS & ACTION MODE

    private fun toggleSelection(position: Int) {
        val adapter = chaptersAdapter ?: return
        val item = adapter.getItem(position) ?: return
        adapter.toggleSelection(position)
        adapter.notifyDataSetChanged()
        if (adapter.isSelected(position)) {
            selectedChapters.add(item)
        } else {
            selectedChapters.remove(item)
        }
        actionMode?.invalidate()
    }

    private fun setSelection(position: Int) {
        val adapter = chaptersAdapter ?: return
        val item = adapter.getItem(position) ?: return
        if (!adapter.isSelected(position)) {
            adapter.toggleSelection(position)
            selectedChapters.add(item)
            actionMode?.invalidate()
        }
    }

    private fun getSelectedChapters(): List<ChapterItem> {
        val adapter = chaptersAdapter ?: return emptyList()
        return adapter.selectedPositions.mapNotNull { adapter.getItem(it) }
    }

    private fun createActionModeIfNeeded() {
        if (actionMode == null) {
            actionMode = (activity as? AppCompatActivity)?.startSupportActionMode(this)
            binding.actionToolbar.show(
                actionMode!!,
                R.menu.chapter_selection
            ) { onActionItemClicked(it!!) }
        }
    }

    private fun destroyActionModeIfNeeded() {
        lastClickPosition = -1
        actionMode?.finish()
    }

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        mode.menuInflater.inflate(R.menu.generic_selection, menu)
        chaptersAdapter?.mode = SelectableAdapter.Mode.MULTI
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        val count = chaptersAdapter?.selectedItemCount ?: 0
        if (count == 0) {
            // Destroy action mode if there are no items selected.
            destroyActionModeIfNeeded()
        } else {
            mode.title = count.toString()

            val chapters = getSelectedChapters()
            binding.actionToolbar.findItem(R.id.action_download)?.isVisible = !isLocalSource && chapters.any { !it.isDownloaded }
            binding.actionToolbar.findItem(R.id.action_delete)?.isVisible = !isLocalSource && chapters.any { it.isDownloaded }
            binding.actionToolbar.findItem(R.id.action_bookmark)?.isVisible = chapters.any { !it.chapter.bookmark }
            binding.actionToolbar.findItem(R.id.action_remove_bookmark)?.isVisible = chapters.all { it.chapter.bookmark }
            binding.actionToolbar.findItem(R.id.action_mark_as_read)?.isVisible = chapters.any { !it.chapter.read }
            binding.actionToolbar.findItem(R.id.action_mark_as_unread)?.isVisible = chapters.all { it.chapter.read }

            // Hide FAB to avoid interfering with the bottom action toolbar
            // actionFab?.hide()
            actionFab?.isVisible = false
        }
        return false
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        return onActionItemClicked(item)
    }

    private fun onActionItemClicked(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_select_all -> selectAll()
            R.id.action_select_inverse -> selectInverse()
            R.id.action_download -> downloadChapters(getSelectedChapters())
            R.id.action_delete -> showDeleteChaptersConfirmationDialog()
            R.id.action_bookmark -> bookmarkChapters(getSelectedChapters(), true)
            R.id.action_remove_bookmark -> bookmarkChapters(getSelectedChapters(), false)
            R.id.action_mark_as_read -> markAsRead(getSelectedChapters())
            R.id.action_mark_as_unread -> markAsUnread(getSelectedChapters())
            R.id.action_mark_previous_as_read -> markPreviousAsRead(getSelectedChapters())
            else -> return false
        }
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        binding.actionToolbar.hide()
        chaptersAdapter?.mode = SelectableAdapter.Mode.SINGLE
        chaptersAdapter?.clearSelection()
        selectedChapters.clear()
        actionMode = null

        // TODO: there seems to be a bug in MaterialComponents where the [ExtendedFloatingActionButton]
        // fails to show up properly
        // actionFab?.show()
        actionFab?.isVisible = true
    }

    override fun onDetach(view: View) {
        destroyActionModeIfNeeded()
        super.onDetach(view)
    }

    // SELECTION MODE ACTIONS

    private fun selectAll() {
        val adapter = chaptersAdapter ?: return
        adapter.selectAll()
        selectedChapters.addAll(adapter.items)
        actionMode?.invalidate()
    }

    private fun selectInverse() {
        val adapter = chaptersAdapter ?: return

        selectedChapters.clear()
        for (i in 0..adapter.itemCount) {
            adapter.toggleSelection(i)
        }
        selectedChapters.addAll(adapter.selectedPositions.mapNotNull { adapter.getItem(it) })

        actionMode?.invalidate()
        adapter.notifyDataSetChanged()
    }

    private fun markAsRead(chapters: List<ChapterItem>) {
        presenter.markChaptersRead(chapters, true)
        destroyActionModeIfNeeded()
    }

    private fun markAsUnread(chapters: List<ChapterItem>) {
        presenter.markChaptersRead(chapters, false)
        destroyActionModeIfNeeded()
    }

    private fun downloadChapters(chapters: List<ChapterItem>) {
        val view = view
        val manga = presenter.manga
        presenter.downloadChapters(chapters)
        if (view != null && !manga.favorite) {
            addSnackbar = activity!!.root_coordinator?.snack(view.context.getString(R.string.snack_add_to_library), Snackbar.LENGTH_INDEFINITE) {
                setAction(R.string.action_add) {
                    addToLibrary(manga)
                }
            }
        }
        destroyActionModeIfNeeded()
    }

    private fun showDeleteChaptersConfirmationDialog() {
        DeleteChaptersDialog(this).showDialog(router)
    }

    override fun deleteChapters() {
        deleteChapters(getSelectedChapters())
    }

    private fun markPreviousAsRead(chapters: List<ChapterItem>) {
        val adapter = chaptersAdapter ?: return
        val prevChapters = if (presenter.sortDescending()) adapter.items.reversed() else adapter.items
        val chapterPos = prevChapters.indexOf(chapters.last())
        if (chapterPos != -1) {
            markAsRead(prevChapters.take(chapterPos))
        }
        destroyActionModeIfNeeded()
    }

    private fun bookmarkChapters(chapters: List<ChapterItem>, bookmarked: Boolean) {
        presenter.bookmarkChapters(chapters, bookmarked)
        destroyActionModeIfNeeded()
    }

    fun deleteChapters(chapters: List<ChapterItem>) {
        if (chapters.isEmpty()) return

        presenter.deleteChapters(chapters)
        destroyActionModeIfNeeded()
    }

    fun onChaptersDeleted(chapters: List<ChapterItem>) {
        // this is needed so the downloaded text gets removed from the item
        chapters.forEach {
            chaptersAdapter?.updateItem(it)
        }
        chaptersAdapter?.notifyDataSetChanged()
    }

    fun onChaptersDeletedError(error: Throwable) {
        Timber.e(error)
    }

    // OVERFLOW MENU DIALOGS

    private fun getUnreadChaptersSorted() = /* SY --> */ if (presenter.source.isEhBasedSource()) presenter.chapters
        .filter { !it.read && it.status == Download.NOT_DOWNLOADED }
        .distinctBy { it.name }
        .sortedBy { it.source_order }
    else /* SY <-- */ presenter.chapters
        .filter { !it.read && it.status == Download.NOT_DOWNLOADED }
        .distinctBy { it.name }
        .sortedByDescending { it.source_order }

    private fun downloadChapters(choice: Int) {
        val chaptersToDownload = when (choice) {
            R.id.download_next -> getUnreadChaptersSorted().take(1)
            R.id.download_next_5 -> getUnreadChaptersSorted().take(5)
            R.id.download_next_10 -> getUnreadChaptersSorted().take(10)
            R.id.download_custom -> {
                showCustomDownloadDialog()
                return
            }
            R.id.download_unread -> presenter.chapters.filter { !it.read }
            R.id.download_all -> presenter.chapters
            else -> emptyList()
        }
        if (chaptersToDownload.isNotEmpty()) {
            downloadChapters(chaptersToDownload)
        }
        destroyActionModeIfNeeded()
    }

    private fun showCustomDownloadDialog() {
        DownloadCustomChaptersDialog(
            this,
            presenter.chapters.size
        ).showDialog(router)
    }

    override fun downloadCustomChapters(amount: Int) {
        val chaptersToDownload = getUnreadChaptersSorted().take(amount)
        if (chaptersToDownload.isNotEmpty()) {
            downloadChapters(chaptersToDownload)
        }
    }

    // Chapters list - end

    companion object {
        const val FROM_SOURCE_EXTRA = "from_source"
        const val MANGA_EXTRA = "manga"

        // EXH -->
        const val UPDATE_EXTRA = "update"
        const val SMART_SEARCH_CONFIG_EXTRA = "smartSearchConfig"
        // EXH <--

        /**
         * Key to change the cover of a manga in [onActivityResult].
         */
        const val REQUEST_IMAGE_OPEN = 101

        const val REQUEST_EDIT_MANGA_COVER = 201
    }
}
