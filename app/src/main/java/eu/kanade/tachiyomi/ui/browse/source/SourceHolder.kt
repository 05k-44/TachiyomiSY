package eu.kanade.tachiyomi.ui.browse.source

import android.view.View
import androidx.core.view.isVisible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.icon
import eu.kanade.tachiyomi.ui.base.holder.BaseFlexibleViewHolder
import eu.kanade.tachiyomi.ui.base.holder.SlicedHolder
import io.github.mthli.slice.Slice
import kotlinx.android.synthetic.main.source_main_controller_card_item.card
import kotlinx.android.synthetic.main.source_main_controller_card_item.image
import kotlinx.android.synthetic.main.source_main_controller_card_item.pin
import kotlinx.android.synthetic.main.source_main_controller_card_item.source_browse
import kotlinx.android.synthetic.main.source_main_controller_card_item.source_latest
import kotlinx.android.synthetic.main.source_main_controller_card_item.title

class SourceHolder(view: View, override val adapter: SourceAdapter /* SY --> */, val showButtons: Boolean /* SY <-- */) :
    BaseFlexibleViewHolder(view, adapter),
    SlicedHolder {

    override val slice = Slice(card).apply {
        setColor(adapter.cardBackground)
    }

    override val viewToSlice: View
        get() = card

    init {
        source_browse.setOnClickListener {
            adapter.clickListener.onBrowseClick(bindingAdapterPosition)
        }

        source_latest.setOnClickListener {
            adapter.clickListener.onLatestClick(bindingAdapterPosition)
        }

        pin.setOnClickListener {
            adapter.clickListener.onPinClick(bindingAdapterPosition)
        }

        // SY -->
        if (!showButtons) {
            source_browse.isVisible = false
            source_latest.isVisible = false
        }
        // SY <--
    }

    fun bind(item: SourceItem) {
        val source = item.source
        setCardEdges(item)

        // Set source name
        title.text = source.name

        // Set source icon
        itemView.post {
            val icon = source.icon()
            when {
                icon != null -> image.setImageDrawable(icon)
                item.source.id == LocalSource.ID -> image.setImageResource(R.mipmap.ic_local_source)
            }
        }

        source_browse.setText(R.string.browse)
        source_latest.isVisible = source.supportsLatest/* SY --> */ && showButtons /* SY <-- */

        pin.isVisible = true
        pin.setImageResource(
            if (item.isPinned) {
                R.drawable.ic_push_pin_filled_24dp
            } else {
                R.drawable.ic_push_pin_24dp
            }
        )
    }
}
