package com.vibe.app.vpn

enum class VpnCountry(
    val code: String,
    val displayNameAr: String,
    val displayNameEn: String,
    val flag: String,
    val tunnelName: String,
) {
    EGYPT("EG", "مصر", "Egypt", "🇪🇬", "egypt"),
    JORDAN("JO", "الأردن", "Jordan", "🇯🇴", "jordan"),
    MOROCCO("MA", "المغرب", "Morocco", "🇲🇦", "morocco");

    val profileFileName: String
        get() = "$tunnelName.conf"

    companion object {
        fun fromCode(code: String?): VpnCountry? = entries.firstOrNull {
            it.code.equals(code, ignoreCase = true)
        }
    }
}
