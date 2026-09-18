package pl.usundlug.crmbridge.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneNumberNormalizerTest {
    @Test fun polishNineDigits() {
        assertEquals("+48573481685", PhoneNumberNormalizer.normalizePolish("573 481 685"))
    }

    @Test fun countryCodeWithoutPlus() {
        assertEquals("+48573481685", PhoneNumberNormalizer.normalizePolish("48573481685"))
    }

    @Test fun doubleZeroPrefix() {
        assertEquals("+48573481685", PhoneNumberNormalizer.normalizePolish("0048 573 481 685"))
    }
}
