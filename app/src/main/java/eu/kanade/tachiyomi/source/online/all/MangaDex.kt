package eu.kanade.tachiyomi.source.online.all

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.UrlImportableSource
import exh.source.DelegatedHttpSource
import exh.util.urlImportFetchSearchManga
import rx.Observable

class MangaDex(delegate: HttpSource, val context: Context) :
    DelegatedHttpSource(delegate),
    ConfigurableSource,
    UrlImportableSource {

    override val matchingHosts: List<String> = listOf("mangadex.org", "www.mangadex.org")

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
        urlImportFetchSearchManga(context, query) {
            super.fetchSearchManga(page, query, filters)
        }

    override fun mapUrlToMangaUrl(uri: Uri): String? {
        val lcFirstPathSegment = uri.pathSegments.firstOrNull()?.toLowerCase() ?: return null

        return if (lcFirstPathSegment == "title" || lcFirstPathSegment == "manga") {
            "/manga/${uri.pathSegments[1]}"
        } else {
            null
        }
    }

    override val lang: String get() = delegate.lang

    override fun setupPreferenceScreen(screen: PreferenceScreen) = (delegate as ConfigurableSource).setupPreferenceScreen(screen)
}
