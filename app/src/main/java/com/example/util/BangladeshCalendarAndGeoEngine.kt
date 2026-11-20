package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class BanglaDateResult(
    val day: Int,
    val monthIndex: Int, // 1..12
    val monthNameBn: String,
    val monthNameEn: String,
    val banglaYear: Int,
    val seasonBn: String,
    val isLeapFalgun: Boolean,
    val formattedBengali: String
)

data class GeocodedPlaceResult(
    val latitude: Double,
    val longitude: Double,
    val displayName: String,
    val source: String // "COORDINATE_LITERAL", "BD_OFFLINE_INDEX", "ANDROID_GEOCODER", "NOMINATIM_OSM"
)

data class DistanceAndEtaResult(
    val distanceKm: Double,
    val distanceMeters: Long,
    val estimatedDriveMinutes: Int,
    val estimatedWalkMinutes: Int,
    val mapsRouteUri: String,
    val mapsNavigationUri: String,
    val summaryBengali: String,
    val routeSource: String = "ESTIMATED_STRAIGHT_LINE_HAVERSINE",
    val isExactRoadRoute: Boolean = false
)

/**
 * Revised Bangladesh Bangla Calendar (Bangla Academy 2019 Official Revision),
 * Asia/Dhaka Clock, Haversine Distance, ETA Estimation, and Google Maps Intent Launcher.
 */
object BangladeshCalendarAndGeoEngine {

    val DHAKA_ZONE: ZoneId = ZoneId.of("Asia/Dhaka")

    private val BANGLA_MONTHS_BN = listOf(
        "বৈশাখ", "জ্যৈষ্ঠ", "আষাঢ়", "শ্রাবণ", "ভাদ্র", "আশ্বিন",
        "কার্তিক", "অগ্রহায়ণ", "পৌষ", "মাঘ", "ফাল্গুন", "চৈত্র"
    )

    private val BANGLA_MONTHS_EN = listOf(
        "Boishakh", "Joishtho", "Asharh", "Srabon", "Bhadro", "Ashwin",
        "Kartik", "Ogrohayon", "Poush", "Magh", "Falgun", "Chaitra"
    )

    private val BANGLA_WEEKDAYS_BN = listOf(
        "সোমবার", "মঙ্গলবার", "বুধবার", "বৃহস্পতিবার", "শুক্রবার", "শনিবার", "রবিবার"
    )

    private val BANGLA_DIGITS = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')

    fun toBengaliDigits(number: Int): String = toBengaliDigits(number.toString())

    fun toBengaliDigits(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            if (ch in '0'..'9') {
                sb.append(BANGLA_DIGITS[ch - '0'])
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Converts any Gregorian LocalDate to the Revised Bangladesh Bangla Calendar (Bangla Academy 2019).
     * Rules:
     * - Pohela Boishakh is fixed on 14 April.
     * - First 6 months (Boishakh..Ashwin) = 31 days each.
     * - Kartik, Ogrohayon, Poush, Magh, Chaitra = 30 days each.
     * - Falgun = 29 days in normal years, 30 days when the Gregorian year containing February is a leap year.
     */
    fun toRevisedBangladeshBanglaDate(date: LocalDate): BanglaDateResult {
        val gYear = date.year
        val pohelaBoishakhThisYear = LocalDate.of(gYear, 4, 14)

        val banglaYearStartGregorianYear = if (!date.isBefore(pohelaBoishakhThisYear)) gYear else gYear - 1
        val boishakh1 = LocalDate.of(banglaYearStartGregorianYear, 4, 14)
        val banglaYear = banglaYearStartGregorianYear - 593

        // Falgun spans February of (banglaYearStartGregorianYear + 1)
        val febYear = banglaYearStartGregorianYear + 1
        val isLeap = isGregorianLeapYear(febYear)
        val falgunDays = if (isLeap) 30 else 29

        val monthLengths = intArrayOf(31, 31, 31, 31, 31, 31, 30, 30, 30, 30, falgunDays, 30)

        var remainingDays = ChronoUnit.DAYS.between(boishakh1, date).toInt()
        var monthIdx = 0
        while (monthIdx < 11 && remainingDays >= monthLengths[monthIdx]) {
            remainingDays -= monthLengths[monthIdx]
            monthIdx++
        }
        val bDay = remainingDays + 1
        val monthNumber = monthIdx + 1
        val monthBn = BANGLA_MONTHS_BN[monthIdx]
        val monthEn = BANGLA_MONTHS_EN[monthIdx]
        val seasonBn = when (monthNumber) {
            1, 2 -> "গ্রীষ্মকাল"
            3, 4 -> "বর্ষাকাল"
            5, 6 -> "শরৎকাল"
            7, 8 -> "হেমন্তকাল"
            9, 10 -> "শীতকাল"
            else -> "বসন্তকাল"
        }

        val formattedBn = "${toBengaliDigits(bDay)} $monthBn ${toBengaliDigits(banglaYear)} বঙ্গাব্দ ($seasonBn)"
        return BanglaDateResult(
            day = bDay,
            monthIndex = monthNumber,
            monthNameBn = monthBn,
            monthNameEn = monthEn,
            banglaYear = banglaYear,
            seasonBn = seasonBn,
            isLeapFalgun = isLeap,
            formattedBengali = formattedBn
        )
    }

    fun isGregorianLeapYear(year: Int): Boolean {
        return (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
    }

    fun getCurrentDhakaDateTimeReport(nowEpochMs: Long = System.currentTimeMillis()): JSONObject {
        val zoned: ZonedDateTime = Instant.ofEpochMilli(nowEpochMs).atZone(DHAKA_ZONE)
        val localDate = zoned.toLocalDate()
        val banglaDate = toRevisedBangladeshBanglaDate(localDate)
        val weekdayBn = BANGLA_WEEKDAYS_BN[(zoned.dayOfWeek.value - 1).coerceIn(0, 6)]

        val hour24 = zoned.hour
        val minute = zoned.minute
        val amPmBn = when {
            hour24 < 5 -> "ভোর"
            hour24 < 12 -> "সকাল"
            hour24 < 16 -> "দুপুর"
            hour24 < 18 -> "বিকাল"
            hour24 < 20 -> "সন্ধ্যা"
            else -> "রাত"
        }
        val hour12 = when {
            hour24 == 0 -> 12
            hour24 > 12 -> hour24 - 12
            else -> hour24
        }
        val timeBn = "$amPmBn ${toBengaliDigits(hour12)}:${toBengaliDigits(minute.toString().padStart(2, '0'))}"
        val englishDateStr = String.format(
            Locale.US,
            "%04d-%02d-%02d %02d:%02d (%s)",
            zoned.year,
            zoned.monthValue,
            zoned.dayOfMonth,
            hour24,
            minute,
            DHAKA_ZONE.id
        )

        val summaryBn = "বাংলাদেশ সময় (Asia/Dhaka): $weekdayBn, $timeBn | ইংরেজি তারিখ: ${toBengaliDigits(localDate.toString())} | বাংলা তারিখ (সংশোধিত বর্ষপঞ্জি): ${banglaDate.formattedBengali}।"

        return JSONObject().apply {
            put("success", true)
            put("timezone", DHAKA_ZONE.id)
            put("isoDate", localDate.toString())
            put("formattedDhakaTime", englishDateStr)
            put("weekdayBengali", weekdayBn)
            put("timeBengali", timeBn)
            put("banglaDay", banglaDate.day)
            put("banglaMonthIndex", banglaDate.monthIndex)
            put("banglaMonthBn", banglaDate.monthNameBn)
            put("banglaMonthEn", banglaDate.monthNameEn)
            put("banglaYear", banglaDate.banglaYear)
            put("banglaSeasonBn", banglaDate.seasonBn)
            put("banglaFormatted", banglaDate.formattedBengali)
            put("isLeapFalgun", banglaDate.isLeapFalgun)
            put("summaryBengali", summaryBn)
        }
    }

    /**
     * Exact Haversine great-circle distance in kilometers between two WGS84 lat/lng coordinates.
     */
    fun haversineDistanceKm(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val earthRadiusKm = 6371.0088
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return earthRadiusKm * c
    }

    @Volatile
    var geoHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun calculateDistanceAndEta(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
        originLabel: String = "বর্তমান অবস্থান",
        destLabel: String = "গন্তব্য"
    ): DistanceAndEtaResult {
        val distanceKm = haversineDistanceKm(originLat, originLng, destLat, destLng)
        val meters = (distanceKm * 1000.0).roundToInt().toLong()
        // Road detour factor (~1.25x straight-line) at avg 35 km/h drive, 4.8 km/h walk
        val effectiveRoadKm = distanceKm * 1.25
        val driveMin = ((effectiveRoadKm / 35.0) * 60.0).roundToInt().coerceAtLeast(if (meters < 100) 0 else 1)
        val walkMin = ((effectiveRoadKm / 4.8) * 60.0).roundToInt().coerceAtLeast(if (meters < 50) 0 else 1)

        val routeUri = "https://www.google.com/maps/dir/?api=1&origin=$originLat,$originLng&destination=$destLat,$destLng&travelmode=driving"
        val navUri = "google.navigation:q=$destLat,$destLng"

        val distFormatted = String.format(Locale.US, "%.2f", distanceKm)
        val summaryBn = "'$originLabel' থেকে '$destLabel'-এর আনুমানিক সরলরৈখিক (Haversine) দূরত্ব $distFormatted কিমি ($meters মিটার; লাইভ রোড রাউটিং সংযোগ না থাকায় আনুমানিক হিসাব)। আনুমানিক গাড়ি সময় ~${toBengaliDigits(driveMin)} মিনিট (হাঁটায় ~${toBengaliDigits(walkMin)} মিনিট)।"

        return DistanceAndEtaResult(
            distanceKm = distanceKm,
            distanceMeters = meters,
            estimatedDriveMinutes = driveMin,
            estimatedWalkMinutes = walkMin,
            mapsRouteUri = routeUri,
            mapsNavigationUri = navUri,
            summaryBengali = summaryBn,
            routeSource = "ESTIMATED_STRAIGHT_LINE_HAVERSINE",
            isExactRoadRoute = false
        )
    }

    suspend fun calculateWorldwideRouteAndEta(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
        originLabel: String = "বর্তমান অবস্থান",
        destLabel: String = "গন্তব্য",
        travelMode: String = "driving"
    ): DistanceAndEtaResult = withContext(Dispatchers.IO) {
        val normalizedMode = when (travelMode.trim().lowercase(Locale.ROOT)) {
            "walk", "walking", "foot", "হাঁটা", "হেঁটে" -> "walking"
            "cycle", "cycling", "bike", "bicycle", "bicycling", "সাইকেল" -> "cycling"
            "transit", "bus", "train", "public_transport", "বাস", "ট্রেন" -> "transit"
            else -> "driving"
        }
        val mapsMode = if (normalizedMode == "cycling") "bicycling" else normalizedMode
        val routeUri = "https://www.google.com/maps/dir/?api=1&origin=$originLat,$originLng&destination=$destLat,$destLng&travelmode=$mapsMode"
        val navUri = "google.navigation:q=$destLat,$destLng"

        // Public transit live schedules cannot be fetched via OSRM; return honest estimate + Google Maps transit URI
        if (normalizedMode == "transit") {
            val haversine = calculateDistanceAndEta(
                originLat = originLat,
                originLng = originLng,
                destLat = destLat,
                destLng = destLng,
                originLabel = originLabel,
                destLabel = destLabel
            )
            val estTransitMin = ((haversine.distanceKm / 25.0) * 60.0).roundToInt().coerceAtLeast(1)
            return@withContext haversine.copy(
                estimatedDriveMinutes = estTransitMin,
                mapsRouteUri = routeUri,
                summaryBengali = "'$originLabel' থেকে '$destLabel'-এ পাবলিক ট্রানজিট সময়সূচি সরাসরি OSRM-এ উপলব্ধ নয়; সরলরৈখিক দূরত্ব ${String.format(Locale.US, "%.2f", haversine.distanceKm)} কিমি ও আনুমানিক ট্রানজিট ~${toBengaliDigits(estTransitMin)} মিনিট। লাইভ বাস/ট্রেন রুটের জন্য Google Maps ট্রানজিট ব্যবহার করুন।",
                routeSource = "ESTIMATED_TRANSIT_UNAVAILABLE",
                isExactRoadRoute = false
            )
        }

        val osrmProfile = when (normalizedMode) {
            "walking" -> "foot"
            "cycling" -> "bike"
            else -> "driving"
        }

        // Query live OSRM road/foot/bike route first
        try {
            val osrmUrl = String.format(
                Locale.US,
                "https://router.project-osrm.org/route/v1/%s/%.6f,%.6f;%.6f,%.6f?overview=false",
                osrmProfile,
                originLng,
                originLat,
                destLng,
                destLat
            )
            val req = Request.Builder()
                .url(osrmUrl)
                .header("User-Agent", "NAHID-AI-Android/1.0")
                .get()
                .build()
            geoHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bodyStr = resp.body?.string().orEmpty()
                    val json = JSONObject(bodyStr)
                    val routes = json.optJSONArray("routes")
                    if (routes != null && routes.length() > 0) {
                        val first = routes.getJSONObject(0)
                        val roadMeters = first.optDouble("distance", -1.0).roundToInt().toLong()
                        val roadSeconds = first.optDouble("duration", -1.0)
                        if (roadMeters >= 0L && roadSeconds >= 0.0) {
                            val roadKm = roadMeters / 1000.0
                            val modeMin = (roadSeconds / 60.0).roundToInt().coerceAtLeast(if (roadMeters < 50) 0 else 1)
                            val walkMin = if (normalizedMode == "walking") {
                                modeMin
                            } else {
                                ((roadKm / 4.8) * 60.0).roundToInt().coerceAtLeast(if (roadMeters < 50) 0 else 1)
                            }
                            val distFmt = String.format(Locale.US, "%.2f", roadKm)
                            val modeBn = when (normalizedMode) {
                                "walking" -> "হাঁটার পথ"
                                "cycling" -> "সাইকেল রুট"
                                else -> "গাড়ির সড়ক রুট"
                            }
                            val summaryBn = "'$originLabel' থেকে '$destLabel'-এর প্রকৃত দূরত্ব $distFmt কিমি ($roadMeters মিটার, $modeBn — OSRM)। আনুমানিক সময় ~${toBengaliDigits(modeMin)} মিনিট (হাঁটায় ~${toBengaliDigits(walkMin)} মিনিট)।"
                            return@withContext DistanceAndEtaResult(
                                distanceKm = roadKm,
                                distanceMeters = roadMeters,
                                estimatedDriveMinutes = modeMin,
                                estimatedWalkMinutes = walkMin,
                                mapsRouteUri = routeUri,
                                mapsNavigationUri = navUri,
                                summaryBengali = summaryBn,
                                routeSource = if (normalizedMode == "driving") "OSRM_ROAD_ROUTE" else "OSRM_ROAD_ROUTE_${normalizedMode.uppercase(Locale.ROOT)}",
                                isExactRoadRoute = true
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        // Honest straight-line Haversine fallback when OSRM is unavailable/offline
        val fallback = calculateDistanceAndEta(
            originLat = originLat,
            originLng = originLng,
            destLat = destLat,
            destLng = destLng,
            originLabel = originLabel,
            destLabel = destLabel
        )
        fallback.copy(mapsRouteUri = routeUri)
    }

    suspend fun geocodeWorldwidePlace(context: Context?, query: String): GeocodedPlaceResult? = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isBlank()) return@withContext null

        // 1. Direct "lat,lng" coordinate pair
        val coordMatch = Regex("""(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)""").find(clean)
        if (coordMatch != null) {
            val lat = coordMatch.groupValues[1].toDoubleOrNull()
            val lng = coordMatch.groupValues[2].toDoubleOrNull()
            if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                return@withContext GeocodedPlaceResult(lat, lng, clean, "COORDINATE_LITERAL")
            }
        }

        // 2. Fast local Bangladesh landmark/district table
        resolveKnownPlaceCoordinates(clean)?.let { (lat, lng) ->
            return@withContext GeocodedPlaceResult(lat, lng, clean, "BD_OFFLINE_INDEX")
        }

        // 3. Worldwide Android Geocoder (if available on device)
        if (context != null) {
            try {
                if (android.location.Geocoder.isPresent()) {
                    val geocoder = android.location.Geocoder(context, Locale.getDefault())
                    @Suppress("DEPRECATION")
                    val results = geocoder.getFromLocationName(clean, 1)
                    val first = results?.firstOrNull()
                    if (first != null) {
                        val addrLine = first.getAddressLine(0)?.takeIf { it.isNotBlank() } ?: clean
                        return@withContext GeocodedPlaceResult(first.latitude, first.longitude, addrLine, "ANDROID_GEOCODER")
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 4. Worldwide OpenStreetMap Nominatim HTTPS Geocoding
        try {
            val encoded = URLEncoder.encode(clean, "UTF-8")
            val url = "https://nominatim.openstreetmap.org/search?format=json&limit=1&q=$encoded"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "NAHID-AI-Android/1.0")
                .header("Accept", "application/json")
                .get()
                .build()
            geoHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bodyStr = resp.body?.string().orEmpty().trim()
                    if (bodyStr.startsWith("[")) {
                        val arr = JSONArray(bodyStr)
                        if (arr.length() > 0) {
                            val obj = arr.getJSONObject(0)
                            val lat = obj.optString("lat", "").toDoubleOrNull()
                            val lon = obj.optString("lon", "").toDoubleOrNull()
                            val displayName = obj.optString("display_name", clean).ifBlank { clean }
                            if (lat != null && lon != null) {
                                return@withContext GeocodedPlaceResult(lat, lon, displayName, "NOMINATIM_OSM")
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        null
    }

    fun launchGoogleMapsRoute(
        context: Context,
        destinationQueryOrCoords: String,
        originLat: Double? = null,
        originLng: Double? = null,
        destLat: Double? = null,
        destLng: Double? = null,
        travelMode: String = "driving"
    ): JSONObject {
        val res = JSONObject()
        val mapsMode = when (travelMode.trim().lowercase(Locale.ROOT)) {
            "walking", "walk", "foot" -> "walking"
            "cycling", "cycle", "bike", "bicycling" -> "bicycling"
            "transit", "bus", "train" -> "transit"
            else -> "driving"
        }
        val uriString = when {
            originLat != null && originLng != null && destLat != null && destLng != null -> {
                "https://www.google.com/maps/dir/?api=1&origin=$originLat,$originLng&destination=$destLat,$destLng&travelmode=$mapsMode"
            }
            destLat != null && destLng != null -> {
                "google.navigation:q=$destLat,$destLng"
            }
            else -> {
                val encoded = URLEncoder.encode(destinationQueryOrCoords.trim(), "UTF-8")
                "https://www.google.com/maps/dir/?api=1&destination=$encoded&travelmode=$mapsMode"
            }
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
            setPackage("com.google.android.apps.maps")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            val pm = context.packageManager
            val resolved = pm.resolveActivity(intent, 0)
            if (resolved != null) {
                context.startActivity(intent)
                res.put("success", true)
                res.put("mapsUri", uriString)
                res.put("travelMode", mapsMode)
                res.put("summaryBengali", "Google Maps-এ '$destinationQueryOrCoords'-এর রুট নেভিগেশন ($mapsMode) চালু করা হয়েছে।")
            } else {
                val webFallbackUrl = if (uriString.startsWith("https://")) {
                    uriString
                } else {
                    "https://www.google.com/maps/search/?api=1&query=${URLEncoder.encode(destinationQueryOrCoords.trim(), "UTF-8")}"
                }
                val webFallback = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(webFallbackUrl)
                ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                if (pm.resolveActivity(webFallback, 0) != null) {
                    context.startActivity(webFallback)
                    res.put("success", true)
                    res.put("mapsUri", webFallbackUrl)
                    res.put("travelMode", mapsMode)
                    res.put("summaryBengali", "ব্রাউজারে Google Maps-এ '$destinationQueryOrCoords' রুট খোলা হয়েছে।")
                } else {
                    res.put("success", false)
                    res.put("mapsUri", uriString)
                    res.put("summaryBengali", "ডিভাইসে Google Maps বা ম্যাপ ব্রাউজার হ্যান্ডলার পাওয়া যায়নি।")
                }
            }
            res
        } catch (e: Exception) {
            res.put("success", false)
            res.put("mapsUri", uriString)
            res.put("summaryBengali", "Google Maps চালু করতে সমস্যা হয়েছে: ${e.localizedMessage}")
            res
        }
    }

    // Known major Bangladesh landmarks/districts for instant offline coordinate resolution
    private val KNOWN_BD_COORDS = mapOf(
        "ঢাকা" to (23.8103 to 90.4125),
        "dhaka" to (23.8103 to 90.4125),
        "বাগেরহাট" to (22.6516 to 89.7859),
        "bagerhat" to (22.6516 to 89.7859),
        "মোড়লগঞ্জ" to (22.4533 to 89.8583),
        "morrelganj" to (22.4533 to 89.8583),
        "খুলনা" to (22.8456 to 89.5403),
        "khulna" to (22.8456 to 89.5403),
        "চট্টগ্রাম" to (22.3569 to 91.7832),
        "chattogram" to (22.3569 to 91.7832),
        "chittagong" to (22.3569 to 91.7832),
        "সিলেট" to (24.8949 to 91.8687),
        "sylhet" to (24.8949 to 91.8687),
        "রাজশাহী" to (24.3745 to 88.6042),
        "rajshahi" to (24.3745 to 88.6042),
        "বরিশাল" to (22.7010 to 90.3535),
        "barishal" to (22.7010 to 90.3535)
    )

    fun resolveKnownPlaceCoordinates(placeName: String): Pair<Double, Double>? {
        val lower = placeName.trim().lowercase(Locale.ROOT)
        return KNOWN_BD_COORDS.entries.firstOrNull { (k, _) -> lower.contains(k) }?.value
    }
}
