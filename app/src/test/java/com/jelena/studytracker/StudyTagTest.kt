package com.jelena.studytracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The payload written to a tag is the app's only wire format, so the mapping in both
 * directions is worth pinning down: a change to these strings silently bricks tags that
 * are already programmed and stuck to a desk.
 */
class StudyTagTest {

    @Test
    fun `payloads round-trip`() {
        StudyTag.entries.forEach { tag ->
            assertEquals(tag, StudyTag.fromPayload(tag.payload))
        }
    }

    @Test
    fun `payloads are the documented strings`() {
        assertEquals("study", StudyTag.STUDY.payload)
        assertEquals("switch", StudyTag.SWITCH.payload)
    }

    @Test
    fun `surrounding whitespace and case do not stop a tag being recognised`() {
        assertEquals(StudyTag.STUDY, StudyTag.fromPayload(" STUDY\n"))
    }

    @Test
    fun `an unknown payload is null rather than a guess`() {
        assertNull(StudyTag.fromPayload("studying"))
        assertNull(StudyTag.fromPayload(""))
        assertNull(StudyTag.fromPayload(null))
    }
}
