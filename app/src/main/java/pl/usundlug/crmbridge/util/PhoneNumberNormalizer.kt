package pl.usundlug.crmbridge.util

object PhoneNumberNormalizer {
    fun normalizePolish(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var digits = raw.trim().replace(Regex("[^0-9+]"), "")
        if (digits.startsWith("00")) digits = "+" + digits.drop(2)
        if (digits.startsWith("+")) return "+" + digits.drop(1).filter(Char::isDigit)

        val onlyDigits = digits.filter(Char::isDigit)
        return when {
            onlyDigits.length == 9 -> "+48$onlyDigits"
            onlyDigits.startsWith("48") && onlyDigits.length == 11 -> "+$onlyDigits"
            else -> "+$onlyDigits"
        }
    }
}
