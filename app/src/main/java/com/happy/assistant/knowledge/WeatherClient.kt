package com.happy.assistant.knowledge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.happy.assistant.core.HappyLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Open-Meteo: no key, no account, no rate limit worth worrying about.
 *
 * Spec section 7: weather has a deterministic source, so it must never be
 * answered by a language model. A model will happily invent a temperature and
 * sound certain about it.
 */
@Singleton
class WeatherClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
    private val json: Json,
    private val log: HappyLog,
) {

    private data class Place(val latitude: Double, val longitude: Double, val label: String)

    @Serializable
    private data class Forecast(val current: Current? = null, val daily: Daily? = null)

    @Serializable
    private data class Current(
        val temperature_2m: Double = 0.0,
        val apparent_temperature: Double = 0.0,
        val weather_code: Int = -1,
    )

    @Serializable
    private data class Daily(
        val temperature_2m_max: List<Double> = emptyList(),
        val temperature_2m_min: List<Double> = emptyList(),
        val weather_code: List<Int> = emptyList(),
    )

    @Serializable
    private data class GeoResults(val results: List<GeoPlace> = emptyList())

    @Serializable
    private data class GeoPlace(
        val name: String = "",
        val latitude: Double = 0.0,
        val longitude: Double = 0.0,
    )

    /**
     * [place] null means here, which needs location permission. A named place is
     * looked up by name and needs no permission at all.
     */
    suspend fun describe(place: String?, tomorrow: Boolean): String = withContext(Dispatchers.IO) {
        val target = if (place.isNullOrBlank()) {
            here() ?: return@withContext "I could not work out where you are. " +
                "Give me location permission, or name a city."
        } else {
            lookUp(place) ?: return@withContext "I could not find a place called $place."
        }

        val url = buildString {
            append(FORECAST)
            append("?latitude=").append(target.latitude)
            append("&longitude=").append(target.longitude)
            append("&current=temperature_2m,apparent_temperature,weather_code")
            if (tomorrow) {
                append("&daily=temperature_2m_max,temperature_2m_min,weather_code")
                append("&forecast_days=2")
            }
            append("&timezone=auto")
        }

        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    log.w(TAG, "open-meteo returned ${response.code}")
                    return@withContext "The weather service is not answering right now."
                }
                val forecast = json.decodeFromString<Forecast>(response.body?.string().orEmpty())
                if (tomorrow) spokenTomorrow(forecast, target.label) else spokenNow(forecast, target.label)
            }
        } catch (t: Throwable) {
            log.w(TAG, "weather lookup failed: ${t.javaClass.simpleName}")
            "I could not reach the weather service."
        }
    }

    private fun spokenNow(forecast: Forecast, label: String): String {
        val current = forecast.current ?: return "I did not get a reading back."
        val temp = current.temperature_2m.roundToInt()
        val feels = current.apparent_temperature.roundToInt()
        val where = if (label.isEmpty()) "" else " in $label"
        // Only mention the apparent temperature when it actually differs.
        val feelsPart = if (abs(temp - feels) >= 2) ", feels like $feels" else ""
        return "It is $temp degrees$where$feelsPart. ${describeCode(current.weather_code)}."
    }

    private fun spokenTomorrow(forecast: Forecast, label: String): String {
        val daily = forecast.daily ?: return "I did not get a forecast back."
        // Index 1 is tomorrow; index 0 is today.
        val high = daily.temperature_2m_max.getOrNull(1)?.roundToInt()
        val low = daily.temperature_2m_min.getOrNull(1)?.roundToInt()
        if (high == null || low == null) return "I did not get a forecast back."
        val where = if (label.isEmpty()) "" else " in $label"
        val sky = describeCode(daily.weather_code.getOrNull(1) ?: -1)
        return "Tomorrow$where, a high of $high and a low of $low. $sky."
    }

    /** Last known fix rather than a live one: weather does not need ten metres. */
    private fun here(): Place? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        return try {
            val manager = context.getSystemService(LocationManager::class.java)
            val best = manager.getProviders(true)
                .mapNotNull { provider -> manager.getLastKnownLocation(provider) }
                .maxByOrNull(Location::getTime) ?: return null
            Place(best.latitude, best.longitude, placeName(best))
        } catch (t: Throwable) {
            log.w(TAG, "could not read the last known location: ${t.javaClass.simpleName}")
            null
        }
    }

    /** Android's built-in geocoder, which is free and needs no key. */
    private fun placeName(location: Location): String = try {
        Geocoder(context, Locale.getDefault())
            .getFromLocation(location.latitude, location.longitude, 1)
            ?.firstOrNull()
            ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
            .orEmpty()
    } catch (t: Throwable) {
        ""
    }

    private fun lookUp(place: String): Place? {
        val url = "$GEOCODE?name=" + URLEncoder.encode(place.trim(), "UTF-8") + "&count=1"
        return try {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val found = json.decodeFromString<GeoResults>(response.body?.string().orEmpty())
                val first = found.results.firstOrNull() ?: return null
                Place(first.latitude, first.longitude, first.name)
            }
        } catch (t: Throwable) {
            log.w(TAG, "geocoding failed for \"$place\": ${t.javaClass.simpleName}")
            null
        }
    }

    /** WMO weather codes, as spoken English. */
    private fun describeCode(code: Int): String = when (code) {
        0 -> "Clear"
        1, 2 -> "Mostly clear"
        3 -> "Overcast"
        45, 48 -> "Foggy"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 80, 81 -> "Rain"
        65, 82 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71, 73, 75, 77, 85, 86 -> "Snow"
        95 -> "Thunderstorms"
        96, 99 -> "Thunderstorms with hail"
        else -> "Nothing unusual"
    }

    companion object {
        private const val TAG = "Weather"
        private const val FORECAST = "https://api.open-meteo.com/v1/forecast"
        private const val GEOCODE = "https://geocoding-api.open-meteo.com/v1/search"
    }
}
