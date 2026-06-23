// SPDX-License-Identifier: Apache-2.0
// Shim: bridges nav-core's expected `org.maplibre.geojson.model.Point`
// import path to the Java maplibre-gl 6.0.1 `org.maplibre.geojson.Point`
// class. Exposes both:
//   - A named-arg public constructor `Point(longitude, latitude[, altitude])`
//     that nav-core uses heavily (e.g. `Point(longitude = lon, latitude = lat)`).
//   - The Kotlin property accessors (`point.latitude` etc.) that nav-core
//     relies on — the underlying Java class only has `latitude()` methods.
package org.maplibre.geojson.model

class Point internal constructor(internal val raw: org.maplibre.geojson.Point) {

    /** Named-arg public constructor used throughout nav-core.
     *  altitude is nullable to match the upstream Kotlin port — most
     *  call sites pass `altitude = location.altitude` where the
     *  source is `Double?`. Null/NaN both mean "no altitude". */
    constructor(longitude: Double, latitude: Double, altitude: Double? = null) : this(
        if (altitude == null || altitude.isNaN())
            org.maplibre.geojson.Point.fromLngLat(longitude, latitude)
        else
            org.maplibre.geojson.Point.fromLngLat(longitude, latitude, altitude)
    )

    /** GeoJSON-style list constructor: [longitude, latitude] or
     *  [longitude, latitude, altitude]. Matches the 7.0.0-pre0
     *  Kotlin port's `Point(List<Double>)` constructor that
     *  GlassNav's Utils.java relies on. */
    constructor(coordinates: List<Double>) : this(
        when (coordinates.size) {
            2 -> org.maplibre.geojson.Point.fromLngLat(coordinates[0], coordinates[1])
            3 -> org.maplibre.geojson.Point.fromLngLat(coordinates[0], coordinates[1], coordinates[2])
            else -> throw IllegalArgumentException("Point requires 2 or 3 coordinates, got ${coordinates.size}")
        }
    )

    val latitude: Double get() = raw.latitude()
    val longitude: Double get() = raw.longitude()
    val altitude: Double? get() = if (raw.hasAltitude()) raw.altitude() else null
    val hasAltitude: Boolean get() = raw.hasAltitude()

    override fun equals(other: Any?): Boolean = other is Point && raw == other.raw
    override fun hashCode(): Int = raw.hashCode()
    override fun toString(): String = "Point(${raw.longitude()},${raw.latitude()})"

    companion object {
        @JvmStatic fun fromLngLat(longitude: Double, latitude: Double): Point =
            Point(longitude, latitude)
        @JvmStatic fun fromLngLat(longitude: Double, latitude: Double, altitude: Double): Point =
            Point(longitude, latitude, altitude)
    }
}
