// SPDX-License-Identifier: Apache-2.0
// Shim: re-exports `TurfMisc` under nav-core's expected
// `org.maplibre.geojson.turf` package, delegating to Java
// maplibre-turf 6.0.1. `nearestPointOnLine` returns a tiny
// FeatureResult holding the geometry so nav-core's
// `.geometry as Point` casts stay valid.
package org.maplibre.geojson.turf

import org.maplibre.geojson.model.LineString
import org.maplibre.geojson.model.Point

/**
 * Minimal "Feature-like" return type for [TurfMisc.nearestPointOnLine].
 * nav-core only accesses `.geometry`, which it then casts to [Point].
 * Our [geometry] wraps the underlying maplibre Java Feature's geometry
 * (which is always a Point for nearestPointOnLine) back into the
 * shimmed [Point].
 */
class FeatureResult internal constructor(internal val raw: org.maplibre.geojson.Feature) {
    val geometry: Any?
        get() {
            val g = raw.geometry() ?: return null
            return when (g) {
                is org.maplibre.geojson.Point -> Point(g)
                is org.maplibre.geojson.LineString -> LineString(g)
                else -> g
            }
        }
}

object TurfMisc {
    @JvmStatic fun lineSlice(start: Point, end: Point, line: LineString): LineString =
        LineString(org.maplibre.turf.TurfMisc.lineSlice(start.raw, end.raw, line.raw))

    @JvmStatic fun lineSliceAlong(line: LineString, startDist: Double, stopDist: Double, unit: TurfUnit): LineString =
        LineString(org.maplibre.turf.TurfMisc.lineSliceAlong(line.raw, startDist, stopDist, unit.code))

    @JvmStatic fun nearestPointOnLine(point: Point, coords: List<Point>): FeatureResult =
        FeatureResult(
            org.maplibre.turf.TurfMisc.nearestPointOnLine(point.raw, coords.map { it.raw })
        )

    @JvmStatic fun nearestPointOnLine(point: Point, coords: List<Point>, unit: TurfUnit): FeatureResult =
        FeatureResult(
            org.maplibre.turf.TurfMisc.nearestPointOnLine(point.raw, coords.map { it.raw }, unit.code)
        )
}
