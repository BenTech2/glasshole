// SPDX-License-Identifier: Apache-2.0
// Shim: bridges nav-core's expected `org.maplibre.geojson.model.LineString`
// to the Java maplibre-gl 6.0.1 `org.maplibre.geojson.LineString`.
package org.maplibre.geojson.model

class LineString internal constructor(internal val raw: org.maplibre.geojson.LineString) {
    /** Polyline-encoded constructor — matches the 7.0.0-pre0 Kotlin port API. */
    constructor(polyline: String, precision: Int) : this(
        org.maplibre.geojson.LineString.fromPolyline(polyline, precision)
    )
    /** Coordinate-list constructor — matches the 7.0.0-pre0 Kotlin port API. */
    constructor(coordinates: List<Point>) : this(
        org.maplibre.geojson.LineString.fromLngLats(coordinates.map { it.raw })
    )

    val coordinates: List<Point> get() = raw.coordinates().map { Point(it) }

    override fun equals(other: Any?): Boolean = other is LineString && raw == other.raw
    override fun hashCode(): Int = raw.hashCode()

    companion object {
        @JvmStatic fun fromLngLats(points: List<Point>): LineString =
            LineString(org.maplibre.geojson.LineString.fromLngLats(points.map { it.raw }))
        @JvmStatic fun fromPolyline(polyline: String, precision: Int): LineString =
            LineString(org.maplibre.geojson.LineString.fromPolyline(polyline, precision))
    }
}
