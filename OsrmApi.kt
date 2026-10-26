package com.campusrun.sim.route

import com.campusrun.sim.model.RoutePoint
import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * OSRM public routing service, GeoJSON geometry so no polyline decoding
 * is needed. Coordinates arrive as [longitude, latitude].
 */
private data class OsrmResponse(
    @SerializedName("routes") val routes: List<OsrmRoute> = emptyList(),
)

private data class OsrmRoute(
    @SerializedName("geometry") val geometry: OsrmGeometry? = null,
)

private data class OsrmGeometry(
    @SerializedName("coordinates") val coordinates: List<List<Double>> = emptyList(),
)

private interface OsrmService {
    @GET("route/v1/driving/{coords}?overview=full&geometries=geojson")
    suspend fun route(@Path("coords") coords: String): OsrmResponse
}

object OsrmApi {
    private val service: OsrmService = Retrofit.Builder()
        .baseUrl("https://router.project-osrm.org/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(OsrmService::class.java)

    /** Build a driving route through [points] in order. Returns null on failure. */
    suspend fun routeThrough(points: List<RoutePoint>): List<RoutePoint>? {
        if (points.size < 2) return null
        val coords = points.joinToString(";") { "${it.longitude},${it.latitude}" }
        return try {
            val response = service.route(coords)
            val raw = response.routes.firstOrNull()?.geometry?.coordinates
                ?: return null
            val parsed = raw.mapNotNull { pair ->
                if (pair.size >= 2) RoutePoint(latitude = pair[1], longitude = pair[0]) else null
            }
            parsed.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }
}
