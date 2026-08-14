package com.evsuite.tasker.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactDirectoryTest {
    @Test fun `phone formatting is removed but dialing symbols are retained`() {
        assertEquals("+33123456789", ContactDirectory.normalize("+33 (1) 23-45-67-89"))
        assertEquals("*123#", ContactDirectory.normalize("* 123 #"))
    }
}
