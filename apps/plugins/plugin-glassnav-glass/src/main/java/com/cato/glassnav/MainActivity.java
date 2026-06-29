package com.cato.glassnav;

import static java.lang.Math.abs;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;


import com.google.android.glass.media.Sounds;
import com.google.android.glass.touchpad.Gesture;
import com.google.android.glass.touchpad.GestureDetector;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.maplibre.navigation.core.location.engine.LocationEngine;
import org.maplibre.navigation.core.milestone.BannerInstructionMilestone;
import org.maplibre.navigation.core.milestone.Milestone;
import org.maplibre.navigation.core.milestone.MilestoneEventListener;
import org.maplibre.navigation.core.models.BannerInstructions;
import org.maplibre.navigation.core.models.DirectionsRoute;
import org.maplibre.navigation.core.models.StepManeuver;
import org.maplibre.navigation.core.navigation.MapLibreNavigation;
import org.maplibre.navigation.core.navigation.MapLibreNavigationOptions;
import org.maplibre.navigation.core.navigation.camera.SimpleCamera;
import org.maplibre.navigation.core.offroute.OffRouteDetector;
import org.maplibre.navigation.core.offroute.OffRouteListener;
import org.maplibre.navigation.core.routeprogress.ProgressChangeListener;
import org.maplibre.navigation.core.routeprogress.RouteLegProgress;
import org.maplibre.navigation.core.routeprogress.RouteProgress;
import org.maplibre.navigation.core.snap.SnapToRoute;
import org.maplibre.navigation.core.utils.RouteUtils;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.oscim.android.MapView;
import org.oscim.android.canvas.AndroidBitmap;
import org.oscim.android.theme.AssetsRenderTheme;
import org.oscim.backend.CanvasAdapter;
import org.oscim.backend.canvas.Bitmap;
import org.oscim.core.GeoPoint;
import org.oscim.layers.Layer;
import org.oscim.layers.LocationTextureLayer;
import org.oscim.layers.PathLayer;
import org.oscim.layers.marker.ItemizedLayer;
import org.oscim.layers.marker.MarkerInterface;
import org.oscim.layers.marker.MarkerItem;
import org.oscim.layers.marker.MarkerSymbol;
import org.oscim.layers.tile.buildings.BuildingLayer;
import org.oscim.layers.tile.vector.VectorTileLayer;
import org.oscim.layers.tile.vector.labeling.LabelLayer;
import org.oscim.renderer.LocationCallback;
import org.oscim.theme.IRenderTheme;
import org.oscim.theme.styles.LineStyle;
import org.oscim.tiling.source.OkHttpEngine;
import org.oscim.tiling.source.UrlTileSource;
import org.oscim.theme.internal.VtmThemes;
import org.oscim.tiling.source.mapfile.MapFileTileSource;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;
import okhttp3.Cache;
import okhttp3.OkHttpClient;

public class MainActivity extends Activity implements SensorEventListener, TextToSpeech.OnInitListener {
    private static final String TAG = "MainActivity";
    static MapView mapView;
    TextView directionDistance;
    TextView primaryInstructionText;
    TextView secondaryInstructionText;
    TextView etaText;
    TextView speedometerView; // resolved to top_right OR bottom_right based on setting
    ImageView directionImage;
    ImageView modeImage;
    static RouteProgress currentProgress;
    static Milestone currentMilestone;
    private GestureDetector mGestureDetector;
    private LocationTextureLayer locationTextureLayer;
    private static ItemizedLayer markerLayer;
    private IRenderTheme theme;
    private LocationManager locationManager;
    private PowerManager powerManager;
    private PowerManager.WakeLock wakeLock;
    private ImageView navArrow;
    private SensorManager sensorManager;
    private List<ScanResult> wifiAccessPoints;
    static OkHttpClient client = null;
    static DirectionsRoute route;
    private final float[] mRotationMatrix = new float[16];
    private final float[] mOrientation = new float[9];
    private float mHeading;
    private float mPitch;
    /** XE-only: heading at the last actually-issued map redraw. We
     *  skip redraws when the head hasn't moved at least 2° to keep
     *  the PowerVR SGX 540 cool. Updates from setMapPosition
     *  (location-driven) always redraw regardless. */
    private float mLastDrawnHeadingDeg = Float.NaN;
    /** Cached XE detection — Build.MODEL = "Glass 1". */
    private final boolean mIsXE =
        "Glass 1".equalsIgnoreCase(android.os.Build.MODEL);
    private GeomagneticField mGeomagneticField;
    static SnapToRoute snapToRoute = new SnapToRoute();
    private float routeHeading;
    static Location lastLocation = null;
    private double scale = 100000.0;

    private static TextToSpeech tts;
    private static boolean initialized = false;
    private static String queuedText;
    static Mode mode = Mode.NONE;
    static MapLibreNavigation navigation = null;

    private final Handler bluetoothHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(@NonNull android.os.Message msg) {
            if (msg.what == BluetoothMapsService.MessageConstants.MESSAGE_READ) {
                bluetoothConnected = true;
                if (navigation == null) {
                    // Connected state is implicit — let the map show
                    // through. No icon / banner clutter unless we're
                    // actively searching (handled in the else below).
                    directionImage.setImageDrawable(null);
                    primaryInstructionText.setText("");
                }
                try {
                    byte[] payload = (byte[]) msg.obj;
                    String readMessage = new String(payload, 0, msg.arg1);
                    //Log.i(TAG, "Got bluetooth message: " + readMessage);
                    String jsonString = readMessage.trim();
                    if (jsonString.startsWith("{")) {
                        try {
                            JSONObject jsonMessage = new JSONObject(jsonString);
                            Utils.selectedInfo = new Utils.LocationInfo(jsonMessage.getString("n"), jsonMessage.getString("dn"), new GeoPoint(jsonMessage.getDouble("la"), jsonMessage.getDouble("lo")), (float) jsonMessage.getDouble("di"));
                            // Auto-start a travel mode if the user picked one
                            // in plugin settings — skips the on-glass walk /
                            // bike / drive selection card entirely.
                            String defaultMode = getSharedPreferences(
                                GlassNavPluginService.PREFS_NAME, MODE_PRIVATE
                            ).getString("default_route_mode", "ask");
                            if ("walk".equals(defaultMode)) {
                                MainActivity.mode = Mode.WALK;
                                Utils.startMainActivity(MainActivity.this, 0);
                            } else if ("cycle".equals(defaultMode)) {
                                MainActivity.mode = Mode.CYCLE;
                                Utils.startMainActivity(MainActivity.this, 0);
                            } else if ("drive".equals(defaultMode)) {
                                MainActivity.mode = Mode.DRIVE;
                                Utils.startMainActivity(MainActivity.this, 0);
                            } else {
                                Intent routeIntent = new Intent(MainActivity.this, RouteActivity.class);
                                startActivity(routeIntent);
                            }
                        } catch (JSONException e) {
                            throw new RuntimeException(e);
                        }
                    } else {
                        Location btLocation = locationFromBytes(payload);
                        //Log.d(TAG, "Bluetooth location received: " + btLocation);
                        locationListener.onLocationChanged(btLocation);
                        // Stop GPS updates when Bluetooth is connected
                        if (locationManager != null) {
                            locationManager.removeUpdates(locationListener);
                        }
                        handler.removeCallbacks(gpsTimeoutRunnable);
                    }
                } catch (ClassCastException e) {
                    Log.e(TAG, "Unexpected MESSAGE_READ payload type", e);
                }
            } else if (msg.what == BluetoothMapsService.MessageConstants.MESSAGE_ERROR) {
                bluetoothConnected = false;
                if (navigation == null) {
                    directionImage.setImageResource(R.drawable.ic_bluetooth_searching);
                    primaryInstructionText.setText("Waiting for Bluetooth connection");
                }
                Log.e(TAG, "Bluetooth error: " + msg.obj);
                Log.i(TAG, "Restarting GPS updates");
                startLocationUpdates();
                Log.i(TAG, "Restarting bluetooth service");
                bluetoothService.start();
            }
        }
    };
    private static BluetoothMapsService bluetoothService;
    boolean bluetoothConnected = false;

    private static final int MAP_OFFSET_X = 0;    // pixels to offset horizontally
    private static final int MAP_OFFSET_Y = 80; // pixels to offset vertically (negative moves up)

    private static final int GPS_TIMEOUT_MS = 60000;

    @SuppressLint("MissingPermission")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Honor the phone-side "Keep screen on" toggle. Read from the
        // same SharedPreferences PluginConfigHandler writes to.
        boolean keepScreenOn = getSharedPreferences(
            GlassNavPluginService.PREFS_NAME, MODE_PRIVATE
        ).getBoolean("keep_screen_on", false);
        if (keepScreenOn) {
            getWindow().addFlags(
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        CustomTrust customTrust = new CustomTrust(getApplicationContext());
        client = customTrust.getClient();

        /*
         * Before you make any calls on the mapsforge library, you need to initialize the
         * AndroidGraphicFactory. Behind the scenes, this initialization process gathers a bit of
         * information on your device, such as the screen resolution, that allows mapsforge to
         * automatically adapt the rendering for the device.
         * If you forget this step, your app will crash. You can place this code, like in the
         * Samples app, in the Android Application class. This ensures it is created before any
         * specific activity. But it can also be created in the onCreate() method in your activity.
         */
        AndroidGraphicFactory.createInstance(getApplication());

        /*
         * A MapView is an Android View (or ViewGroup) that displays a mapsforge map. You can have
         * multiple MapViews in your app or even a single Activity. Have a look at the mapviewer.xml
         * on how to create a MapView using the Android XML Layout definitions.
         */
        setContentView(R.layout.mapviewer);

        directionDistance = findViewById(R.id.direction_distance);
        directionImage = findViewById(R.id.direction);
        modeImage = findViewById(R.id.mode);
        modeImage.setVisibility(View.INVISIBLE);
        primaryInstructionText = findViewById(R.id.primary_instruction);
        secondaryInstructionText = findViewById(R.id.secondary_instruction);
        etaText = findViewById(R.id.eta);
        etaText.setVisibility(View.INVISIBLE);

        // Speedometer overlay — pick the corner the user set in plugin
        // settings; updateSpeedometer() will toggle visibility per the
        // mode setting (off / nav_only / always) on each location fix.
        TextView speedTopRight = findViewById(R.id.speedometer_top_right);
        TextView speedBottomRight = findViewById(R.id.speedometer_bottom_right);
        String speedPos = getSharedPreferences(
            GlassNavPluginService.PREFS_NAME, MODE_PRIVATE
        ).getString("speedometer_position", "top_right");
        speedometerView = "bottom_right".equals(speedPos) ? speedBottomRight : speedTopRight;

        mapView = findViewById(R.id.mapView);

        mapView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);

        // Initialize sensors
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        // Initialize wake lock
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "GlassNav::LocationWakeLock");
        try {
            openMap();
        } catch (Exception e) {
            e.printStackTrace();
        }
        tts = new TextToSpeech(this, this);
        mGestureDetector = createGestureDetector(this);
        // GlassHole BT bridge: register a listener on our plugin
        // service so phone messages flow into the same handler the
        // upstream RFCOMM ConnectedThread used. Transport is owned by
        // the launcher's BluetoothListenerService, not by us — so we
        // don't open / accept / close any socket here.
        GlassNavPluginService.setListener(bytes -> {
            android.os.Message msg = bluetoothHandler.obtainMessage(
                BluetoothMapsService.MessageConstants.MESSAGE_READ,
                bytes.length, -1, bytes);
            msg.sendToTarget();
        });
        bluetoothService = new BluetoothMapsService(this, bluetoothHandler);
        directionImage.setImageResource(R.drawable.ic_bluetooth_searching);
        primaryInstructionText.setText("Waiting for Bluetooth connection");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        Log.i(TAG, "Received new intent: " + intent);
        Bundle bundle = intent.getExtras();
        if (bundle != null && bundle.get("action") != null) {
            switch ((int) bundle.get("action")) {
                case 0:
                    Log.i(TAG, "Intent requested to start navigation");
                    startRouteNavigation();
                    break;
                case 1:
                    Log.i(TAG, "Intent requested to stop navigation");
                    stopNavigation();
                    break;
            }
        }
    }

    private void openMap() {
        try {
            VectorTileLayer tileLayer;
            // Multi-map dir: any *.map file dropped in /sdcard/glasshole/maps
            // is picked up automatically. The picker prefers files whose
            // bbox contains the user's last known location (smaller bbox
            // wins — finer detail). Falls back to the legacy
            // /sdcard/Map.map slot if the new dir is empty.
            File mapFile = pickBestOfflineMap();

            if (mapFile == null) {
                Log.i(TAG, "No offline .map file found, falling back to online tile source");

                OkHttpClient.Builder builder = new OkHttpClient.Builder() //TODO: Add user agent header
                        .sslSocketFactory(CustomTrust.sslSocketFactory, CustomTrust.trustManager);

                // Cache the tiles into file system
                File cacheDirectory = new File(getExternalCacheDir(), "tiles");
                int cacheSize = 10 * 1024 * 1024; // 10 MB
                Cache cache = new Cache(cacheDirectory, cacheSize);
                builder.cache(cache);

                UrlTileSource tileSource = OsmMvtTileSource.builder()
                        .httpFactory(new OkHttpEngine.OkHttpFactory(builder))
                        //.locale("en")
                        .build();

                tileLayer = mapView.map().setBaseMap(tileSource);

                theme = mapView.map().setTheme(new AssetsRenderTheme(getAssets(), "", "vtm/openmaptilesdark.xml"));
            } else {
                Log.i(TAG, "Loading map from local file: " + mapFile.getPath());
                MapFileTileSource tileSource = new MapFileTileSource();
                tileSource.setMapFile(mapFile.getPath());
                tileLayer = mapView.map().setBaseMap(tileSource);
                theme = mapView.map().setTheme(VtmThemes.DARK);
            }

            // Building layer — 3D-extruded buildings. Skipped on
            // Glass XE because the TI OMAP4 + PowerVR SGX 540 +
            // building-extrusion GPU work causes the headset to
            // overheat in ~10 minutes of continuous nav. EE1 and EE2
            // run different SoCs (Snapdragon family) with more
            // thermal headroom and handle it fine.
            boolean isXE = "Glass 1".equalsIgnoreCase(android.os.Build.MODEL);
            if (!isXE) {
                mapView.map().layers().add(new BuildingLayer(mapView.map(), tileLayer));
            }

            // Label layer
            mapView.map().layers().add(new LabelLayer(mapView.map(), tileLayer));

            locationTextureLayer = new LocationTextureLayer(mapView.map(), 1f);
            locationTextureLayer.locationRenderer.setBitmapArrow(CanvasAdapter.decodeBitmap(getResources().openRawResource(R.raw.location_texture)));
            locationTextureLayer.locationRenderer.setBitmapMarker(CanvasAdapter.decodeBitmap(getResources().openRawResource(R.raw.marker_texture)));
            locationTextureLayer.locationRenderer.setCallback(new LocationCallback() {
                @Override
                public boolean hasRotation() {
                    return true;
                }

                @Override
                public float getRotation() {
                    return mHeading;
                }
            });
            mapView.map().layers().add(locationTextureLayer);

            Bitmap bitmapPoi = new AndroidBitmap(BitmapFactory.decodeResource(getResources(), R.drawable.nav_markers));
            MarkerSymbol symbol = new MarkerSymbol(bitmapPoi, MarkerSymbol.HotspotPlace.BOTTOM_CENTER);
            markerLayer = new ItemizedLayer(mapView.map(), new ArrayList<>(), symbol, new ItemizedLayer.OnItemGestureListener<>() {
                @Override
                public boolean onItemSingleTapUp(int i, MarkerInterface markerInterface) {
                    return false;
                }

                @Override
                public boolean onItemLongPress(int i, MarkerInterface markerInterface) {
                    return false;
                }
            });
            mapView.map().layers().add(markerLayer);

            mapView.map().viewport().setMinScale(10000);
            mapView.map().viewport().setMaxScale(10000000);
            mapView.map().viewport().setMaxTilt(45.0f);
            mapView.map().viewport().setMinTilt(45.0f);
            mapView.map().viewport().setMapViewCenterY(0.7f); //TODO: Set this properly for Google Glass screen

            // Request location updates
            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

            // Get network location on startup
            getNetworkLocationUpdates();
            startLocationUpdates();
        } catch (Exception e) {
            /*
             * In case of map file errors avoid crash, but developers should handle these cases!
             */
            e.printStackTrace();
        }
    }

    /** Pick the best offline .map file for the current location.
     *
     *  Scan order:
     *    1. /sdcard/glasshole/maps/*.map — preferred. If the user has
     *       a last-known location, score each file by whether its
     *       bounding box contains the position; smaller bbox wins
     *       (finer detail). If no GPS fix yet, just take the first
     *       file alphabetically.
     *    2. /sdcard/Map.map — legacy single-file slot (upstream
     *       GlassNav's original spot). Kept for backwards compat
     *       with users who already had their map file there.
     *    3. null — caller falls back to the online tile source.
     */
    private File pickBestOfflineMap() {
        File mapsDir = new File(
            Environment.getExternalStorageDirectory(), "glasshole/maps");
        File[] files = mapsDir.listFiles((dir, name) -> name.endsWith(".map"));
        if (files != null && files.length > 0) {
            Location loc = lastLocation;
            if (loc != null) {
                File best = null;
                double bestArea = Double.POSITIVE_INFINITY;
                for (File f : files) {
                    org.oscim.tiling.source.mapfile.MapFileTileSource src =
                        new org.oscim.tiling.source.mapfile.MapFileTileSource();
                    try {
                        src.setMapFile(f.getPath());
                        org.oscim.tiling.TileSource.OpenResult r = src.open();
                        if (!r.isSuccess()) continue;
                        org.oscim.tiling.source.mapfile.MapInfo info = src.getMapInfo();
                        if (info == null || info.boundingBox == null) continue;
                        org.oscim.core.BoundingBox bb = info.boundingBox;
                        if (bb.contains(loc.getLatitude(), loc.getLongitude())) {
                            double area = (bb.getMaxLatitude() - bb.getMinLatitude())
                                * (bb.getMaxLongitude() - bb.getMinLongitude());
                            if (area < bestArea) {
                                bestArea = area;
                                best = f;
                            }
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Skipping unreadable map " + f.getName() + ": " + t.getMessage());
                    } finally {
                        try { src.close(); } catch (Throwable ignored) {}
                    }
                }
                if (best != null) {
                    Log.i(TAG, "Picked " + best.getName() + " (bbox contains current location)");
                    return best;
                }
                Log.i(TAG, "Location " + loc.getLatitude() + "," + loc.getLongitude()
                    + " not covered by any map in " + mapsDir.getPath()
                    + " — falling back to first file");
            }
            // No location or no covering bbox — alphabetical pick.
            java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            Log.i(TAG, "Picked " + files[0].getName() + " (no GPS / no covering map)");
            return files[0];
        }
        // Legacy fallback for users with the upstream Map.map slot.
        File legacy = new File(
            Environment.getExternalStorageDirectory(), "Map.map");
        if (legacy.isFile()) {
            Log.i(TAG, "Using legacy /sdcard/Map.map");
            return legacy;
        }
        return null;
    }

    public void startRouteNavigation() {
        Log.i(TAG, "Starting route navigation");
        if (route == null) {
            Log.e(TAG, "No route available for navigation");
            route();
            return;
        }
        /*try {
            JSONObject leg = routeObj.getJSONObject("trip").getJSONArray("legs").getJSONObject(0);
            List<GeoPoint> shape = decodePolyline(leg.getString("shape"));
            PathLayer pathLayer = new PathLayer(mapView.map(), new LineStyle(Color.RED, 10));
            for ( GeoPoint p : shape) {
                pathLayer.addPoint(p);
            }
            Log.i(TAG, "Adding route with " + shape.size() + " points to map");
            //Remove existing path layer if present
            for (Layer layer : mapView.map().layers()) {
                if (layer instanceof PathLayer) {
                    mapView.map().layers().remove(layer);
                    break;
                }
            }
            mapView.map().layers().add(pathLayer);


        } catch (JSONException e) {
            throw new RuntimeException(e);
        }*/
        LocationEngine locationEngine = new LocationEngine() {
            @Override
            public @NotNull Flow<org.maplibre.navigation.core.location.@NotNull Location> listenToLocation(@NotNull Request request) {
                return KotlinUtils.locationFlow(request);
            }

            @Override
            public @Nullable Object getLastLocation(@NotNull Continuation<? super org.maplibre.navigation.core.location.@Nullable Location> $completion) {
                return Utils.androidLocationtoMapLibreLocation(lastLocation);
            }
        };

        navigation = new MapLibreNavigation(new MapLibreNavigationOptions(), locationEngine, new SimpleCamera(), snapToRoute, new OffRouteDetector(), new RouteUtils());
        navigation.startNavigation(route);
        modeImage.setVisibility(View.VISIBLE);
        switch (mode) {
            case WALK:
                modeImage.setImageResource(R.drawable.travel_mode_walk);
                break;
            case CYCLE:
                modeImage.setImageResource(R.drawable.travel_mode_bike);
                break;
            case DRIVE:
                modeImage.setImageResource(R.drawable.travel_mode_drive);
                break;
        }
        etaText.setVisibility(View.VISIBLE);
        navigation.addProgressChangeListener(new ProgressChangeListener() {
            @Override
            public void onProgressChange(org.maplibre.navigation.core.location.@NotNull Location location, @NotNull RouteProgress routeProgress) {
                //Log.i(TAG, "Progress update: " + routeProgress);
                currentProgress = routeProgress;
                if (currentMilestone instanceof BannerInstructionMilestone) {
                    BannerInstructions instructions = ((BannerInstructionMilestone) currentMilestone).getBannerInstructions();
                    if (instructions != null) {
                        primaryInstructionText.setText(instructions.getPrimary().getText());
                        if (instructions.getSecondary() != null) {
                            secondaryInstructionText.setText(instructions.getSecondary().getText());
                        } else {
                            secondaryInstructionText.setText("");
                        }
                        if (instructions.getSub() != null) {
                            //Log.i(TAG, "Subtext: " + instructions.getSub().getText());
                        }
                    }
                }
                directionDistance.setText(Utils.formatDistance(MainActivity.this, (float) routeProgress.getStepDistanceRemaining()));
                etaText.setText(Utils.formatEta(MainActivity.this, routeProgress.getDurationRemaining()));
                RouteLegProgress currentLegProgress = routeProgress.getCurrentLegProgress();
                if (currentLegProgress.getUpComingStep() != null) {
                    StepManeuver currentManeuver = currentLegProgress.getUpComingStep().getManeuver();
                    directionImage.setImageResource(Utils.getImageFromManuever(currentManeuver));
                }
                if (currentMilestone != null && navigation.getRouteUtils().isArrivalEvent(routeProgress, currentMilestone)) {
                    Log.i(TAG, "Arrival milestone reached");
                    stopNavigation();
                }
            }
        });

        PowerManager.WakeLock wklk = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "GlassNav::InstructionWakeLock");
        navigation.addMilestoneEventListener(new MilestoneEventListener() {
            @Override
            public void onMilestoneEvent(@NotNull RouteProgress routeProgress, @Nullable String instruction, @NotNull Milestone milestone) {
                //TODO: Turn on screen, play alert sound, then use tts with instruction
                //Log.i(TAG, "Milestone reached: " + milestone + ", instruction: " + instruction);
                wklk.acquire(15000);
                if (instruction != null) {
                    speak(instruction);
                }
                currentMilestone = milestone;
            }
        });
        navigation.addOffRouteListener(new OffRouteListener() {
            @Override
            public void userOffRoute(org.maplibre.navigation.core.location.@NotNull Location location) {
                if (Utils.isNetworkConnected(getApplicationContext())) {
                    navigation.stopNavigation();
                    speak("Rerouting.");
                    route();
                } /*else {
                    Log.w(TAG, "Device offline, will not reroute.");
                }*/
            }
        });
        List<GeoPoint> routePoints = decodePolyline(route.getGeometry());
        for (Layer layer: mapView.map().layers()) { // If rerouting, remove previous route from map
            if (layer instanceof PathLayer) {
                mapView.map().layers().remove(layer);
            }
        }
        PathLayer pathLayer = new PathLayer(mapView.map(), new LineStyle(Color.CYAN, 10));
        for ( GeoPoint p : routePoints) {
            pathLayer.addPoint(p);
        }
        mapView.map().layers().add(2, pathLayer);
        markerLayer.addItem(new MarkerItem(Utils.selectedInfo.name, "", Utils.selectedInfo.location));
    }

    enum Mode {
        NONE,
        WALK,
        CYCLE,
        DRIVE
    }

    void route() {
        if (navigation != null) navigation.onDestroy();
        String costing = "auto";
        switch (mode) {
            case DRIVE:
                costing = "auto";
                break;
            case WALK:
                costing = "pedestrian";
                break;
            case CYCLE:
                costing = "bicycle";
        }
        Utils.getRoute(Utils.selectedInfo.location.getLatitude(), Utils.selectedInfo.location.getLongitude(), Utils.selectedInfo.name, costing, this);
    }

    void stopNavigation() {
        currentMilestone = null;
        mode = Mode.NONE;
        route = null;
        modeImage.setVisibility(View.INVISIBLE);
        etaText.setVisibility(View.INVISIBLE);
        for (Layer layer: mapView.map().layers()) {
            if (layer instanceof PathLayer) {
                mapView.map().layers().remove(layer);
            }
        }
        markerLayer.removeAllItems();
        Utils.selectedInfo = null;
        if (navigation != null) {
            navigation.onDestroy();
            navigation = null;
        }
        if (bluetoothConnected) {
            // Clean map view when connected — no banner needed.
            directionImage.setImageDrawable(null);
            primaryInstructionText.setText("");
        } else {
            directionImage.setImageResource(R.drawable.ic_bluetooth_searching);
            primaryInstructionText.setText("Waiting for Bluetooth connection");
        }
    }

    private static List<GeoPoint> decodePolyline(String encoded) {
        List<GeoPoint> track = new ArrayList<>();
        int index = 0;
        int lat = 0, lng = 0;

        while (index < encoded.length()) {
            int b, shift = 0, result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            int dlat = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            lat += dlat;

            shift = 0;
            result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            int dlng = ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            lng += dlng;

            GeoPoint p = new GeoPoint((double) lat / 1E6, (double) lng / 1E6);
            track.add(p);
        }
        return track;
    }

    @Override
    protected void onDestroy() {
        /*
         * Whenever your activity exits, some cleanup operations have to be performed lest your app
         * runs out of memory.
         */
        if (wakeLock.isHeld()) wakeLock.release();
        mapView.onDestroy();
        if (theme != null) theme.dispose();
        if (navigation != null) navigation.onDestroy();
        if (bluetoothService != null) bluetoothService.stop();
        // Drop our listener so the plugin service stops dispatching to
        // this dead activity instance.
        GlassNavPluginService.setListener(null);
        super.onDestroy();
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastGpsTimestamp = 0L;
    private final Runnable gpsTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            if (now - lastGpsTimestamp >= GPS_TIMEOUT_MS && mode == Mode.NONE) {
                getNetworkLocationUpdates();
            }
        }
    };
    private void startLocationUpdates() {
        try {
            Log.i(TAG, "Starting location updates");
            if (bluetoothConnected) {
                Log.d(TAG, "Bluetooth connected, skipping GPS request");
                handler.removeCallbacks(gpsTimeoutRunnable); // Remove any pending GPS timeout callbacks
                locationManager.removeUpdates(locationListener);
                return;
            }

            if (locationManager == null) {
                locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
                if (locationManager == null) {
                    Log.e(TAG, "LocationManager is null");
                    return;
                }
            }

            // Acquire wake lock to prevent GPS from being shut down
            if (!wakeLock.isHeld()) {
                wakeLock.acquire();
            }

            Criteria criteria = new Criteria();
            criteria.setAccuracy(Criteria.ACCURACY_FINE);
            criteria.setAltitudeRequired(true);

            List<String> providers = locationManager.getProviders(
                    criteria, true /* enabledOnly */);

            for (String provider : providers) {
                locationManager.requestLocationUpdates(provider, 500,
                        0.5f, locationListener);
            }

            lastGpsTimestamp = System.currentTimeMillis();
            handler.removeCallbacks(gpsTimeoutRunnable);
            handler.postDelayed(gpsTimeoutRunnable, GPS_TIMEOUT_MS);

            // Get last known location
            Location lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (lastKnownLocation != null) {
                updateMapPosition(lastKnownLocation);
            }

        } catch (SecurityException e) {
            Log.e(TAG, "Location permission not granted", e);
        }
    }

    /*static void showRouteOverview() {
        if (!isShowingOverview) {
            List<GeoPoint> routePoints = decodePolyline(route.getGeometry());
            Check if navigation is in progress, and if so, don't draw the route, as it's already shown by the nav
            PathLayer pathLayer = new PathLayer(mapView.map(), new LineStyle(Color.CYAN, 10));
            for ( GeoPoint p : routePoints) {
                pathLayer.addPoint(p);
            }
            mapView.map().layers().add(pathLayer);
            BoundingBox bbox = new BoundingBox(routePoints);
            MapPosition mapPosition = new MapPosition();
            mapPosition.setByBoundingBox(bbox, mapView.getWidth(), mapView.getHeight());
            mapView.map().setMapPosition(mapPosition);
            isShowingOverview = true;
        }
    }*/

    private Location locationFromBytes(byte[] data) {
        try {
            ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            double lat = bb.getDouble();      // bytes 0..7
            double lon = bb.getDouble();      // bytes 8..15
            double alt = bb.getDouble();      // bytes 16..23
            float speed = bb.getFloat();      // bytes 24..27
            float bearing = bb.getFloat();    // bytes 28..31

            Location loc = new Location("Bluetooth");
            loc.setLatitude(lat);
            loc.setLongitude(lon);
            loc.setAltitude(alt);
            loc.setSpeed(speed);
            loc.setBearing(bearing);
            loc.setTime(System.currentTimeMillis());
            return loc;
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse Bluetooth location bytes", e);
            return null;
        }
    }

    private void getNetworkLocationUpdates() {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiManager.startScan();
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        List<ScanResult> wifis = wifiManager.getScanResults();
        if (!wifis.equals(wifiAccessPoints)) {
            // Update only if there are changes
            wifiAccessPoints = wifis;
            JSONObject jsonBody = new JSONObject();
            JSONArray wifiArray = new JSONArray();
            try {
                for (ScanResult result : wifis) {
                    if (!result.SSID.endsWith("_nomap")) {
                        JSONObject wifiObj = new JSONObject();
                        wifiObj.put("macAddress", result.BSSID);
                        wifiObj.put("signalStrength", result.level);
                        wifiObj.put("frequency", result.frequency);
                        wifiArray.put(wifiObj);
                    }
                }
                jsonBody.put("wifiAccessPoints", wifiArray);
                //Log.d(TAG, "WiFi scan results: " + jsonBody.toString());
            } catch (JSONException e) {
                Log.e(TAG, "JSON Error: " + e);
            }
            HttpsUtils.makePostRequest(client, "https://api.beacondb.net/v1/geolocate", jsonBody, "POST", new HttpsUtils.HttpCallback() {
                @Override
                public void onSuccess(String response) {
                    //Log.d(TAG, "Network location response: " + response);
                    Location networkLocation = new Location(LocationManager.NETWORK_PROVIDER);
                    try {
                        JSONObject jsonResponse = new JSONObject(response);
                        JSONObject locationObj = jsonResponse.getJSONObject("location");
                        double lat = locationObj.getDouble("lat");
                        double lng = locationObj.getDouble("lng");
                        double accuracy = jsonResponse.getDouble("accuracy");
                        networkLocation.setLatitude(lat);
                        networkLocation.setLongitude(lng);
                        networkLocation.setAccuracy((float) accuracy);
                    } catch (JSONException e) {
                        Log.e(TAG, "JSON Parsing Error: " + e);
                    }
                    if (!bluetoothConnected) {
                        locationListener.onLocationChanged(networkLocation);
                    }
                }

                @Override
                public void onError(String errorMessage) {
                    Log.e(TAG, "Network location error: " + errorMessage);
                }
            });
        } /*else {
            Log.d(TAG, "Scan results unchanged, skipping network location request");
        }*/

    }

    /**
     * Updates the cached instance of the geomagnetic field after a location change.
     */
    private void updateGeomagneticField(Location location) {
        mGeomagneticField = new GeomagneticField((float) location.getLatitude(),
                (float) location.getLongitude(), (float) location.getAltitude(),
                location.getTime());
    }
    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            //Log.d(TAG, "Location changed: " + location);
            double bearing = 0;
            if (lastLocation != null) {
                // Calculate bearing between last and current location
                double latitude1 = Math.toRadians(lastLocation.getLatitude());
                double longitude1 = Math.toRadians(-lastLocation.getLongitude());
                double latitude2 = Math.toRadians(location.getLatitude());
                double longitude2 = Math.toRadians(-location.getLongitude());

                double dLong = longitude2 - longitude1;

                double dPhi = Math.log(Math.tan(latitude2 / 2.0 + Math.PI / 4.0) / Math.tan(latitude1 / 2.0 + Math.PI / 4.0));
                if (abs(dLong) > Math.PI)
                    if (dLong > 0.0)
                        dLong = -(2.0 * Math.PI - dLong);
                    else
                        dLong = (2.0 * Math.PI + dLong);

                bearing = ((Math.toDegrees(Math.atan2(dLong, dPhi)) + 360.0) % 360.0);
                //Log.d(TAG, "Bearing: " + bearing);
            }

            lastGpsTimestamp = System.currentTimeMillis();
            handler.removeCallbacks(gpsTimeoutRunnable);
            handler.postDelayed(gpsTimeoutRunnable, GPS_TIMEOUT_MS);

            updateGeomagneticField(location);
            //location.setBearing((float) bearing);

            org.maplibre.navigation.core.location.Location mapLibreLocation = Utils.androidLocationtoMapLibreLocation(location);
            KotlinUtils.Companion.sendLocation(mapLibreLocation);

            if (currentProgress != null && currentMilestone != null) {
                routeHeading = snapToRoute.getSnappedLocation(mapLibreLocation, currentProgress).getBearing();
            }
            updateMapPosition(location);
            lastLocation = location;
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            Log.d(TAG, "Status changed: " + provider + ", status: " + status);
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) {
            Log.d(TAG, "Provider enabled: " + provider);
            if (provider.equals(LocationManager.GPS_PROVIDER)) {
                startLocationUpdates(); // Restart updates when GPS is enabled
            }
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
            Log.d(TAG, "Provider disabled: " + provider);
        }
    };


    static float mod(float a, float b) {
        return (a % b + b) % b;
    }
    private float computeTrueNorth(float heading) {
        if (mGeomagneticField != null) {
            return heading + mGeomagneticField.getDeclination();
        } else {
            return heading;
        }
    }
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            // Get the current heading from the sensor, then notify the listeners of the
            // change.
            SensorManager.getRotationMatrixFromVector(mRotationMatrix, event.values);
            SensorManager.remapCoordinateSystem(mRotationMatrix, SensorManager.AXIS_X,
                    SensorManager.AXIS_Z, mRotationMatrix);
            SensorManager.getOrientation(mRotationMatrix, mOrientation);

            // Store the pitch (used to display a message indicating that the user's head
            // angle is too steep to produce reliable results.
            mPitch = (float) Math.toDegrees(mOrientation[1]);

            // Convert the heading (which is relative to magnetic north) to one that is
            // relative to true north, using the user's current location to compute this.
            float magneticHeading = (float) Math.toDegrees(mOrientation[0]);
            mHeading = mod(computeTrueNorth(magneticHeading), 360.0f)
                    - 6;
            // Always rotate the map to face the user's head heading.
            mapView.map().viewport().setRotation(-mHeading);
            // XE thermal: skip redraw when head hasn't moved enough
            // for a visible rotation change. Location-driven redraws
            // (updateMapPosition) still fire on every GPS fix so the
            // user's position dot stays current. 2° dead-band gives
            // ~50% redraw reduction in typical wear + drops the
            // PowerVR GPU duty cycle enough to skirt the thermal cap.
            if (mIsXE) {
                if (!Float.isNaN(mLastDrawnHeadingDeg)) {
                    float delta = Math.abs(shortestAngleDelta(mHeading, mLastDrawnHeadingDeg));
                    if (delta < 2.0f) return;
                }
                mLastDrawnHeadingDeg = mHeading;
            }
            mapView.map().updateMap(false);
        }
    }

    /** Shortest signed angular delta in degrees, range -180..180. */
    private static float shortestAngleDelta(float a, float b) {
        float d = ((a - b) % 360f + 540f) % 360f - 180f;
        return d;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        /*if (sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) { TODO: Implement interference warning
            mHasInterference = (accuracy < SensorManager.SENSOR_STATUS_ACCURACY_HIGH);
            notifyAccuracyChanged();
        }*/
    }


    private void updateMapPosition(Location location) {
        //Log.i(TAG, "Updating map position to " + location);
        locationTextureLayer.setPosition(location.getLatitude(), location.getLongitude(), location.getAccuracy());
        updateSpeedometer(location);
        //mapView.map().animator().animateTo(10, new GeoPoint(location.getLatitude(), location.getLongitude()));
        if (lastLocation == null) {
            mapView.map().setMapPosition(location.getLatitude(), location.getLongitude(), 100000.0);
            mapView.map().updateMap(false);
            return;
        }
        mapView.map().setMapPosition(location.getLatitude(), location.getLongitude(), scale);
        // Same simplification as in onSensorChanged — always head-up,
        // never route-up. Head heading reads the user's intent better
        // on a Glass display than the snapped route bearing.
        mapView.map().viewport().setRotation(-mHeading);
        // Force a repaint. After the sensor-handler heading throttle
        // (≥2° delta), VTM was only repainting on touch events when
        // the user held still — `setMapPosition` queues the new
        // viewport but doesn't ALWAYS trigger a frame. This explicit
        // updateMap guarantees every GPS fix paints, regardless of
        // whether head rotation also moved.
        mapView.map().updateMap(false);
    }

    /** Update the speedometer overlay text + visibility from the
     *  user's plugin settings.
     *
     *  - speedometer_mode: off / nav_only / always
     *  - units: imperial → mph; metric → km/h
     *
     *  Uses Location.getSpeed() (m/s) which is populated by every
     *  recent Android version + the binary LOC envelope from phone. */
    private void updateSpeedometer(Location location) {
        if (speedometerView == null) return;
        android.content.SharedPreferences prefs = getSharedPreferences(
            GlassNavPluginService.PREFS_NAME, MODE_PRIVATE);
        String mode = prefs.getString("speedometer_mode", "off");
        boolean show = "always".equals(mode)
            || ("nav_only".equals(mode) && navigation != null);
        if (!show) {
            speedometerView.setVisibility(View.GONE);
            return;
        }
        float speedMps = location.hasSpeed() ? location.getSpeed() : 0f;
        boolean imperial = "imperial".equals(prefs.getString("units", "imperial"));
        int displaySpeed;
        String unit;
        if (imperial) {
            displaySpeed = Math.round(speedMps * 2.236936f); // mph
            unit = "mph";
        } else {
            displaySpeed = Math.round(speedMps * 3.6f); // km/h
            unit = "km/h";
        }
        speedometerView.setText(displaySpeed + " " + unit);
        speedometerView.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onResume() {
        Log.i(TAG, "Resuming activity, starting location updates");
        super.onResume();
        mapView.onResume();
        // Ask the phone to start streaming GPS over BT. Without this,
        // opening GlassNav directly (vs. via the Maps share) leaves
        // SpeedTracker idle and no LOC binary packets arrive — the
        // map sits at no-location-yet forever. Idempotent: phone
        // ignores REQ_GPS_START if the stream is already running.
        GlassNavPluginService.requestGpsStreamStart();
        if (locationManager == null) {
            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        }

        // Register sensor listeners. EE1/EE2 use SENSOR_DELAY_UI
        // (~17 Hz) so head rotation tracks tight. XE has thermal
        // headroom problems with the PowerVR SGX 540 at 17 Hz +
        // continuous VTM redraws — drop to SENSOR_DELAY_NORMAL
        // (~5 Hz) plus the 2° heading dead-band above to stay
        // under the thermal cap.
        int sensorRate = mIsXE
                ? SensorManager.SENSOR_DELAY_NORMAL
                : SensorManager.SENSOR_DELAY_UI;
        sensorManager.registerListener(this,
                sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR),
                sensorRate);

        // The rotation vector sensor doesn't give us accuracy updates, so we observe the
        // magnetic field sensor solely for those.
        sensorManager.registerListener(this,
                sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD),
                sensorRate);

        // Stop any existing updates before starting new ones
        locationManager.removeUpdates(locationListener);
        startLocationUpdates();
    }

    @Override
    protected void onPause() {
        Log.i(TAG, "Pausing activity");
        super.onPause();
        mapView.onPause();
        //if (locationManager != null) {
        //    locationManager.removeUpdates(locationListener);
        //}
        // Unregister sensor listeners
        if (sensorManager != null && this instanceof SensorEventListener) {
            sensorManager.unregisterListener((SensorEventListener) this);
        }
        handler.removeCallbacks(gpsTimeoutRunnable);
    }

    public static void speak(String text) {
        // If not yet initialized, queue up the text.
        if (!initialized) {
            queuedText = text;
            return;
        }
        queuedText = null;
        // Before speaking the current text, stop any ongoing speech.
        tts.stop();
        // Speak the text.
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
    }
    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            initialized = true;
            tts.setLanguage(Locale.ENGLISH);

            if (queuedText != null) {
                speak(queuedText);
            }
        }
    }

    private GestureDetector createGestureDetector(Context context) {
        GestureDetector gestureDetector = new GestureDetector(context);
        //Create a base listener for generic gestures
        gestureDetector.setBaseListener( new GestureDetector.BaseListener() {
            @Override
            public boolean onGesture(Gesture gesture) {
                if (gesture == Gesture.TAP && lastLocation != null) {
                    AudioManager am = (AudioManager) getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
                    am.playSoundEffect(Sounds.TAP);
                    Intent tapIntent = new Intent(MainActivity.this, SearchActivity.class);
                    startActivity(tapIntent);
                    return true;
                }
                if (gesture == Gesture.SWIPE_DOWN) {
                    AudioManager am = (AudioManager) getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
                    am.playSoundEffect(Sounds.DISMISSED);
                    finish();
                    return true;
                }
                return false;
            }
        });
        gestureDetector.setScrollListener(new GestureDetector.ScrollListener() {
            @Override
            public boolean onScroll(float displacement, float delta, float velocity) {
                if (lastLocation == null) return false;
                double sensitivity = 0.005; // tweak this to taste
                double factor = 1.0 + (delta * sensitivity);
                factor = Math.max(0.1, factor); // avoid zero/negative scale
                scale *= factor;
                mapView.map().setMapPosition(
                        lastLocation.getLatitude(),
                        lastLocation.getLongitude(),
                        scale
                );
                // Head-up rotation in all modes (matches the
                // simplification in onSensorChanged + updateMapPosition).
                mapView.map().viewport().setRotation(-mHeading);

                //Log.d(TAG, "Scroll detected: displacement=" + displacement + ", delta=" + delta +
                //        ", velocity=" + velocity + ", newZoom=" + mapView.map().getMapPosition().getScale());
                return true;
            }
        });
        return gestureDetector;
    }
    /* Send generic motion events to the gesture detector */
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (mGestureDetector != null) {
            return mGestureDetector.onMotionEvent(event);
        }
        return false;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        return onGenericMotionEvent(event);
    }
}