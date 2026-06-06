package com.jonas.x24.services

import retrofit2.Call
import retrofit2.http.GET

data class IpWhoisResponse(
    val ip: String?,
    val success: Boolean,
    val city: String?,
    val region: String?,
    val country: String?,
    val latitude: Double?,
    val longitude: Double?
)

interface IpWhoisApi {
    @GET("/")
    fun getLocationInfo(): Call<IpWhoisResponse>
}
