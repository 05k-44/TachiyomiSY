package eu.kanade.tachiyomi.ui.source.browse

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.preference.getOrDefault
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.hasSideNavBar
import eu.kanade.tachiyomi.util.view.setEdgeToEdge
import kotlinx.android.synthetic.main.source_filter_sheet.*
import uy.kohesive.injekt.injectLazy

class SourceSearchSheet(activity: Activity) :
    BottomSheetDialog(activity, R.style.BottomSheetDialogTheme) {

    /**
     * Preferences helper.
     */
    private val preferences by injectLazy<PreferencesHelper>()

    private var sheetBehavior: BottomSheetBehavior<*>

    private var elevationAnimator: ValueAnimator? = null

    var filterChanged = true

    var isNotElevated = false

    val adapter: FlexibleAdapter<IFlexible<*>> = FlexibleAdapter<IFlexible<*>>(null)
        .setDisplayHeadersAtStartUp(true)

    var onSearchClicked = {}

    var onResetClicked = {}

    init {
        val view = activity.layoutInflater.inflate(R.layout.source_filter_sheet, null)
        setContentView(view)
        toolbar_title.text = context.getString(R.string.search_filters)
        search_btn.setOnClickListener { dismiss() }
        reset_btn.setOnClickListener { onResetClicked() }

        recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
        recycler.clipToPadding = false
        recycler.adapter = adapter
        recycler.setHasFixedSize(true)
        sheetBehavior = BottomSheetBehavior.from(view.parent as ViewGroup)
        sheetBehavior.skipCollapsed = true
        sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        setEdgeToEdge(
            activity, view, 50.dpToPx
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            preferences.readerTheme().getOrDefault() == 0 &&
            !activity.window.decorView.rootWindowInsets.hasSideNavBar())
            window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        sheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onSlide(bottomSheet: View, progress: Float) {}

            override fun onStateChanged(p0: View, state: Int) {
                if (state == BottomSheetBehavior.STATE_EXPANDED) {
                    sheetBehavior.skipCollapsed = true
                }
            }
        })

        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val atTop = !recycler.canScrollVertically(-1)
                if (atTop != isNotElevated) {
                    elevationAnimator?.cancel()
                    isNotElevated = atTop
                    elevationAnimator?.cancel()
                    elevationAnimator = ObjectAnimator.ofFloat(
                        title_layout,
                        "elevation",
                        title_layout.elevation,
                        if (atTop) 0f else 10f.dpToPx
                    )
                    elevationAnimator?.duration = 100
                    elevationAnimator?.start()
                }
            }
        })
    }

    override fun dismiss() {
        super.dismiss()
        if (filterChanged)
            onSearchClicked()
    }

    fun setFilters(items: List<IFlexible<*>>) {
        adapter.updateDataSet(items)
    }
}
