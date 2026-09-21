package ac.mdiq.podcini.storage.utils

import ac.mdiq.podcini.utils.Logd
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.format.DateTimeFormat
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.alternativeParsing
import kotlinx.datetime.format.char
import kotlinx.datetime.format.optional
import kotlinx.datetime.toInstant
import kotlin.time.Instant

private const val TAG: String = "DateUtils"

private val StandardEnglishAbbrev = MonthNames.ENGLISH_ABBREVIATED

private val SeptEnglishAbbrev = MonthNames(listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sept", "Oct", "Nov", "Dec"))

private val EnglishFullNames = MonthNames.ENGLISH_FULL

private val KnownTimeZones = mapOf(
    // Common legacy US zones found in old feed generators
    "EDT" to "-0400", "EST" to "-0500",
    "CDT" to "-0500", "CST" to "-0600",
    "MDT" to "-0600", "MST" to "-0700",
    "PDT" to "-0700", "PST" to "-0800",

    // Common legacy European zones
    "CEST" to "+0200", "CET" to "+0100",
    "BST"  to "+0100",

    // Universal Standards (Mandated by RSS / RFC 822)
    "UT" to "+0000", "UTC" to "+0000", "GMT" to "+0000", "Z" to "+0000"
)
private fun createRssFormat(monthNames: MonthNames) = DateTimeComponents.Format {
    day()
    char(' ')
    monthName(monthNames)
    char(' ')
    year()
    char(' ')
    hour()
    char(':')
    minute()
    optional {
        char(':')
        second()
    }
    optional {
        char(' ')
        alternativeParsing({
            offset(UtcOffset.Formats.FOUR_DIGITS) // -0400
        }) {
            offset(UtcOffset.Formats.ISO)         // -04:00 or Z
        }
    }
}

private val CustomFormats: List<DateTimeFormat<DateTimeComponents>> = listOf(
    createRssFormat(StandardEnglishAbbrev),
    createRssFormat(SeptEnglishAbbrev),
    createRssFormat(EnglishFullNames),
    DateTimeComponents.Format {
        monthName(StandardEnglishAbbrev)
        char(' ')
        day()
        char(' ')
        hour()
        char(':')
        minute()
        char(':')
        second()
        char(' ')
        year()
    }
)

/**
 * Safely parses an external date string into a UTC [Instant].
 *
 * @param input Raw date string from external API, RSS feed, or database
 * @return UTC [Instant] if parsing succeeded, or `null` if invalid
 */
fun parseDate(input: String?): Instant? {
    if (input.isNullOrBlank()) return null

    val isoInstant = runCatching { Instant.parse(input.trim()) }.getOrNull()
    if (isoInstant != null) return isoInstant
    var date = input.trim().replace('/', '-').replace(Regex("\\s+"), " ")
    date = date.replace(Regex("([+-]\\d\\d):(\\d\\d)$"), "$1$2")
    KnownTimeZones.forEach { (zone, offset) -> if (date.endsWith(zone)) date = date.dropLast(zone.length) + offset }
    if (date.contains(',')) date = date.substringAfter(',').trim()

    runCatching { Instant.parse(date) }.getOrNull()?.let { return it }

    for (format in CustomFormats) {
        val components = format.parseOrNull(date) ?: continue
        val instantWithOffset = runCatching { components.toInstantUsingOffset() }.getOrNull()
        if (instantWithOffset != null) return instantWithOffset
        val localDateTime = runCatching { components.toLocalDateTime() }.getOrNull()
        if (localDateTime != null) return localDateTime.toInstant(TimeZone.UTC)
    }
    Logd(TAG) { "Could not parse date string \"$input\" [$date], likely an ETag" }
    return null
}
