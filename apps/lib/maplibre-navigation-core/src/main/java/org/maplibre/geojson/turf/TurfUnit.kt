// SPDX-License-Identifier: Apache-2.0
// Shim: replaces nav-core's expected `TurfUnit` enum from the
// 7.0.0-pre0 Kotlin port. The Java maplibre-turf 6.0.1 library
// uses `String` constants (TurfConstants.UNIT_*) for the same
// thing; this enum's `code` property surfaces those strings so
// our TurfMeasurement / TurfMisc shims can hand them straight
// to the Java implementations.
package org.maplibre.geojson.turf

enum class TurfUnit(internal val code: String) {
    METERS(org.maplibre.turf.TurfConstants.UNIT_METERS),
    KILOMETERS(org.maplibre.turf.TurfConstants.UNIT_KILOMETERS),
    MILES(org.maplibre.turf.TurfConstants.UNIT_MILES),
    NAUTICAL_MILES(org.maplibre.turf.TurfConstants.UNIT_NAUTICAL_MILES),
    DEGREES(org.maplibre.turf.TurfConstants.UNIT_DEGREES),
    RADIANS(org.maplibre.turf.TurfConstants.UNIT_RADIANS),
    INCHES(org.maplibre.turf.TurfConstants.UNIT_INCHES),
    YARDS(org.maplibre.turf.TurfConstants.UNIT_YARDS),
    CENTIMETERS(org.maplibre.turf.TurfConstants.UNIT_CENTIMETERS),
    FEET(org.maplibre.turf.TurfConstants.UNIT_FEET),
}
