package com.example.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

data class LiveWeatherData(
    val locationName: String,
    val temperatureC: Double,
    val feelsLikeC: Double,
    val conditionBengali: String,
    val humidityPercent: Int,
    val windSpeedKmh: Double,
    val precipitationMm: Double,
    val rainSummaryBengali: String,
    val fullSummaryBengali: String,
    val isLiveData: Boolean = true,
    val source: String = "open-meteo",
    val errorCode: String? = null,
    val lastUpdatedMillis: Long = System.currentTimeMillis(),
    val hourlySummary: List<String> = emptyList(),
    val dailyForecast: List<String> = emptyList()
)

object WeatherService {

    @Volatile
    var httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    @Volatile
    var geocodingBaseUrl: String = "https://geocoding-api.open-meteo.com/v1/search"

    @Volatile
    var forecastBaseUrl: String = "https://api.open-meteo.com/v1/forecast"

    // Pre-mapped Coordinates for Morrelganj & Major Bangladesh Regions
    private val KNOWN_COORDINATES = mapOf(
        "morrelganj" to Pair(22.4578, 89.8576),
        "মোড়লগঞ্জ" to Pair(22.4578, 89.8576),
        "মোরলগঞ্জ" to Pair(22.4578, 89.8576),
        "bagerhat" to Pair(22.6602, 89.7895),
        "বাগেরহাট" to Pair(22.6602, 89.7895),
        "khulna" to Pair(22.8456, 89.5403),
        "খুলনা" to Pair(22.8456, 89.5403),
        "dhaka" to Pair(23.8103, 90.4125),
        "ঢাকা" to Pair(23.8103, 90.4125),
        "chittagong" to Pair(22.3569, 91.7832),
        "চট্টগ্রাম" to Pair(22.3569, 91.7832),
        "barisal" to Pair(22.7010, 90.3535),
        "বরিশাল" to Pair(22.7010, 90.3535),
        "rajshahi" to Pair(24.3745, 88.6042),
        "রাজশাহী" to Pair(24.3745, 88.6042),
        "sylhet" to Pair(24.8949, 91.8687),
        "সিলেট" to Pair(24.8949, 91.8687),
        "rangpur" to Pair(25.7439, 89.2752),
        "রংপুর" to Pair(25.7439, 89.2752),
        "mymensingh" to Pair(24.7471, 90.4203),
        "ময়মনসিংহ" to Pair(24.7471, 90.4203),
        "comilla" to Pair(23.4607, 91.1809),
        "কুমিল্লা" to Pair(23.4607, 91.1809),
        "jessore" to Pair(23.1664, 89.2081),
        "যশোর" to Pair(23.1664, 89.2081),
        "cox's bazar" to Pair(21.4272, 92.0058),
        "কক্সবাজার" to Pair(21.4272, 92.0058),
        "mongla" to Pair(22.4883, 89.6083),
        "মোংলা" to Pair(22.4883, 89.6083),
        "pirojpur" to Pair(22.5841, 89.9720),
        "পিরোজপুর" to Pair(22.5841, 89.9720)
    )

    suspend fun fetchWeather(targetLocation: String = "মোড়লগঞ্জ"): LiveWeatherData = withContext(Dispatchers.IO) {
        val rawInput = targetLocation.trim()
        val hasExplicitQuery = rawInput.isNotBlank()
        val query = rawInput.lowercase(Locale.ROOT).ifBlank { "মোড়লগঞ্জ" }
        var resolvedName = rawInput.ifBlank { "মোড়লগঞ্জ, বাগেরহাট" }

        // Determine coordinates — exact lookup first, then whole-word/long-query containment (avoid false matches on short strings like "bar")
        var coords = KNOWN_COORDINATES[query]
        if (coords == null && query.length >= 4) {
            for ((key, pair) in KNOWN_COORDINATES) {
                if (query.contains(key)) {
                    coords = pair
                    break
                }
            }
        }

        var geocodingFailedNetwork = false
        var geocodingNoResults = false

        // Attempt Open-Meteo Geocoding when city is not in known coordinates table
        if (coords == null) {
            try {
                val encoded = URLEncoder.encode(rawInput.ifBlank { query }, "UTF-8")
                val geoUrl = "$geocodingBaseUrl?name=$encoded&count=1&language=en&format=json"
                val geoReq = Request.Builder().url(geoUrl).build()
                httpClient.newCall(geoReq).execute().use { geoRes ->
                    if (geoRes.isSuccessful) {
                        val geoBody = geoRes.body?.string() ?: ""
                        val geoJson = JSONObject(geoBody)
                        val results = geoJson.optJSONArray("results")
                        if (results != null && results.length() > 0) {
                            val first = results.getJSONObject(0)
                            val lat = first.optDouble("latitude")
                            val lng = first.optDouble("longitude")
                            val name = first.optString("name", resolvedName)
                            val country = first.optString("country", "")
                            resolvedName = if (country.isNotBlank()) "$name, $country" else name
                            coords = Pair(lat, lng)
                        } else {
                            geocodingNoResults = true
                        }
                    } else {
                        geocodingFailedNetwork = true
                    }
                }
            } catch (_: Exception) {
                geocodingFailedNetwork = true
            }
        }

        // Truthfulness guarantee: NEVER silently substitute Morrelganj coordinates when an explicit query could not be geocoded!
        if (coords == null) {
            return@withContext if (geocodingNoResults && hasExplicitQuery) {
                LiveWeatherData(
                    locationName = resolvedName,
                    temperatureC = 0.0,
                    feelsLikeC = 0.0,
                    conditionBengali = "অবস্থান খুঁজে পাওয়া যায়নি",
                    humidityPercent = 0,
                    windSpeedKmh = 0.0,
                    precipitationMm = 0.0,
                    rainSummaryBengali = "অজানা শহর/স্থান",
                    fullSummaryBengali = "'$resolvedName' নামের কোনো স্থান আবহাওয়া ডাটাবেজে খুঁজে পাওয়া যায়নি। অনুগ্রহ করে সঠিক শহর বা জেলার নাম বলুন।",
                    isLiveData = false,
                    source = "LOCATION_NOT_FOUND",
                    errorCode = "LOCATION_NOT_FOUND"
                )
            } else {
                LiveWeatherData(
                    locationName = resolvedName,
                    temperatureC = 0.0,
                    feelsLikeC = 0.0,
                    conditionBengali = "লাইভ তথ্য পাওয়া যায়নি (অফলাইন/সংযোগ ত্রুটি)",
                    humidityPercent = 0,
                    windSpeedKmh = 0.0,
                    precipitationMm = 0.0,
                    rainSummaryBengali = "লাইভ পূর্বাভাস অনুপলব্ধ",
                    fullSummaryBengali = "'$resolvedName'-এর অবস্থান যাচাই ও লাইভ আবহাওয়া তথ্য সংগ্রহ করা যায়নি (${if (geocodingFailedNetwork) "ইন্টারনেট বা সার্ভার সংযোগ বিচ্ছিন্ন" else "লোকেশন অনুপলব্ধ"})।",
                    isLiveData = false,
                    source = "UNAVAILABLE_OFFLINE",
                    errorCode = "UNAVAILABLE_OFFLINE"
                )
            }
        }

        val (lat, lng) = coords!!
        if (query.contains("morrel") || query.contains("মোড়ল") || query.contains("মোরল")) {
            resolvedName = "মোড়লগঞ্জ, বাগেরহাট"
        }

        try {
            val url = "$forecastBaseUrl?latitude=$lat&longitude=$lng" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m" +
                "&hourly=temperature_2m,precipitation_probability,weather_code" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum" +
                "&forecast_days=3&timezone=auto"
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: ""
                    val json = JSONObject(body)
                    val current = json.optJSONObject("current")
                    if (current != null) {
                        val temp = current.optDouble("temperature_2m", 28.5)
                        val feelsLike = current.optDouble("apparent_temperature", temp + 2.0)
                        val humidity = current.optInt("relative_humidity_2m", 70)
                        val wind = current.optDouble("wind_speed_10m", 10.0)
                        val precip = current.optDouble("precipitation", 0.0)
                        val wCode = current.optInt("weather_code", 1)

                        val hourlyList = mutableListOf<String>()
                        val hourlyObj = json.optJSONObject("hourly")
                        if (hourlyObj != null) {
                            val hTimes = hourlyObj.optJSONArray("time")
                            val hTemps = hourlyObj.optJSONArray("temperature_2m")
                            val hProbs = hourlyObj.optJSONArray("precipitation_probability")
                            if (hTimes != null && hTemps != null) {
                                val count = minOf(6, hTimes.length(), hTemps.length())
                                for (i in 0 until count) {
                                    val tLabel = hTimes.optString(i, "").substringAfter("T", hTimes.optString(i, ""))
                                    val tVal = String.format(Locale.US, "%.0f°C", hTemps.optDouble(i))
                                    val pVal = if (hProbs != null && i < hProbs.length()) " (বৃষ্টি ${hProbs.optInt(i)}%)" else ""
                                    hourlyList.add("$tLabel: $tVal$pVal")
                                }
                            }
                        }

                        val dailyList = mutableListOf<String>()
                        val dailyObj = json.optJSONObject("daily")
                        if (dailyObj != null) {
                            val dTimes = dailyObj.optJSONArray("time")
                            val dMax = dailyObj.optJSONArray("temperature_2m_max")
                            val dMin = dailyObj.optJSONArray("temperature_2m_min")
                            val dRain = dailyObj.optJSONArray("precipitation_sum")
                            if (dTimes != null && dMax != null && dMin != null) {
                                val count = minOf(3, dTimes.length(), dMax.length(), dMin.length())
                                for (i in 0 until count) {
                                    val dayLabel = dTimes.optString(i, "")
                                    val minS = String.format(Locale.US, "%.0f", dMin.optDouble(i))
                                    val maxS = String.format(Locale.US, "%.0f", dMax.optDouble(i))
                                    val rainS = if (dRain != null && i < dRain.length()) String.format(Locale.US, ", বৃষ্টি %.1fমিমি", dRain.optDouble(i)) else ""
                                    dailyList.add("$dayLabel: $minS°–$maxS°C$rainS")
                                }
                            }
                        }

                        val cond = decodeWeatherCode(wCode)
                        val rainSummary = if (precip > 0.0) {
                            "বর্তমানে বৃষ্টিপাত হচ্ছে (${String.format(Locale.US, "%.1f", precip)} মিমি)"
                        } else if (wCode in listOf(51, 53, 55, 61, 63, 65, 80, 81)) {
                            "বৃষ্টির সম্ভাবনা রয়েছে"
                        } else {
                            "বর্তমানে বৃষ্টির কোনো সম্ভাবনা নেই"
                        }

                        val dailyText = if (dailyList.isNotEmpty()) "\nআগামী দিনের পূর্বাভাস: ${dailyList.joinToString(" | ")}" else ""
                        val summary = "${resolvedName}-এর লাইভ আবহাওয়া: তাপমাত্রা ${String.format(Locale.US, "%.1f", temp)}°C (অনুভূত ${String.format(Locale.US, "%.1f", feelsLike)}°C), আকাশ $cond। আর্দ্রতা $humidity%, বাতাসের গতি ${String.format(Locale.US, "%.1f", wind)} কিমি/ঘণ্টা এবং $rainSummary।$dailyText"

                        return@withContext LiveWeatherData(
                            locationName = resolvedName,
                            temperatureC = temp,
                            feelsLikeC = feelsLike,
                            conditionBengali = cond,
                            humidityPercent = humidity,
                            windSpeedKmh = wind,
                            precipitationMm = precip,
                            rainSummaryBengali = rainSummary,
                            fullSummaryBengali = summary,
                            isLiveData = true,
                            source = "open-meteo",
                            errorCode = null,
                            lastUpdatedMillis = System.currentTimeMillis(),
                            hourlySummary = hourlyList,
                            dailyForecast = dailyList
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // Fall through to honest offline/unavailable response
        }

        return@withContext LiveWeatherData(
            locationName = resolvedName,
            temperatureC = 0.0,
            feelsLikeC = 0.0,
            conditionBengali = "লাইভ তথ্য পাওয়া যায়নি (অফলাইন/সংযোগ ত্রুটি)",
            humidityPercent = 0,
            windSpeedKmh = 0.0,
            precipitationMm = 0.0,
            rainSummaryBengali = "লাইভ পূর্বাভাস অনুপলব্ধ",
            fullSummaryBengali = "${resolvedName}-এর লাইভ আবহাওয়া তথ্য এই মুহূর্তে সংগ্রহ করা যায়নি (ইন্টারনেট বা সার্ভার সংযোগ বিচ্ছিন্ন)।",
            isLiveData = false,
            source = "UNAVAILABLE_OFFLINE",
            errorCode = "UNAVAILABLE_OFFLINE"
        )
    }

    private fun decodeWeatherCode(code: Int): String {
        return when (code) {
            0 -> "পরিষ্কার আকাশ (Clear Sky)"
            1 -> "প্রধানত পরিষ্কার (Mainly Clear)"
            2 -> "আংশিক মেঘলা (Partly Cloudy)"
            3 -> "মেঘলা ও কুয়াশাচ্ছন্ন (Overcast)"
            45, 48 -> "কুয়াশাচ্ছন্ন (Foggy)"
            51, 53, 55 -> "হালকা গুঁড়ি গুঁড়ি বৃষ্টি (Drizzle)"
            61, 63, 65 -> "বৃষ্টিপাত (Rain)"
            66, 67 -> "শীতল বৃষ্টি (Freezing Rain)"
            71, 73, 75 -> "তুষারপাত (Snow fall)"
            80, 81, 82 -> "বর্ষণ ও মাঝারি বৃষ্টি (Rain Showers)"
            95, 96, 99 -> "বজ্রঝড় ও কালবৈশাখী (Thunderstorm)"
            else -> "স্বাভাবিক রৌদ্রোজ্জ্বল ও মেঘলা আবহাওয়া"
        }
    }
}
