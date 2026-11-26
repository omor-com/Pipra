package com.pipra.browser

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.TextView

data class Suggestion(val title: String, val url: String, val kind: Kind) {
    enum class Kind { HISTORY, BOOKMARK, TOP_SITE, SEARCH }
    override fun toString(): String = url
}

class SuggestAdapter(
    context: Context,
    private val source: () -> List<Suggestion>
) : ArrayAdapter<Suggestion>(context, 0) {

    private var items: List<Suggestion> = emptyList()
    private val inflater = LayoutInflater.from(context)

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Suggestion = items[position]

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = convertView ?: inflater.inflate(android.R.layout.simple_list_item_2, parent, false)
        row.setBackgroundColor(0xFF14161A.toInt())
        val s = items[position]
        val tag = when (s.kind) {
            Suggestion.Kind.HISTORY -> "History · "
            Suggestion.Kind.BOOKMARK -> "Bookmark · "
            Suggestion.Kind.TOP_SITE -> ""
            Suggestion.Kind.SEARCH -> "Search · "
        }
        row.findViewById<TextView>(android.R.id.text1).apply {
            text = tag + s.title
            setTextColor(0xFFE6E8EC.toInt())
            maxLines = 1
        }
        row.findViewById<TextView>(android.R.id.text2).apply {
            text = s.url
            setTextColor(0xFF9CA3AF.toInt())
            maxLines = 1
            textSize = 12f
        }
        return row
    }

    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val q = constraint?.toString()?.trim()?.lowercase().orEmpty()
            val matched = if (q.isEmpty()) emptyList()
            else source()
                .asSequence()
                .filter { it.url.lowercase().contains(q) || it.title.lowercase().contains(q) }
                .distinctBy { it.url }
                .sortedBy { if (hostOf(it.url).startsWith(q)) 0 else 1 }
                .take(8)
                .toList()
            return FilterResults().apply { values = matched; count = matched.size }
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            items = (results?.values as? List<Suggestion>) ?: emptyList()
            if (items.isEmpty()) notifyDataSetInvalidated() else notifyDataSetChanged()
        }

        override fun convertResultToString(resultValue: Any?): CharSequence =
            (resultValue as? Suggestion)?.url ?: ""
    }

    private fun hostOf(url: String): String =
        url.substringAfter("://", url).substringBefore('/').lowercase()

    companion object {
        val TOP_SITES: List<Suggestion> = listOf(
            "DuckDuckGo" to "https://duckduckgo.com",
            "Wikipedia" to "https://www.wikipedia.org",
            "GitHub" to "https://github.com",
            "Stack Overflow" to "https://stackoverflow.com",
            "Brave Search" to "https://search.brave.com",
            "Startpage" to "https://www.startpage.com",
            "Google" to "https://www.google.com",
            "Bing" to "https://www.bing.com"
        ).map { Suggestion(it.first, it.second, Suggestion.Kind.TOP_SITE) }
    }
}
