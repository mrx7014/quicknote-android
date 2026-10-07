package com.quicknote.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TagNormalizerTest {
    @Test fun trimsHashesAndDeduplicatesWithoutCaseSensitivity() {
        assertEquals("Work, home, Ideas", TagNormalizer.normalize(" #Work, home; work\nIdeas "))
    }

    @Test fun limitsTagLengthAndNumberOfTags() {
        val longTag = "x".repeat(40)
        val tags = TagNormalizer.normalize("$longTag, b, c, d, e, f, g, h, i, j")
        assertEquals(8, tags.split(", ").size)
        assertEquals(32, tags.substringBefore(',' ).length)
    }

    @Test fun ignoresEmptyTags() {
        assertEquals("Work", TagNormalizer.normalize(", ; #Work,,"))
    }
}
