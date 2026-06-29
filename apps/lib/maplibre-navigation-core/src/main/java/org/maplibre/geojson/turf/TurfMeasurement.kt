// SPDX-License-Identifier: Apache-2.0
// Shim: re-exports `TurfMeasurement` under nav-core's expected
// `org.maplibre.geojson.turf` package, delegating to the Java
// maplibre-turf 6.0.1 implementation.
package org.maplibre.geojson.turf

import org.maplibre.geojson.model.LineString
import org.maplibre.geojson.model.Point

object TurfMeasurement {
    @JvmStatic fun length(line: LineString, unit: TurfUnit): Double =
        org.maplibre.turf.TurfMeasurement.length(line.raw, unit.code)

    @JvmStatic fun length(coords: List<Point>, unit: TurfUnit): Double =
        org.maplibre.turf.TurfMeasurement.length(coords.map { it.raw }, unit.code)

    @JvmStatic fun along(line: LineString, distance: Double, unit: TurfUnit): Point =
        Point(org.maplibre.turf.TurfMeasurement.along(line.raw, distance, unit.code))

    @JvmStatic fun along(coords: List<Point>, distance: Double, unit: TurfUnit): Point =
        Point(org.maplibre.turf.TurfMeasurement.along(coords.map { it.raw }, distance, unit.code))

    @JvmStatic fun bearing(p1: Point, p2: Point): Double =
        org.maplibre.turf.TurfMeasurement.bearing(p1.raw, p2.raw)

    @JvmStatic fun distance(p1: Point, p2: Point): Double =
        org.maplibre.turf.TurfMeasurement.distance(p1.raw, p2.raw)

    @JvmStatic fun distance(p1: Point, p2: Point, unit: TurfUnit): Double =
        org.maplibre.turf.TurfMeasurement.distance(p1.raw, p2.raw, unit.code)

    @JvmStatic fun destination(point: Point, distance: Double, bearing: Double, unit: TurfUnit): Point =
        Point(org.maplibre.turf.TurfMeasurement.destination(point.raw, distance, bearing, unit.code))

    @JvmStatic fun midpoint(p1: Point, p2: Point): Point =
        Point(org.maplibre.turf.TurfMeasurement.midpoint(p1.raw, p2.raw))
}
