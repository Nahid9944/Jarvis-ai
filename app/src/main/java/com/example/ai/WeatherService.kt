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
    val source: String = "open-meteo"
)

object WeatherService {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

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
        val query = targetLocation.trim().lowercase(Locale.ROOT).ifBlank { "মোড়লগঞ্জ" }
        var resolvedName = targetLocation.trim().ifBlank { "মোড়লগঞ্জ, বাগেরহাট" }

        // Determine coordinates
        var coords = KNOWN_COORDINATES[query]
        if (coords == null) {
            // Check contains
            for ((key, pair) in KNOWN_COORDINATES) {
                if (query.contains(key) || key.contains(query)) {
                    coords = pair
                    break
                }
            }
        }

        // If still unknown, attempt Open-Meteo Geocoding
        if (coords == null) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=en&format=json"
                val geoReq = Request.Builder().url(geoUrl).build()
                val geoRes = httpClient.newCall(geoReq).execute()
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
                    }
                }
            } catch (e: Exception) {
                // Ignore geocoding failure, fallback to Morrelganj
            }
        }

        val (lat, lng) = coords ?: Pair(22.4578, 89.8576) // Default Morrelganj, Bagerhat
        if (coords == null && (query.contains("morrel") || query.contains("মোড়ল"))) {
            resolvedName = "মোড়লগঞ্জ, বাগেরহাট"
        }

        try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lng&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m&timezone=auto"
            val req = Request.Builder().url(url).build()
            val res = httpClient.newCall(req).execute()
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

                    val cond = decodeWeatherCode(wCode)
                    val rainSummary = if (precip > 0.0) {
                        "বর্তমানে বৃষ্টিপাত হচ্ছে (${String.format(Locale.US, "%.1f", precip)} মিমি)"
                    } else if (wCode in listOf(51, 53, 55, 61, 63, 65, 80, 81)) {
                        "বৃষ্টির সম্ভাবনা রয়েছে"
                    } else {
                        "বর্তমানে বৃষ্টির কোনো সম্ভাবনা নেই"
                    }

                    val summary = "${resolvedName}-এর লাইভ আবহাওয়া: তাপমাত্রা ${String.format(Locale.US, "%.1f", temp)}°C (অনুভূত ${String.format(Locale.US, "%.1f", feelsLike)}°C), আকাশ $cond। আর্দ্রতা $humidity%, বাতাসের গতি ${String.format(Locale.US, "%.1f", wind)} কিমি/ঘণ্টা এবং $rainSummary।"

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
                        source = "open-meteo"
                    )
                }
            }
        } catch (e: Exception) {
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
            source = "UNAVAILABLE_OFFLINE"
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
