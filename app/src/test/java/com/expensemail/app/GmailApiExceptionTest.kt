package com.expensemail.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GmailApiExceptionTest {
    @Test
    fun recognizesQuotaExceededMessage() {
        val error = GmailApiException(
            statusCode = 403,
            reason = "",
            retryAfterSeconds = null,
            message = "Quota exceeded for quota metric 'Total Query Cost' and limit 'Units per minute per user'."
        )

        assertTrue(error.isQuotaLimit)
    }

    @Test
    fun recognizesRateLimitReasonAndDoesNotTreatAuthorizationAsQuota() {
        assertTrue(GmailApiException(403, "userRateLimitExceeded", null, "Rate limited").isQuotaLimit)
        assertTrue(GmailApiException(429, "", null, "Too many requests").isQuotaLimit)
        assertFalse(GmailApiException(401, "authError", null, "Invalid credentials").isQuotaLimit)
    }
}
