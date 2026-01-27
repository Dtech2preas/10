package com.jonas.x24.network

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {
    // NOTE: This URL should be updated if the worker is deployed to a different address.
    private const val BASE_URL = "https://super-surf-282e.jonasmochebane.workers.dev/"

    val api: WorkerApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(WorkerApi::class.java)
    }
}
