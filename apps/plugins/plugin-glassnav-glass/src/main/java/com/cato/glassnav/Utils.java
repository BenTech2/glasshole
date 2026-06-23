package com.cato.glassnav;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.maplibre.geojson.model.Point;
import org.maplibre.navigation.core.models.DirectionsResponse;
import org.maplibre.navigation.core.models.DirectionsRoute;
import org.maplibre.navigation.core.models.ManeuverModifier;
import org.maplibre.navigation.core.models.RouteOptions;
import org.maplibre.navigation.core.models.StepManeuver;
import org.oscim.core.GeoPoint;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class Utils {
    static LocationInfo selectedInfo;
    private static final String TAG = "GlassNav Utils";
    static class LocationInfo {
        String name;
        String displayName;
        GeoPoint location;
        float distance; // In meters

        public LocationInfo(String name, String displayName, GeoPoint location, float distance) {
            this.name = name;
            this.displayName = displayName;
            this.location = location;
            this.distance = distance;
        }
    }

    static org.maplibre.navigation.core.location.Location androidLocationtoMapLibreLocation(android.location.Location location) {
        return new org.maplibre.navigation.core.location.Location(
                location.getLatitude(),
                location.getLongitude(),
                location.getAccuracy(),
                location.getAltitude(),
                null,
                null,
                null,
                location.getSpeed(),
                location.getBearing(),
                System.currentTimeMillis(),
                location.getProvider()
        );
    }

    static void startMainActivity(Context context, int action) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("action", action);
        context.startActivity(intent);
    }

    public static void getRoute(double latitude, double longitude, String name, String costing, Context context) {
        //FIXME: Crash if lastlocation is null
        Log.d(TAG, "Getting route to " + latitude + ", " + longitude);
        JSONObject request = new JSONObject();
        try {
            JSONArray locations = new JSONArray();
            JSONObject startPos = new JSONObject();
            JSONObject endPos = new JSONObject();
            startPos.put("lat", MainActivity.lastLocation.getLatitude());
            startPos.put("lon", MainActivity.lastLocation.getLongitude());
            endPos.put("lat", latitude);
            endPos.put("lon", longitude);
            endPos.put("name", name);
            locations.put(startPos);
            locations.put(endPos);
            //JSONObject costingOptions = new JSONObject();
            //JSONObject costingType = new JSONObject();
            //costingType.put("top_speed", 130); //
            //costingOptions.put(costing, costingType);
            //request.put("costing_options", costingOptions);
            request.put("locations", locations);
            request.put("costing", costing);
            request.put("format", "osrm");
            request.put("banner_instructions", true);
            request.put("voice_instructions", true);
            request.put("language", "en-US");
            Log.i(TAG, "Route request: " + request.toString());
        } catch (JSONException e) {
            Log.e(TAG, "An error occurred: " + e);
        }
        HttpsUtils.makePostRequest(MainActivity.client, "https://valhalla1.openstreetmap.de/route", request, "POST", new HttpsUtils.HttpCallback() {

            @Override
            public void onSuccess(String response) {
                Log.i(TAG, "Route response: " + response);
                DirectionsRoute route = DirectionsResponse.fromJson(response).getRoutes().get(0);

                List<Point> coordinates = Arrays.asList(new Point(List.of(MainActivity.lastLocation.getLongitude(), MainActivity.lastLocation.getLatitude())), new Point(List.of(longitude, latitude)));
                RouteOptions routeOptions = new RouteOptions(
                        "https://valhalla.routing",   // baseUrl
                        "valhalla",                   // user
                        "valhalla",                   // profile
                        coordinates,                  // coordinates
                        null,                         // alternatives
                        Locale.getDefault().getLanguage(), // language
                        null,                         // radiuses
                        null,                         // bearings
                        null,                         // continueStraight
                        null,                         // roundaboutExits
                        null,                         // geometries
                        null,                         // overview
                        null,                         // steps
                        null,                         // annotations
                        null,                         // exclude
                        true,                         // voiceInstructions
                        true,                         // bannerInstructions
                        null,                         // voiceUnits
                        "valhalla",                   // accessToken
                        "0000-0000-0000-0000",         // requestUuid
                        null,                         // approaches
                        null,                         // waypointIndices
                        null,                         // waypointNames
                        null,                         // waypointTargets
                        null,                         // walkingOptions
                        null                          // snappingClosures
                );

                MainActivity.route = route.toBuilder().withRouteOptions(routeOptions).build();
                startMainActivity(context, 0);
            }

            @Override
            public void onError(String errorMessage) {
                Log.e(TAG, "Route request error: " + errorMessage);
                // Valhalla returns DistanceExceeded when walk/cycle
                // exceeds its ~250 km pedestrian limit. Auto-retry
                // with driving (much higher limit) — the user's
                // intent was clearly to navigate THERE, just on the
                // wrong mode.
                boolean distanceExceeded =
                    errorMessage != null && errorMessage.contains("DistanceExceeded");
                final android.os.Handler ui =
                    new android.os.Handler(android.os.Looper.getMainLooper());
                if (distanceExceeded && !"auto".equals(costing)) {
                    ui.post(() -> android.widget.Toast.makeText(
                        context,
                        "Too far for " + costing + " — retrying as driving",
                        android.widget.Toast.LENGTH_LONG
                    ).show());
                    MainActivity.mode = MainActivity.Mode.DRIVE;
                    getRoute(latitude, longitude, name, "auto", context);
                    return;
                }
                ui.post(() -> {
                    String msg;
                    if (distanceExceeded) {
                        msg = "Route too long for Valhalla's limits.";
                    } else if (errorMessage != null && errorMessage.contains("NoSegments")) {
                        msg = "No road network at that destination.";
                    } else {
                        msg = "Routing service error.";
                    }
                    android.widget.Toast.makeText(context, msg,
                        android.widget.Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /** Distance in meters → display string respecting the user's
     *  Distance Units setting in the plugin config.
     *
     *  Metric: ≥1km → "X.X km", else → "X m".
     *  Imperial: ≥528 ft (0.1 mi) → "X.X mi", else → "X ft". */
    public static String formatDistance(android.content.Context ctx, float distanceMeters) {
        boolean imperial = "imperial".equals(
            ctx.getSharedPreferences(GlassNavPluginService.PREFS_NAME, Context.MODE_PRIVATE)
                .getString("units", "imperial"));
        if (imperial) {
            double miles = distanceMeters / 1609.344;
            if (miles >= 0.1) return String.format("%.1f mi", miles);
            int feet = (int) Math.round(distanceMeters * 3.28084);
            return feet + " ft";
        }
        if (distanceMeters >= 1000) return String.format("%.1f km", distanceMeters / 1000.0);
        return Math.round(distanceMeters) + " m";
    }

    /** Legacy overload — callers without a Context fall back to metric. */
    public static String formatDistance(float distance) {
        if (distance >= 1000) return Math.round(distance / 1000) + " km";
        return Math.round(distance) + " m";
    }

    /** Time remaining in seconds → display string respecting the
     *  user's ETA Display setting.
     *
     *  arrival (default): "ETA 5:30 PM"
     *  remaining: "X min" / "Xh Ym" */
    public static String formatEta(android.content.Context ctx, double durationRemainingSec) {
        String format = ctx.getSharedPreferences(GlassNavPluginService.PREFS_NAME, Context.MODE_PRIVATE)
            .getString("eta_format", "arrival");
        if ("arrival".equals(format)) {
            long arriveMs = System.currentTimeMillis() + (long) (durationRemainingSec * 1000);
            return java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                .format(new java.util.Date(arriveMs));
        }
        int totalMin = (int) Math.round(durationRemainingSec / 60.0);
        if (totalMin < 60) return totalMin + " min";
        int hours = totalMin / 60;
        int mins = totalMin % 60;
        return mins == 0 ? hours + "h" : hours + "h " + mins + "m";
    }

    static boolean isNetworkConnected(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        return cm.getActiveNetworkInfo() != null && cm.getActiveNetworkInfo().isConnected();
    }

    static Integer getImageFromManuever(StepManeuver maneuver) {
        int image = R.drawable.da_turn_unknown;
        ManeuverModifier.Type modifier = maneuver.getModifier();
        switch (maneuver.getType()) {
            case FORK:
                if (modifier == ManeuverModifier.Type.RIGHT || modifier == ManeuverModifier.Type.SLIGHT_RIGHT) {
                    image = R.drawable.da_turn_fork_right;
                } else if (modifier == ManeuverModifier.Type.LEFT || modifier == ManeuverModifier.Type.SLIGHT_LEFT) {
                    image = R.drawable.da_turn_fork_left;
                } else if (modifier == ManeuverModifier.Type.STRAIGHT) {
                    image = R.drawable.da_turn_straight;
                } else {
                    Log.e(TAG, "FORK image for " + modifier.toString() + " not implemented!");
                }
                break;
            case END_OF_ROAD:
            case ON_RAMP:
            case ROUNDABOUT_TURN:
            case TURN:
                if (modifier == ManeuverModifier.Type.SLIGHT_RIGHT) {
                    image = R.drawable.da_turn_slight_right;
                } else if (modifier == ManeuverModifier.Type.RIGHT) {
                    image = R.drawable.da_turn_right;
                } else if (modifier == ManeuverModifier.Type.SHARP_RIGHT) {
                    image = R.drawable.da_turn_sharp_right;
                } else if (modifier == ManeuverModifier.Type.SLIGHT_LEFT) {
                    image = R.drawable.da_turn_slight_left;
                } else if (modifier == ManeuverModifier.Type.LEFT) {
                    image = R.drawable.da_turn_left;
                } else if (modifier == ManeuverModifier.Type.SHARP_LEFT) {
                    image = R.drawable.da_turn_sharp_left;
                } else if (modifier == ManeuverModifier.Type.UTURN) {
                    image = R.drawable.da_turn_uturn;
                } else {
                    Log.e(TAG, "TURN image for " + modifier.toString() + " not implemented!");
                }
                break;
            case MERGE:
                image = R.drawable.da_turn_generic_merge;
                break;
            case ARRIVE:
                image = R.drawable.da_turn_arrive;
                break;
            case DEPART:
                image = R.drawable.da_depart;
                break;
            case ROTARY:
            case ROUNDABOUT:
                if (modifier == ManeuverModifier.Type.SLIGHT_RIGHT) {
                    image = R.drawable.da_turn_roundabout_3;
                } else if (modifier == ManeuverModifier.Type.RIGHT) {
                    image = R.drawable.da_turn_roundabout_2;
                } else if (modifier == ManeuverModifier.Type.SHARP_RIGHT) {
                    image = R.drawable.da_turn_roundabout_1;
                } else if (modifier == ManeuverModifier.Type.SLIGHT_LEFT) {
                    image = R.drawable.da_turn_roundabout_5;
                } else if (modifier == ManeuverModifier.Type.LEFT) {
                    image = R.drawable.da_turn_roundabout_6;
                } else if (modifier == ManeuverModifier.Type.SHARP_LEFT) {
                    image = R.drawable.da_turn_roundabout_7;
                } else if (modifier == ManeuverModifier.Type.STRAIGHT) {
                    image = R.drawable.da_turn_roundabout_4;
                } else if (modifier == ManeuverModifier.Type.UTURN) {
                    image = R.drawable.da_turn_uturn;
                } else {
                    Log.e(TAG, "ROUNDABOUT image for " + modifier.toString() + " not implemented!");
                }
                break;
            case CONTINUE:
                image = R.drawable.da_turn_straight;
                break;
            case NEW_NAME:
                Log.e(TAG, "NEW_NAME image not implemented!");
                break;
            case OFF_RAMP:
                if (modifier == ManeuverModifier.Type.RIGHT || modifier == ManeuverModifier.Type.SLIGHT_RIGHT) {
                    image = R.drawable.da_turn_ramp_right;
                } else if (modifier == ManeuverModifier.Type.LEFT || modifier == ManeuverModifier.Type.SLIGHT_LEFT) {
                    image = R.drawable.da_turn_ramp_left;
                } else {
                    Log.e(TAG, "OFF_RAMP image for " + modifier.toString() + " not implemented!");
                }
                break;
            case USE_LANE:
                Log.e(TAG, "USE_LANE image not implemented!");
                break;
            case NOTIFICATION:
                Log.e(TAG, "NOTIFICATION image not implemented!");
                break;
            case EXIT_ROTARY:
            case EXIT_ROUNDABOUT:
                image = R.drawable.da_turn_roundabout_exit;
                break;
            default:
                Log.e(TAG, maneuver.getType().toString() + " image not implemented!");
                break;
        }
        if (image == R.drawable.da_turn_unknown) {
            Log.e(TAG, "Unknown image");
        }
        return image;
    }
}
