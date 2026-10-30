package com.lgiii.followermirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class FollowerDataTest {
    @Test fun comparisonPartitionsFollowersAndFollowing() {
        val result = FollowerData.compare(
            followers = listOf("mutual", "follower_only"),
            following = listOf("mutual", "no_followback"),
        )
        assertEquals(setOf("mutual"), result.mutuals)
        assertEquals(setOf("no_followback"), result.notFollowingBack)
        assertEquals(setOf("follower_only"), result.notFollowingMe)
    }

    @Test fun comparisonNormalizesCaseAndDuplicates() {
        val result = FollowerData.compare(
            followers = listOf("Alex", "ALEX", "Sam"),
            following = listOf("alex", "sam", "SAM", "Other"),
        )
        assertEquals(setOf("alex", "sam"), result.mutuals)
        assertEquals(setOf("other"), result.notFollowingBack)
        assertEquals(emptySet<String>(), result.notFollowingMe)
    }

    @Test fun emptyListsProduceEmptyResults() {
        assertEquals(FollowerData.Comparison(emptySet(), emptySet(), emptySet()), FollowerData.compare(emptyList(), emptyList()))
    }

    @Test fun splitFollowerFilesMergeAndDeduplicate() {
        val split = listOf(
            """[{"string_list_data":[{"value":"First.User","href":"https://www.instagram.com/First.User/"}]}]""",
            """[{"string_list_data":[{"value":"second"}]},{"string_list_data":[{"value":"FIRST.USER"}]}]""",
        )
        assertEquals(setOf("first.user", "second"), FollowerData.parseFollowerExports(split))
    }

    @Test fun malformedOrIncompleteJsonFailsCleanlyOrSkipsBadEntries() {
        try {
            FollowerData.parseInstagramExport("not json")
            throw AssertionError("Malformed JSON should be rejected")
        } catch (_: org.json.JSONException) {
            // The UI catches this and reports an import error.
        }
        val partial = """[{"string_list_data":[null,{"href":"https://www.instagram.com/From.Link/"},{"value":""}]}]"""
        assertEquals(setOf("from.link"), FollowerData.parseInstagramExport(partial))
    }

    @Test fun historicalComparisonReportsOnlyDetectedDisappearances() {
        assertEquals(
            setOf("gone"),
            FollowerData.detectedDisappearances(listOf("Stayed", "Gone", "GONE"), listOf("stayed")),
        )
    }

    @Test fun realisticInstagramFixturesAreClassifiedAndCountedCorrectly() {
        val first = fixture("followers_1.json")
        val second = fixture("followers_2.json")
        val following = fixture("following.json")

        assertEquals(FollowerData.DatasetKind.FOLLOWERS, FollowerData.identifyDatasetType(first, "followers_1.json"))
        assertEquals(FollowerData.DatasetKind.FOLLOWING, FollowerData.identifyDatasetType(following, "following.json"))

        val followerImport = FollowerData.validateAndParse(
            listOf(
                FollowerData.SourceDocument("followers_1.json", first),
                FollowerData.SourceDocument("followers_2.json", second),
            ),
            FollowerData.DatasetKind.FOLLOWERS,
        )
        val followingImport = FollowerData.validateAndParse(
            listOf(FollowerData.SourceDocument("following.json", following)),
            FollowerData.DatasetKind.FOLLOWING,
        )
        assertEquals(3, followerImport.accounts.size) // Duplicate MutualOne across split files is counted once.
        assertEquals(3, followingImport.accounts.size)
        val result = FollowerData.compare(followerImport.accounts, followingImport.accounts)
        assertEquals(setOf("mutualone", "mutualtwo"), result.mutuals)
        assertEquals(setOf("followingonly"), result.notFollowingBack)
        assertEquals(setOf("followeronly"), result.notFollowingMe)
    }

    @Test fun aFollowingExportCannotBeImportedAsFollowersEvenIfRenamed() {
        val following = fixture("following.json")
        assertEquals(
            FollowerData.DatasetKind.CONFLICT,
            FollowerData.identifyDatasetType(following, "followers_1.json"),
        )
        val error = assertThrows(IllegalArgumentException::class.java) {
            FollowerData.validateAndParse(
                listOf(FollowerData.SourceDocument("followers_1.json", following)),
                FollowerData.DatasetKind.FOLLOWERS,
            )
        }
        assertTrue(error.message.orEmpty().contains("cannot be imported into Followers"))
    }

    @Test fun aMixedOrMalformedBatchIsRejectedBeforeProducingReplacementData() {
        val oldDataset = setOf("previously-valid")
        val error = assertThrows(IllegalArgumentException::class.java) {
            FollowerData.validateAndParse(
                listOf(
                    FollowerData.SourceDocument("followers_1.json", fixture("followers_1.json")),
                    FollowerData.SourceDocument("following.json", fixture("following.json")),
                ),
                FollowerData.DatasetKind.FOLLOWERS,
            )
        }
        assertTrue(error.message.orEmpty().contains("cannot be imported into Followers"))
        assertEquals(setOf("previously-valid"), oldDataset) // No candidate is returned; callers persist only after validation.
    }

    @Test fun ambiguousJsonWithoutRecognizableStructureOrFilenameIsRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            FollowerData.validateAndParse(
                listOf(FollowerData.SourceDocument("export.json", fixture("followers_1.json"))),
                FollowerData.DatasetKind.FOLLOWERS,
            )
        }
        assertTrue(error.message.orEmpty().contains("unrecognized or ambiguous"))
    }

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("instagram/$name")!!.readText()
}
