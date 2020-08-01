package eu.kanade.tachiyomi.ui.browse.source

import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.app.Dialog
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.listItems
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.ControllerChangeType
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.preference.minusAssign
import eu.kanade.tachiyomi.data.preference.plusAssign
import eu.kanade.tachiyomi.databinding.SourceMainControllerBinding
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.base.controller.DialogController
import eu.kanade.tachiyomi.ui.base.controller.NucleusController
import eu.kanade.tachiyomi.ui.base.controller.requestPermissionsSafe
import eu.kanade.tachiyomi.ui.base.controller.withFadeTransaction
import eu.kanade.tachiyomi.ui.browse.BrowseController
import eu.kanade.tachiyomi.ui.browse.SourceDividerItemDecoration
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceController
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchController
import eu.kanade.tachiyomi.ui.browse.source.latest.LatestUpdatesController
import eu.kanade.tachiyomi.ui.category.sources.ChangeSourceCategoriesDialog
import eu.kanade.tachiyomi.util.system.toast
import exh.ui.smartsearch.SmartSearchController
import kotlinx.android.parcel.Parcelize
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import reactivecircus.flowbinding.appcompat.QueryTextEvent
import reactivecircus.flowbinding.appcompat.queryTextEvents
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * This controller shows and manages the different catalogues enabled by the user.
 * This controller should only handle UI actions, IO actions should be done by [SourcePresenter]
 * [SourceAdapter.OnSourceClickListener] call function data on browse item click.
 * [SourceAdapter.OnLatestClickListener] call function data on latest item click
 */
class SourceController(bundle: Bundle? = null) :
    NucleusController<SourceMainControllerBinding, SourcePresenter>(bundle),
    FlexibleAdapter.OnItemClickListener,
    FlexibleAdapter.OnItemLongClickListener,
    SourceAdapter.OnSourceClickListener,
    /*SY -->*/ ChangeSourceCategoriesDialog.Listener /*SY <--*/ {

    private val preferences: PreferencesHelper = Injekt.get()

    /**
     * Adapter containing sources.
     */
    private var adapter: SourceAdapter? = null

    // EXH -->
    private val smartSearchConfig: SmartSearchConfig? = args.getParcelable(SMART_SEARCH_CONFIG)

    private val mode = if (smartSearchConfig == null) Mode.CATALOGUE else Mode.SMART_SEARCH
    // EXH <--

    init {
        // SY -->
        setHasOptionsMenu(mode == Mode.CATALOGUE)
        // SY <--
    }

    override fun getTitle(): String? {
        // SY -->
        return when (mode) {
            Mode.CATALOGUE -> applicationContext?.getString(R.string.label_sources)
            Mode.SMART_SEARCH -> "Find in another source"
        }
        // SY <--
    }

    override fun createPresenter(): SourcePresenter {
        return SourcePresenter(/* SY --> */ controllerMode = mode /* SY <-- */)
    }

    /**
     * Initiate the view with [R.layout.source_main_controller].
     *
     * @param inflater used to load the layout xml.
     * @param container containing parent views.
     * @return inflated view.
     */
    override fun inflateView(inflater: LayoutInflater, container: ViewGroup): View {
        binding = SourceMainControllerBinding.inflate(inflater)
        return binding.root
    }

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)

        adapter = SourceAdapter(this)

        // Create recycler and set adapter.
        binding.recycler.layoutManager = LinearLayoutManager(view.context)
        binding.recycler.adapter = adapter
        binding.recycler.addItemDecoration(SourceDividerItemDecoration(view.context))
        adapter?.fastScroller = binding.fastScroller

        requestPermissionsSafe(arrayOf(WRITE_EXTERNAL_STORAGE), 301)

        // SY -->
        if (mode == Mode.CATALOGUE) {
            // Update list on extension changes (e.g. new installation)
            (parentController as BrowseController).extensionListUpdateRelay
                .subscribeUntilDestroy {
                    presenter.updateSources()
                }
        }
        // SY <--
    }

    override fun onDestroyView(view: View) {
        adapter = null
        super.onDestroyView(view)
    }

    override fun onChangeStarted(handler: ControllerChangeHandler, type: ControllerChangeType) {
        super.onChangeStarted(handler, type)
        if (type.isPush) {
            presenter.updateSources()
        }
    }

    override fun onItemClick(view: View, position: Int): Boolean {
        onItemClick(position)
        return false
    }

    private fun onItemClick(position: Int) {
        val item = adapter?.getItem(position) as? SourceItem ?: return
        val source = item.source
        // SY -->
        when (mode) {
            Mode.CATALOGUE -> {
                // Open the catalogue view.
                openSource(source, BrowseSourceController(source))
            }
            Mode.SMART_SEARCH -> router.pushController(
                SmartSearchController(
                    Bundle().apply {
                        putLong(SmartSearchController.ARG_SOURCE_ID, source.id)
                        putParcelable(SmartSearchController.ARG_SMART_SEARCH_CONFIG, smartSearchConfig)
                    }
                ).withFadeTransaction()
            )
        }
        // SY <--
    }

    override fun onItemLongClick(position: Int) {
        val activity = activity ?: return
        val item = adapter?.getItem(position) as? SourceItem ?: return

        val isPinned = item.header?.code?.equals(SourcePresenter.PINNED_KEY) ?: false

        val items = mutableListOf(
            Pair(
                activity.getString(if (isPinned) R.string.action_unpin else R.string.action_pin),
                { toggleSourcePin(item.source) }
            )
        )
        if (item.source !is LocalSource) {
            items.add(
                Pair(
                    activity.getString(R.string.action_disable),
                    { disableSource(item.source) }
                )
            )
        }

        // SY -->
        val isWatched = preferences.latestTabSources().get().contains(item.source.id.toString())

        if (item.source.supportsLatest) {
            items.add(
                Pair(
                    activity.getString(if (isWatched) R.string.unwatch else R.string.watch),
                    { watchCatalogue(item.source, isWatched) }
                )
            )
        }

        items.add(
            Pair(
                activity.getString(R.string.label_categories),
                { addToCategories(item.source) }
            )
        )
        // SY <--

        SourceOptionsDialog(item, items).showDialog(router)
    }

    private fun disableSource(source: Source) {
        preferences.disabledSources() += source.id.toString()

        presenter.updateSources()
    }

    private fun toggleSourcePin(source: Source) {
        val isPinned = source.id.toString() in preferences.pinnedSources().get()
        if (isPinned) {
            preferences.pinnedSources() -= source.id.toString()
        } else {
            preferences.pinnedSources() += source.id.toString()
        }

        presenter.updateSources()
    }

    // SY -->
    private fun watchCatalogue(source: Source, isWatched: Boolean) {
        if (isWatched) {
            preferences.latestTabSources() -= source.id.toString()
        } else {
            if (preferences.latestTabSources().get().size + 1 !in 0..5) {
                applicationContext?.toast(R.string.too_many_watched)
                return
            }
            preferences.latestTabSources() += source.id.toString()
        }
    }

    private fun addToCategories(source: Source) {
        val categories = preferences.sourcesTabCategories().get().toList().sortedBy { it.toLowerCase() }

        if (categories.isEmpty()) {
            applicationContext?.toast(R.string.no_source_categories)
            return
        }

        val preferenceSources = preferences.sourcesTabSourcesInCategories().get().toMutableList()
        val sources = preferenceSources.map { it.split("|")[0] }

        if (source.id.toString() in sources) {
            val preferenceSources = preferenceSources.map { it.split("|") }.filter { it[0] == source.id.toString() }.map { Pair(it[0], it[1]) }.toMutableList()

            val preselected = preferenceSources.map { category ->
                categories.indexOf(category.second)
            }.toTypedArray()

            ChangeSourceCategoriesDialog(this, source, categories, preselected)
                .showDialog(router)
        } else {
            ChangeSourceCategoriesDialog(this, source, categories, emptyArray())
                .showDialog(router)
        }
    }

    override fun updateCategoriesForSource(source: Source, categories: List<String>) {
        var preferenceSources = preferences.sourcesTabSourcesInCategories().get().toMutableList()
        val sources = preferenceSources.map { it.split("|")[0] }

        if (source.id.toString() in sources) {
            preferenceSources = preferenceSources
                .map { it.split("|") }
                .filter { it[0] != source.id.toString() }
                .map { it[0] + "|" + it[1] }.toMutableList()
        }

        categories.forEach {
            preferenceSources.add(source.id.toString() + "|" + it)
        }

        preferences.sourcesTabSourcesInCategories().set(
            preferenceSources.sorted().toSet()
        )
        presenter.updateSources()
    }
    // SY <--

    /**
     * Called when browse is clicked in [SourceAdapter]
     */
    override fun onBrowseClick(position: Int) {
        onItemClick(position)
    }

    /**
     * Called when latest is clicked in [SourceAdapter]
     */
    override fun onLatestClick(position: Int) {
        val item = adapter?.getItem(position) as? SourceItem ?: return
        openSource(item.source, LatestUpdatesController(item.source))
    }

    /**
     * Called when pin icon is clicked in [SourceAdapter]
     */
    override fun onPinClick(position: Int) {
        val item = adapter?.getItem(position) as? SourceItem ?: return
        toggleSourcePin(item.source)
    }

    /**
     * Opens a catalogue with the given controller.
     */
    private fun openSource(source: CatalogueSource, controller: BrowseSourceController) {
        preferences.lastUsedSource().set(source.id)
        parentController!!.router.pushController(controller.withFadeTransaction())
    }

    /**
     * Adds items to the options menu.
     *
     * @param menu menu containing options.
     * @param inflater used to load the menu xml.
     */
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        // Inflate menu
        inflater.inflate(R.menu.source_main, menu)

        // SY -->
        if (mode == Mode.SMART_SEARCH) {
            menu.findItem(R.id.action_search).isVisible = false
            menu.findItem(R.id.action_settings).isVisible = false
        }
        // SY <--

        // Initialize search option.
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.maxWidth = Int.MAX_VALUE

        // Change hint to show global search.
        searchView.queryHint = applicationContext?.getString(R.string.action_global_search_hint)

        // Create query listener which opens the global search view.
        searchView.queryTextEvents()
            .filterIsInstance<QueryTextEvent.QuerySubmitted>()
            .onEach { performGlobalSearch(it.queryText.toString()) }
            .launchIn(scope)
    }

    private fun performGlobalSearch(query: String) {
        parentController!!.router.pushController(
            GlobalSearchController(query).withFadeTransaction()
        )
    }

    /**
     * Called when an option menu item has been selected by the user.
     *
     * @param item The selected item.
     * @return True if this event has been consumed, false if it has not.
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            // Initialize option to open catalogue settings.
            R.id.action_settings -> {
                parentController!!.router.pushController(
                    SourceFilterController()
                        .withFadeTransaction()
                )
            }
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * Called to update adapter containing sources.
     */
    fun setSources(sources: List<IFlexible<*>>) {
        adapter?.updateDataSet(sources)
    }

    /**
     * Called to set the last used catalogue at the top of the view.
     */
    fun setLastUsedSource(item: SourceItem?) {
        adapter?.removeAllScrollableHeaders()
        if (item != null) {
            adapter?.addScrollableHeader(item)
            adapter?.addScrollableHeader(LangItem(SourcePresenter.LAST_USED_KEY))
        }
    }

    class SourceOptionsDialog(bundle: Bundle? = null) : DialogController(bundle) {

        private lateinit var item: SourceItem
        private lateinit var items: List<Pair<String, () -> Unit>>

        constructor(item: SourceItem, items: List<Pair<String, () -> Unit>>) : this() {
            this.item = item
            this.items = items
        }

        override fun onCreateDialog(savedViewState: Bundle?): Dialog {
            return MaterialDialog(activity!!)
                .title(text = item.source.toString())
                .listItems(
                    items = items.map { it.first },
                    waitForPositiveButton = false
                ) { dialog, which, _ ->
                    items[which].second()
                    dialog.dismiss()
                }
        }
    }

    // SY -->
    @Parcelize
    data class SmartSearchConfig(val origTitle: String, val origMangaId: Long? = null) : Parcelable

    enum class Mode {
        CATALOGUE,
        SMART_SEARCH
    }

    companion object {
        const val SMART_SEARCH_CONFIG = "SMART_SEARCH_CONFIG"
    }
    // SY <--
}
