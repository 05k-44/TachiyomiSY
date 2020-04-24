package eu.kanade.tachiyomi.ui.reader

import android.graphics.Typeface
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.drawable.DrawableCompat
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.items.AbstractItem
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.util.chapter.ChapterUtil
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols

class ReaderChapterItem(val chapter: Chapter, val manga: Manga, val isCurrent: Boolean) :
    AbstractItem<ReaderChapterItem.ViewHolder>
    () {

    val decimalFormat =
        DecimalFormat("#.###", DecimalFormatSymbols().apply { decimalSeparator = '.' })

    /** defines the type defining this item. must be unique. preferably an id */
    override val type: Int = R.id.reader_chapter_layout

    /** defines the layout which will be used for this item in the list */
    override val layoutRes: Int = R.layout.reader_chapter_item

    override var identifier: Long
        get() = chapter.id!!
        set(value) {}

    override fun getViewHolder(v: View): ViewHolder {
        return ViewHolder(v)
    }

    class ViewHolder(view: View) : FastAdapter.ViewHolder<ReaderChapterItem>(view) {
        private var chapterTitle: TextView = view.findViewById(R.id.chapter_title)
        private var chapterSubtitle: TextView = view.findViewById(R.id.chapter_scanlator)
        var bookmarkButton: FrameLayout = view.findViewById(R.id.bookmark_layout)
        private var bookmarkImage: ImageView = view.findViewById(R.id.bookmark_image)

        override fun bindView(item: ReaderChapterItem, payloads: List<Any>) {
            val chapter = item.chapter
            val manga = item.manga

            val chapterColor = ChapterUtil.chapterColor(itemView.context, item.chapter)

            chapterTitle.setTextColor(chapterColor)
            chapterTitle.text = when (manga.displayMode) {
                Manga.DISPLAY_NUMBER -> {
                    val number = item.decimalFormat.format(chapter.chapter_number.toDouble())
                    itemView.context.getString(R.string.chapter_, number)
                }
                else -> chapter.name
            }

            val statuses = mutableListOf<String>()
            ChapterUtil.relativeDate(chapter)?.let { statuses.add(it) }
            chapter.scanlator?.isNotBlank()?.let { statuses.add(chapter.scanlator!!) }

            if (item.isCurrent) {
                chapterTitle.setTypeface(null, Typeface.BOLD_ITALIC)
                chapterSubtitle.setTypeface(null, Typeface.BOLD_ITALIC)
            } else {
                chapterTitle.setTypeface(null, Typeface.NORMAL)
                chapterSubtitle.setTypeface(null, Typeface.NORMAL)
            }

            // match color of the chapter title
            chapterSubtitle.setTextColor(chapterColor)

            bookmarkImage.setImageResource(
                if (chapter.bookmark) R.drawable.ic_bookmark_24dp
                else R.drawable.ic_bookmark_border_24dp
            )

            val drawableColor = ChapterUtil.bookmarkColor(itemView.context, chapter)

            DrawableCompat.setTint(bookmarkImage.drawable, drawableColor)

            chapterSubtitle.text = statuses.joinToString(" • ")
        }

        override fun unbindView(item: ReaderChapterItem) {
            chapterTitle.text = null
            chapterSubtitle.text = null
        }
    }
}
