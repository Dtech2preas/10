package com.jonas.x24.services

import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.Call

data class WeatherResponse(
    val current_weather: CurrentWeather?
)

data class CurrentWeather(
    val temperature: Double?,
    val windspeed: Double?,
    val winddirection: Double?,
    val weathercode: Int?
)

interface WeatherApi {
    @GET("v1/forecast")
    fun getCurrentWeather(
        @Query("latitude") lat: Double,
        @Query("longitude") lon: Double,
        @Query("current_weather") currentWeather: Boolean = true
    ): Call<WeatherResponse>
}
