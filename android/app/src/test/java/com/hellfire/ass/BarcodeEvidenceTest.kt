package com.hellfire.ass

import com.hellfire.ass.media.BarcodeEvidence
import org.junit.Assert.*
import org.junit.Test

class BarcodeEvidenceTest {
    @Test fun plainTextAndPublicLinksRemainUntrustedEvidence() {
        assertEquals("Habari dunia", BarcodeEvidence.safe(" Habari dunia "))
        assertEquals("https://example.invalid/public", BarcodeEvidence.safe("https://example.invalid/public"))
    }
    @Test fun credentialsActionsAndSecurityCodesAreNotIndexed() {
        for (text in listOf("WIFI:T:WPA;S:fixture;P:synthetic-secret;;", "otpauth://totp/fixture?secret=SYNTHETIC", "otpauth-migration://offline?data=fixture",
            "https://user:password@example.invalid/public", "https://example.invalid/?token=synthetic", "https://example.invalid/#synthetic-token",
            "intent://example.invalid", "javascript:alert(1)", "file:///data/data/fixture", "sms:123456", "123456", "Your OTP is 123456",
            "Nenosiri lako ni 123456", "x".repeat(2001))) assertNull(text, BarcodeEvidence.safe(text))
    }
    @Test fun incidentalCodeValuesAreRedactedThroughTheExistingPolicy() {
        assertEquals("Meeting code [CODE REDACTED]", BarcodeEvidence.safe("Meeting code 123456"))
    }
}
