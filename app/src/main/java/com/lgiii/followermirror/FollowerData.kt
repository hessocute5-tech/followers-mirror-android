package com.lgiii.followermirror

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

/** Pure import and comparison rules shared by the UI and JVM unit tests. */
object FollowerData {
    enum class DatasetKind { FOLLOWERS, FOLLOWING, UNKNOWN, CONFLICT }

    data class SourceDocument(val filename: String, val contents: String)
    data class ValidatedImport(val kind: DatasetKind, val accounts: Set<String>, val sourceFiles: List<String>)
    data class Comparison(
        val mutuals: Set<String>,
        val notFollowingBack: Set<String>,
        val notFollowingMe: Set<String>,
    )

    fun normalizeUsername(value: String): String = value.trim().removePrefix("@").trim().lowercase(Locale.ROOT)

    fun normalizeUsernames(values: Iterable<String>): Set<String> =
        values.map(::normalizeUsername).filter(String::isNotBlank).toSet()

    fun compare(followers: Iterable<String>, following: Iterable<String>): Comparison {
        val followerSet = normalizeUsernames(followers)
        val followingSet = normalizeUsernames(following)
        return Comparison(
            mutuals = followerSet intersect followingSet,
            notFollowingBack = followingSet - followerSet,
            notFollowingMe = followerSet - followingSet,
        )
    }

    fun detectedDisappearances(earlier: Iterable<String>, later: Iterable<String>): Set<String> =
        normalizeUsernames(earlier) - normalizeUsernames(later)

    /**
     * Determine a file's dataset category from export structure first, then its canonical filename.
     * A contradictory filename and JSON structure is rejected instead of silently trusted.
     */
    fun identifyDatasetType(raw: String, filename: String): DatasetKind {
        val root = parseRoot(raw)
        val structuralKinds = linkedSetOf<DatasetKind>()

        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        when (key.lowercase(Locale.ROOT)) {
                            "relationships_following", "following", "following_accounts" -> structuralKinds += DatasetKind.FOLLOWING
                            "relationships_followers", "followers", "follower_accounts" -> structuralKinds += DatasetKind.FOLLOWERS
                        }
                        walk(value.opt(key))
                    }
                }
                is JSONArray -> for (index in 0 until value.length()) walk(value.opt(index))
            }
        }
        walk(root)

        val fromStructure = when (structuralKinds.size) {
            0 -> DatasetKind.UNKNOWN
            1 -> structuralKinds.first()
            else -> DatasetKind.CONFLICT
        }
        val fromFilename = filenameKind(filename)
        if (fromStructure == DatasetKind.CONFLICT) return DatasetKind.CONFLICT
        if (fromStructure != DatasetKind.UNKNOWN) {
            return if (fromFilename != DatasetKind.UNKNOWN && fromFilename != fromStructure) DatasetKind.CONFLICT else fromStructure
        }
        return fromFilename
    }

    /** Validate every selected file before returning a candidate dataset for atomic persistence. */
    fun validateAndParse(documents: List<SourceDocument>, expected: DatasetKind): ValidatedImport {
        require(expected == DatasetKind.FOLLOWERS || expected == DatasetKind.FOLLOWING) { "Choose Followers or Following before importing." }
        require(documents.isNotEmpty()) { "Select at least one JSON file." }
        val combined = linkedSetOf<String>()
        documents.forEach { document ->
            val kind = identifyDatasetType(document.contents, document.filename)
            if (kind != expected) {
                val description = when (kind) {
                    DatasetKind.FOLLOWERS -> "a Followers export"
                    DatasetKind.FOLLOWING -> "a Following export"
                    DatasetKind.CONFLICT -> "conflicting dataset types"
                    DatasetKind.UNKNOWN -> "an unrecognized or ambiguous export"
                }
                val expectedLabel = if (expected == DatasetKind.FOLLOWERS) "Followers" else "Following"
                throw IllegalArgumentException("${document.filename} was identified as $description. It cannot be imported into $expectedLabel. Select the matching original Instagram export file.")
            }
            val names = parseInstagramExport(document.contents)
            if (names.isEmpty()) throw IllegalArgumentException("${document.filename} contains no account records.")
            combined += names
        }
        if (combined.isEmpty()) throw IllegalArgumentException("No usernames were found in the selected file(s).")
        return ValidatedImport(expected, combined, documents.map { it.filename })
    }

    fun parseFollowerExports(contents: Iterable<String>): Set<String> =
        normalizeUsernames(contents.flatMap(::parseInstagramExport))

    fun parseInstagramExport(raw: String): Set<String> {
        val names = linkedSetOf<String>()

        fun add(value: String?) {
            val username = value?.trim()?.removePrefix("@")?.trim('/') ?: return
            if (username.matches(Regex("[A-Za-z0-9._]{1,30}"))) names += normalizeUsername(username)
        }

        fun usernameFromHref(href: String): String? {
            val match = Regex("(?:^|//)(?:www\\.)?instagram\\.com/([^/?#]+)/?", RegexOption.IGNORE_CASE)
                .find(href.trim())
            return match?.groupValues?.getOrNull(1)
        }

        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val list = value.optJSONArray("string_list_data")
                    if (list != null) {
                        for (index in 0 until list.length()) {
                            val entry = list.optJSONObject(index) ?: continue
                            val entryValue = entry.opt("value") as? String
                            if (!entryValue.isNullOrBlank()) add(entryValue)
                            else add(usernameFromHref(entry.opt("href") as? String ?: ""))
                        }
                    }
                    if (list != null || value.has("media_list_data")) add(value.optString("title").takeIf(String::isNotBlank))
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key != "string_list_data") walk(value.opt(key))
                    }
                }
                is JSONArray -> for (index in 0 until value.length()) walk(value.opt(index))
            }
        }

        walk(parseRoot(raw))
        return names
    }

    private fun filenameKind(filename: String): DatasetKind {
        val base = filename.substringAfterLast('/').substringAfterLast('\\').lowercase(Locale.ROOT)
        return when {
            Regex("^followers(?:[_-]\\d+)?\\.json$").matches(base) -> DatasetKind.FOLLOWERS
            Regex("^following(?:[_-]\\d+)?\\.json$").matches(base) -> DatasetKind.FOLLOWING
            else -> DatasetKind.UNKNOWN
        }
    }

    private fun parseRoot(raw: String): Any {
        val trimmed = raw.trimStart()
        if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) {
            throw org.json.JSONException("Expected a JSON object or array")
        }
        val tokener = JSONTokener(raw)
        val root = tokener.nextValue()
        if (root !is JSONObject && root !is JSONArray) {
            throw org.json.JSONException("Expected a JSON object or array")
        }
        if (tokener.nextClean() != '\u0000') {
            throw org.json.JSONException("Unexpected data after the JSON document")
        }
        return root
    }
}
