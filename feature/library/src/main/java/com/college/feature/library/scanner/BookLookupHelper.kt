package com.college.feature.library.scanner

import android.util.Log
import com.college.feature.library.network.BookLookupResponse
import com.college.feature.library.network.LibraryApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Multi-provider client lookup helper:
 * 1. Queries backend /api/v1/library/books/lookup/{isbn} (which uses Google Books, OpenLibrary, and ISBNdb).
 * 2. If backend result lacks Table of Contents, supplements it by querying OpenLibrary TOC endpoints.
 * 3. Searches across sister editions of the same work and publisher descriptions if the exact ISBN lacks a TOC.
 * 4. For books whose Table of Contents cannot be found online, leaves tableOfContents null (empty).
 */
object BookLookupHelper {

    private const val TAG = "BookLookup"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun lookup(isbn: String): BookLookupResponse? = withContext(Dispatchers.IO) {
        val cleanIsbn = isbn.trim().replace("-", "").uppercase()
        if (cleanIsbn.length < 8) {
            Log.w(TAG, "ISBN too short: '$isbn'")
            return@withContext null
        }

        Log.i(TAG, "Starting multi-provider lookup for ISBN: $cleanIsbn")

        // 1. Try backend service first
        try {
            val response = LibraryApiClient.service.lookupBook(cleanIsbn)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                Log.i(TAG, "Backend returned book: '${body.title}', hasTOC=${!body.tableOfContents.isNullOrBlank()}")
                if (body.tableOfContents.isNullOrBlank()) {
                    val olToc = fetchOpenLibraryToc(cleanIsbn, body.title)
                    if (!olToc.isNullOrBlank()) {
                        Log.i(TAG, "Supplemented backend book with OpenLibrary TOC")
                        return@withContext body.copy(
                            tableOfContents = olToc,
                            source = "${body.source ?: "Backend"} + OpenLibrary TOC"
                        )
                    }
                }
                return@withContext body
            } else {
                Log.d(TAG, "Backend returned non-success code: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.d(TAG, "Backend service unavailable (${e.message}) — proceeding to direct on-device APIs")
        }

        // 2. Direct client-side queries: Google Books and OpenLibrary
        val gbResult = queryGoogleBooks(cleanIsbn)
        val olResult = queryOpenLibrary(cleanIsbn)

        if (gbResult == null && olResult == null) {
            Log.w(TAG, "Neither Google Books nor OpenLibrary returned data for $cleanIsbn")
            return@withContext null
        }

        // Merge sources into best possible metadata
        val title = gbResult?.title?.takeIf { it != "Unknown Title" }
            ?: olResult?.title
            ?: "Unknown Title"
        val author = gbResult?.author?.takeIf { it != "Unknown Author" }
            ?: olResult?.author
            ?: "Unknown Author"
        val edition = gbResult?.edition ?: olResult?.edition
        val index = olResult?.index ?: gbResult?.index ?: "Main Hall"
        val category = if (!gbResult?.category.isNullOrBlank() && gbResult?.category != "GENERAL") {
            gbResult!!.category
        } else {
            olResult?.category ?: "GENERAL"
        }
        val overview = gbResult?.overview ?: olResult?.overview

        // 3. Multi-tier Table of Contents resolution
        val directToc = fetchOpenLibraryToc(cleanIsbn, title)
            ?: olResult?.tableOfContents
            ?: extractTocFromDescription(overview)
            ?: gbResult?.tableOfContents

        val source = when {
            gbResult != null && olResult != null && directToc != null -> "Google Books + OpenLibrary TOC"
            gbResult != null && olResult != null -> "Google Books + OpenLibrary"
            gbResult != null && directToc != null -> "Google Books + OpenLibrary TOC"
            gbResult != null -> "Google Books"
            olResult != null -> "OpenLibrary"
            else -> "Online Lookup"
        }

        Log.i(TAG, "Resolved '$title' by '$author' from $source (hasTOC=${!directToc.isNullOrBlank()})")

        BookLookupResponse(
            title = title,
            author = author,
            edition = edition,
            index = index,
            isbn = cleanIsbn,
            category = category,
            overview = overview,
            tableOfContents = directToc,
            source = source
        )
    }

    private fun isbn13To10(isbn13: String): String? {
        if (isbn13.length != 13 || !isbn13.startsWith("978")) return null
        val core = isbn13.substring(3, 12)
        var sum = 0
        for (i in 0 until 9) {
            val digit = core[i].digitToIntOrNull() ?: return null
            sum += digit * (10 - i)
        }
        val rem = (11 - (sum % 11)) % 11
        val checkDigit = if (rem == 10) "X" else rem.toString()
        return core + checkDigit
    }

    fun formatTableOfContents(raw: Any?): String? {
        if (raw == null) return null
        return when (raw) {
            is org.json.JSONArray -> {
                val lines = mutableListOf<String>()
                for (i in 0 until raw.length()) {
                    val item = raw.opt(i)
                    if (item is JSONObject) {
                        val label = item.optString("label").trim()
                        val title = item.optString("title").trim()
                        val page = item.optString("pagenum").trim()
                        val line = buildString {
                            if (label.isNotBlank() && title.isNotBlank()) {
                                append("$label: $title")
                            } else if (label.isNotBlank()) {
                                append(label)
                            } else if (title.isNotBlank()) {
                                append(title)
                            }
                            if (page.isNotBlank()) append(" (p. $page)")
                        }.trim()
                        if (line.isNotBlank()) lines.add(line)
                    } else if (item is String && item.isNotBlank()) {
                        lines.add(item.trim())
                    }
                }
                if (lines.isNotEmpty()) lines.joinToString("\n") else null
            }
            is String -> {
                val trimmed = raw.trim()
                if (trimmed.isBlank()) return null
                if (trimmed.contains(" -- ") && !trimmed.contains("\n")) {
                    val items = trimmed.split(" -- ").map { it.trim() }.filter { it.isNotBlank() }
                    return items.joinToString("\n")
                }
                trimmed
            }
            else -> null
        }
    }

    private fun extractTocFromDescription(desc: String?): String? {
        if (desc.isNullOrBlank()) return null
        val lower = desc.lowercase()
        val triggers = listOf(
            "table of contents:",
            "contents:",
            "chapters include:",
            "chapters:",
            "brief contents:",
            "detailed contents:"
        )
        for (trigger in triggers) {
            val idx = lower.indexOf(trigger)
            if (idx != -1) {
                val after = desc.substring(idx + trigger.length).trim()
                val lines = after.take(1500).lines()
                    .map { it.trim().trimStart('-', '*', '•').trim() }
                    .filter { it.isNotBlank() }
                if (lines.size >= 2) {
                    Log.i(TAG, "Extracted TOC from description trigger '$trigger' (${lines.size} lines)")
                    return lines.joinToString("\n")
                }
            }
        }
        return null
    }

    private fun fetchOpenLibraryToc(isbn: String, titleHint: String? = null): String? {
        val candidates = mutableListOf(isbn)
        isbn13To10(isbn)?.let { candidates.add(it) }

        var workKey: String? = null

        // Pass 1: Try exact ISBN on jscmd=data and /isbn/{isbn}.json
        for (cand in candidates) {
            // A. jscmd=data
            try {
                val url = "https://openlibrary.org/api/books?bibkeys=ISBN:$cand&format=json&jscmd=data"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "BLE-Attendance-Library-App/1.0 (academic-project)")
                    .build()
                httpClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bodyStr = resp.body?.string()
                        if (!bodyStr.isNullOrBlank()) {
                            val root = JSONObject(bodyStr)
                            val bookObj = root.optJSONObject("ISBN:$cand")
                            val toc = bookObj?.opt("table_of_contents")
                            val formatted = formatTableOfContents(toc)
                            if (!formatted.isNullOrBlank()) {
                                Log.i(TAG, "TOC found via OpenLibrary jscmd=data for $cand")
                                return formatted
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "OpenLibrary jscmd=data query error: ${e.message}")
            }

            // B. /isbn/{isbn}.json (follows redirects)
            try {
                val url = "https://openlibrary.org/isbn/$cand.json"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "BLE-Attendance-Library-App/1.0 (academic-project)")
                    .build()
                httpClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bodyStr = resp.body?.string()
                        if (!bodyStr.isNullOrBlank()) {
                            val root = JSONObject(bodyStr)
                            val toc = root.opt("table_of_contents")
                            val formatted = formatTableOfContents(toc)
                            if (!formatted.isNullOrBlank()) {
                                Log.i(TAG, "TOC found via OpenLibrary /isbn/$cand.json")
                                return formatted
                            }
                            if (workKey == null) {
                                val worksArr = root.optJSONArray("works")
                                if (worksArr != null && worksArr.length() > 0) {
                                    workKey = worksArr.optJSONObject(0)?.optString("key")
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "OpenLibrary /isbn/$cand.json error: ${e.message}")
            }
        }

        // Pass 2: Check other editions of this Work
        if (!workKey.isNullOrBlank()) {
            try {
                val url = "https://openlibrary.org$workKey/editions.json?limit=25"
                Log.d(TAG, "Checking sister editions for work: $workKey")
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "BLE-Attendance-Library-App/1.0 (academic-project)")
                    .build()
                httpClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bodyStr = resp.body?.string()
                        if (!bodyStr.isNullOrBlank()) {
                            val root = JSONObject(bodyStr)
                            val entries = root.optJSONArray("entries")
                            if (entries != null) {
                                for (i in 0 until entries.length()) {
                                    val entry = entries.optJSONObject(i) ?: continue
                                    val toc = entry.opt("table_of_contents")
                                    val formatted = formatTableOfContents(toc)
                                    if (!formatted.isNullOrBlank()) {
                                        Log.i(TAG, "TOC found via sister edition ${entry.optString("key")} of work $workKey")
                                        return formatted
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "OpenLibrary work editions query error: ${e.message}")
            }
        }

        // Pass 3: Search OpenLibrary by title if titleHint is provided
        if (!titleHint.isNullOrBlank() && titleHint != "Unknown Title") {
            try {
                val cleanTitle = titleHint.take(50).replace(Regex("[^a-zA-Z0-9 ]"), " ").trim()
                if (cleanTitle.length >= 4) {
                    val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8")
                    val searchUrl = "https://openlibrary.org/search.json?title=$encodedTitle&limit=3&fields=key,title"
                    Log.d(TAG, "Searching OpenLibrary by title: $cleanTitle")
                    val request = Request.Builder()
                        .url(searchUrl)
                        .header("User-Agent", "BLE-Attendance-Library-App/1.0 (academic-project)")
                        .build()
                    httpClient.newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val bodyStr = resp.body?.string()
                            if (!bodyStr.isNullOrBlank()) {
                                val root = JSONObject(bodyStr)
                                val docs = root.optJSONArray("docs")
                                if (docs != null && docs.length() > 0) {
                                    for (d in 0 until docs.length()) {
                                        val doc = docs.optJSONObject(d) ?: continue
                                        val wk = doc.optString("key").takeIf { it.isNotBlank() } ?: continue
                                        val edUrl = "https://openlibrary.org$wk/editions.json?limit=25"
                                        val edReq = Request.Builder()
                                            .url(edUrl)
                                            .header("User-Agent", "BLE-Attendance-Library-App/1.0 (academic-project)")
                                            .build()
                                        httpClient.newCall(edReq).execute().use { edResp ->
                                            if (edResp.isSuccessful) {
                                                val edStr = edResp.body?.string()
                                                if (!edStr.isNullOrBlank()) {
                                                    val edRoot = JSONObject(edStr)
                                                    val entries = edRoot.optJSONArray("entries")
                                                    if (entries != null) {
                                                        for (i in 0 until entries.length()) {
                                                            val entry = entries.optJSONObject(i) ?: continue
                                                            val toc = entry.opt("table_of_contents")
                                                            val formatted = formatTableOfContents(toc)
                                                            if (!formatted.isNullOrBlank()) {
                                                                Log.i(TAG, "TOC found via title search work $wk edition ${entry.optString("key")}")
                                                                return formatted
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "OpenLibrary title search error: ${e.message}")
            }
        }

        return null
    }

    private fun queryGoogleBooks(isbn: String): BookLookupResponse? {
        try {
            val url = "https://www.googleapis.com/books/v1/volumes?q=isbn:$isbn"
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Google Books HTTP response: ${resp.code}")
                    return null
                }
                val bodyStr = resp.body?.string() ?: return null
                val json = JSONObject(bodyStr)
                val items = json.optJSONArray("items") ?: return null
                if (items.length() == 0) return null

                val volumeInfo = items.getJSONObject(0).optJSONObject("volumeInfo") ?: return null
                val title = volumeInfo.optString("title").ifBlank { "Unknown Title" }
                val authorsArray = volumeInfo.optJSONArray("authors")
                val authors = if (authorsArray != null && authorsArray.length() > 0) {
                    val list = mutableListOf<String>()
                    for (i in 0 until authorsArray.length()) list.add(authorsArray.getString(i))
                    list.joinToString(", ")
                } else "Unknown Author"

                val categoriesArray = volumeInfo.optJSONArray("categories")
                val category = if (categoriesArray != null && categoriesArray.length() > 0) {
                    categoriesArray.getString(0).uppercase().take(30)
                } else "GENERAL"

                val description = volumeInfo.optString("description").takeIf { it.isNotBlank() }
                val cleanOverview = description?.replace(Regex("<[^>]+>"), "")?.trim()
                val publishedDate = volumeInfo.optString("publishedDate").takeIf { it.isNotBlank() }
                val edition = publishedDate?.let { "Published $it" }

                val tocRaw = volumeInfo.opt("tableOfContents")
                val tableOfContents = formatTableOfContents(tocRaw)

                return BookLookupResponse(
                    title = title,
                    author = authors,
                    edition = edition,
                    index = "Main Hall",
                    isbn = isbn,
                    category = category,
                    overview = cleanOverview,
                    tableOfContents = tableOfContents,
                    source = "Google Books"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Google Books query error: ${e.message}")
            return null
        }
    }

    private fun queryOpenLibrary(isbn: String): BookLookupResponse? {
        val candidates = mutableListOf(isbn)
        isbn13To10(isbn)?.let { candidates.add(it) }

        for (cand in candidates) {
            try {
                val url = "https://openlibrary.org/api/books?bibkeys=ISBN:$cand&format=json&jscmd=data"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "BLE-Attendance-Library-App/1.0 (academic-project)")
                    .build()

                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val bodyStr = resp.body?.string() ?: return@use
                    val root = JSONObject(bodyStr)
                    val bookObj = root.optJSONObject("ISBN:$cand") ?: return@use

                    val title = bookObj.optString("title").ifBlank { "Unknown Title" }
                    val authorsArray = bookObj.optJSONArray("authors")
                    val authors = if (authorsArray != null && authorsArray.length() > 0) {
                        val list = mutableListOf<String>()
                        for (i in 0 until authorsArray.length()) {
                            list.add(authorsArray.getJSONObject(i).optString("name"))
                        }
                        list.joinToString(", ")
                    } else "Unknown Author"

                    val subjectsArray = bookObj.optJSONArray("subjects")
                    val category = if (subjectsArray != null && subjectsArray.length() > 0) {
                        subjectsArray.getJSONObject(0).optString("name").uppercase().take(30)
                    } else "GENERAL"

                    val publishDate = bookObj.optString("publish_date").takeIf { it.isNotBlank() }
                    val classifications = bookObj.optJSONObject("classifications")
                    val lcClass = classifications?.optJSONArray("lc_classifications")?.optString(0)
                    val deweyClass = classifications?.optJSONArray("dewey_decimal_class")?.optString(0)
                    val index = lcClass?.ifBlank { null } ?: deweyClass?.ifBlank { null } ?: "Main Hall"

                    val notes = bookObj.opt("notes")
                    val overview = when (notes) {
                        is JSONObject -> notes.optString("value")
                        is String -> notes
                        else -> null
                    }?.replace(Regex("<[^>]+>"), "")?.trim()

                    val tableOfContents = formatTableOfContents(bookObj.opt("table_of_contents"))

                    return BookLookupResponse(
                        title = title,
                        author = authors,
                        edition = publishDate,
                        index = index,
                        isbn = isbn,
                        category = category,
                        overview = overview,
                        tableOfContents = tableOfContents,
                        source = "OpenLibrary"
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "OpenLibrary metadata query error for $cand: ${e.message}")
            }
        }
        return null
    }
}
