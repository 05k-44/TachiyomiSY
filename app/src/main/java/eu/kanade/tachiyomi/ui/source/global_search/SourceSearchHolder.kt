package eu.kanade.tachiyomi.ui.source.global_search

import android.view.View
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.ui.base.holder.BaseFlexibleViewHolder
import eu.kanade.tachiyomi.util.view.gone
import eu.kanade.tachiyomi.util.view.visible
import kotlinx.android.synthetic.main.source_global_search_controller_card.*

/**
 * Holder that binds the [SourceSearchItem] containing catalogue cards.
 *
 * @param view view of [SourceSearchItem]
 * @param adapter instance of [SourceSearchAdapter]
 */
class SourceSearchHolder(view: View, val adapter: SourceSearchAdapter) :
        BaseFlexibleViewHolder(view, adapter) {

    /**
     * Adapter containing manga from search results.
     */
    private val mangaAdapter = SourceSearchCardAdapter(adapter.controller)

    private var lastBoundResults: List<SourceSearchCardItem>? = null

    init {
        // Set layout horizontal.
        recycler.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(view.context, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
        recycler.adapter = mangaAdapter
    }

    /**
     * Show the loading of source search result.
     *
     * @param item item of card.
     */
    fun bind(item: SourceSearchItem) {
        val source = item.source
        val results = item.results

        val titlePrefix = if (item.highlighted) "▶" else ""
        val langSuffix = if (source.lang.isNotEmpty()) " (${source.lang})" else ""

        // Set Title with country code if available.
        title.text = titlePrefix + source.name + langSuffix

        when {
            results == null -> {
                progress.visible()
                showHolder()
            }
            results.isEmpty() -> {
                progress.gone()
                no_results.visible()
                source_card.gone()
            }
            else -> {
                progress.gone()
                showHolder()
            }
        }
        if (results !== lastBoundResults) {
            mangaAdapter.updateDataSet(results)
            lastBoundResults = results
        }
    }

    /**
     * Called from the presenter when a manga is initialized.
     *
     * @param manga the initialized manga.
     */
    fun setImage(manga: Manga) {
        getHolder(manga)?.setImage(manga)
    }

    /**
     * Returns the view holder for the given manga.
     *
     * @param manga the manga to find.
     * @return the holder of the manga or null if it's not bound.
     */
    private fun getHolder(manga: Manga): SourceSearchCardHolder? {
        mangaAdapter.allBoundViewHolders.forEach { holder ->
            val item = mangaAdapter.getItem(holder.adapterPosition)
            if (item != null && item.manga.id!! == manga.id!!) {
                return holder as SourceSearchCardHolder
            }
        }

        return null
    }

    private fun showHolder() {
        source_card.visible()
        no_results.gone()
    }
}
