package com.evsuite.tasker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WebhookClientTest {
    @Test fun `cleartext webhook is refused before network access`() {
        val result = WebhookClient.call("GET", "http://example.invalid/hook", null)
        assertFalse(result.ok)
        assertEquals("HTTPS required", result.detail)
    }

    @Test fun `malformed webhook URL is refused`() {
        val result = WebhookClient.call("POST", "not a URL", "{}")
        assertFalse(result.ok)
        assertEquals("invalid URL", result.detail)
    }
}
