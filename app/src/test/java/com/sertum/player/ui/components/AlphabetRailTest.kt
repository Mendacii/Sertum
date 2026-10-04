package com.sertum.player.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The A-Z rail's letter mapping, including the '#' bucket.
 *
 * These are the cases that produced a real bug rather than hypothetical ones: '#' used to
 * resolve to the head of the list, which is where the rail already sits, so the non-Latin
 * run at the end of an alphabetically sorted library could not be reached from the rail at
 * all. The titles below are taken from the library on the reference device, sorted the way
 * the app's queries sort them (SQLite COLLATE NOCASE), because the fix depends on where
 * those entries actually land.
 */
class AlphabetRailTest {

    @Test
    fun `latin titles bucket to their uppercase letter`() {
        assertThat(firstLetterOf("Death")).isEqualTo('D')
        assertThat(firstLetterOf("banG dream!")).isEqualTo('B')
        assertThat(firstLetterOf("  Lorna Shore")).isEqualTo('L')
    }

    @Test
    fun `punctuation, digits and non-latin all bucket to hash`() {
        // Sorts before 'A'
        assertThat(firstLetterOf("'Sick")).isEqualTo('#')
        assertThat(firstLetterOf("...And I Return To Nothingness")).isEqualTo('#')
        assertThat(firstLetterOf("2V-ALK")).isEqualTo('#')
        assertThat(firstLetterOf("12")).isEqualTo('#')
        // Sorts after 'Z'
        assertThat(firstLetterOf("静降想")).isEqualTo('#')
        assertThat(firstLetterOf("橋本みゆき")).isEqualTo('#')
        assertThat(firstLetterOf("Ça")).isEqualTo('#')
        // Degenerate input
        assertThat(firstLetterOf("")).isEqualTo('#')
        assertThat(firstLetterOf("   ")).isEqualTo('#')
    }

    /**
     * The distinction the '#' jump depends on: a digit or a punctuation mark buckets to
     * '#', but is not what the reader means by the non-Latin run.
     */
    @Test
    fun `non-latin excludes digits and punctuation`() {
        assertThat(startsWithNonLatin("静降想")).isTrue()
        assertThat(startsWithNonLatin("橋本みゆき")).isTrue()
        assertThat(startsWithNonLatin("Ça")).isTrue()
        assertThat(startsWithNonLatin("2V-ALK")).isFalse()
        assertThat(startsWithNonLatin("'Sick")).isFalse()
        assertThat(startsWithNonLatin("...And I Return")).isFalse()
        assertThat(startsWithNonLatin("Death")).isFalse()
        assertThat(startsWithNonLatin("")).isFalse()
    }

    /**
     * Reproduces the ordering the albums query produces on the reference device: entries
     * starting with punctuation or a digit sort first, then A-Z, then the non-Latin titles
     * - which are a run, not a single entry.
     */
    private val albumsAsSortedByTheQuery = listOf(
        "'Sick",
        "...And I Return To Nothingness - EP",
        "2V-ALK",
        "A Song Of Romance",
        "Abracadabra",
        "BanG Dream!",
        "ELEMENTS",
        "Gloire Éternelle",
        "静降想",
        "顔",
        "魔法少女まどか☆マギカ ED Magia",
        "黒のバースデイ",
    ).map { firstLetterOf(it) }

    @Test
    fun `hash goes to the first entry of the non-latin run, not the last entry`() {
        val index = railIndexFor('#', albumsAsSortedByTheQuery)

        // The run starts here: three punctuation/digit entries, then A-Z, then the run.
        assertThat(index).isEqualTo(8)
        // Not the end of the list - that was an earlier, wrong reading of the request.
        assertThat(index).isLessThan(albumsAsSortedByTheQuery.lastIndex)
        // And not the head, which is where the rail already is.
        assertThat(index).isGreaterThan(0)
    }

    @Test
    fun `every entry from the hash jump onward is non-latin`() {
        val index = railIndexFor('#', albumsAsSortedByTheQuery)!!

        val fromThere = listOf(
            "'Sick", "...And I Return To Nothingness - EP", "2V-ALK", "A Song Of Romance",
            "Abracadabra", "BanG Dream!", "ELEMENTS", "Gloire Éternelle", "静降想", "顔",
            "魔法少女まどか☆マギカ ED Magia", "黒のバースデイ",
        ).drop(index)

        assertThat(fromThere.all { startsWithNonLatin(it) }).isTrue()
    }

    @Test
    fun `a letter jumps to its own first entry`() {
        assertThat(railIndexFor('A', albumsAsSortedByTheQuery)).isEqualTo(3)
        assertThat(railIndexFor('B', albumsAsSortedByTheQuery)).isEqualTo(5)
        assertThat(railIndexFor('E', albumsAsSortedByTheQuery)).isEqualTo(6)
    }

    @Test
    fun `letters with no entries resolve to nothing`() {
        assertThat(railIndexFor('Q', albumsAsSortedByTheQuery)).isNull()
        assertThat(railIndexFor('Z', albumsAsSortedByTheQuery)).isNull()
    }

    @Test
    fun `an empty list resolves to nothing rather than throwing`() {
        assertThat(railIndexFor('#', emptyList())).isNull()
        assertThat(railIndexFor('A', emptyList())).isNull()
    }

    /**
     * A library of nothing but non-Latin titles has no letters to anchor against, but '#'
     * must still find them rather than resolving to nothing.
     */
    @Test
    fun `an all non-latin list resolves hash to its first entry`() {
        val buckets = listOf("眠", "花澤香菜", "電気式華憐音楽集団").map { firstLetterOf(it) }

        assertThat(railIndexFor('#', buckets)).isEqualTo(0)
        assertThat(railIndexFor('A', buckets)).isNull()
    }

    /**
     * Titles that bucket to '#' but are neither letters nor non-Latin - a library whose
     * only unusual entries are digits and punctuation. The jump must still land somewhere
     * real rather than nowhere.
     */
    @Test
    fun `a list with only punctuation buckets still resolves hash`() {
        val buckets = listOf("'Sick", "2V-ALK").map { firstLetterOf(it) }

        assertThat(railIndexFor('#', buckets)).isEqualTo(0)
    }

    /**
     * Letters, then a non-Latin run, then nothing else: the jump must land on the run's
     * first entry, which is the last entry here.
     */
    @Test
    fun `a run that reaches the end of the list still resolves to its first entry`() {
        val buckets = listOf("Aevv", "BanG Dream!", "眠", "顔").map { firstLetterOf(it) }

        assertThat(railIndexFor('#', buckets)).isEqualTo(2)
    }
}
