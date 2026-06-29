package com.cato.glassnav;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.oscim.core.GeoPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Search picker. Originally GDK CardBuilder + CardScrollView, which
 * crashes on EE2 (no GDK). Plain Android views are equivalent and
 * run on all editions.
 */
public class SearchActivity extends Activity {
    private static final int SPEECH_REQUEST = 0;
    private final List<Utils.LocationInfo> searchResults = new ArrayList<>();
    private final static String TAG = "SearchActivity";
    private boolean searched = false;

    private static class Row {
        final int iconRes;
        final String text;
        final String footnote;
        Row(int iconRes, String text, String footnote) {
            this.iconRes = iconRes; this.text = text; this.footnote = footnote;
        }
    }
    private final List<Row> rows = new ArrayList<>();
    private RowAdapter adapter;
    private ListView listView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSharedPreferences(GlassNavPluginService.PREFS_NAME, MODE_PRIVATE)
                .getBoolean("keep_screen_on", false)) {
            getWindow().addFlags(
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        buildInitialRows();
        listView = new ListView(this);
        listView.setBackgroundColor(Color.BLACK);
        listView.setDivider(null);
        listView.setSelector(android.R.color.transparent);
        adapter = new RowAdapter();
        listView.setAdapter(adapter);
        setupClickListener();
        setContentView(listView);
    }

    private void buildInitialRows() {
        rows.clear();
        if (MainActivity.navigation != null) {
            rows.add(new Row(R.drawable.ic_close, "Stop navigation", null));
        } else {
            rows.add(new Row(R.drawable.ic_bookmark, "Saved", null));
            rows.add(new Row(R.drawable.ic_search, "Search", "Uses the Nominatim API"));
        }
    }

    private class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row r = rows.get(position);
            LinearLayout row = (LinearLayout) convertView;
            ImageView icon;
            TextView text;
            TextView footnote;
            if (row == null) {
                row = new LinearLayout(SearchActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int pad = dp(20);
                row.setPadding(pad, dp(14), pad, dp(14));

                icon = new ImageView(SearchActivity.this);
                LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(36), dp(36));
                iconLp.rightMargin = dp(14);
                icon.setLayoutParams(iconLp);

                LinearLayout textCol = new LinearLayout(SearchActivity.this);
                textCol.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                textCol.setLayoutParams(colLp);

                text = new TextView(SearchActivity.this);
                text.setTextColor(Color.WHITE);
                text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);

                footnote = new TextView(SearchActivity.this);
                footnote.setTextColor(0xFFAAAAAA);
                footnote.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                footnote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);

                textCol.addView(text);
                textCol.addView(footnote);
                row.addView(icon);
                row.addView(textCol);
                row.setTag(new View[] { icon, text, footnote });
            } else {
                View[] tag = (View[]) row.getTag();
                icon = (ImageView) tag[0];
                text = (TextView) tag[1];
                footnote = (TextView) tag[2];
            }
            if (r.iconRes != 0) {
                icon.setVisibility(View.VISIBLE);
                icon.setImageResource(r.iconRes);
            } else {
                icon.setVisibility(View.GONE);
            }
            text.setText(r.text);
            if (r.footnote != null && !r.footnote.isEmpty()) {
                footnote.setVisibility(View.VISIBLE);
                footnote.setText(r.footnote);
            } else {
                footnote.setVisibility(View.GONE);
            }
            return row;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void setupClickListener() {
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (am != null) am.playSoundEffect(AudioManager.FX_KEY_CLICK);
                if (MainActivity.navigation == null) {
                    if (position == 0 && !searched) {
                        try {
                            displayResults(getSavedPlaces());
                        } catch (JSONException e) {
                            throw new RuntimeException(e);
                        }
                    } else if (position == 1 && !searched) {
                        displaySpeechRecognizer();
                    } else {
                        Utils.selectedInfo = searchResults.get(position);
                        Intent routeIntent = new Intent(SearchActivity.this, RouteActivity.class);
                        startActivity(routeIntent);
                    }
                } else {
                    Utils.startMainActivity(SearchActivity.this, 1);
                    finish();
                }
            }
        });
    }

    private JSONArray getSavedPlaces() throws JSONException {
        SharedPreferences sp = getSharedPreferences("places", Context.MODE_PRIVATE);
        Log.d(TAG, "Saved places: " + sp.getString("places", "[]"));
        return new JSONArray(sp.getString("places", "[]"));
    }

    private void displaySpeechRecognizer() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        startActivityForResult(intent, SPEECH_REQUEST);
    }

    private void displayResults(JSONArray results) {
        // Drop the initial menu rows; replace with one row per result.
        rows.clear();
        searchResults.clear();
        GeoPoint lastLocation = new GeoPoint(
            MainActivity.lastLocation.getLatitude(),
            MainActivity.lastLocation.getLongitude());
        for (int i = 0; i < results.length(); i++) {
            try {
                JSONObject result = results.getJSONObject(i);
                String name = result.getString("name");
                String displayName = result.getString("display_name");
                GeoPoint location = new GeoPoint(
                    result.getDouble("lat"), result.getDouble("lon"));
                float distance = distFrom(location, lastLocation);
                searchResults.add(new Utils.LocationInfo(name, displayName, location, distance));
            } catch (JSONException e) {
                Log.e(TAG, "An error occurred: " + e);
            }
        }
        Collections.sort(searchResults, new Comparator<Utils.LocationInfo>() {
            @Override
            public int compare(Utils.LocationInfo o1, Utils.LocationInfo o2) {
                return Float.compare(o1.distance, o2.distance);
            }
        });
        for (Utils.LocationInfo result : searchResults) {
            String label = (result.name == null || result.name.isEmpty())
                ? result.displayName : result.name;
            rows.add(new Row(0, label, "Distance: " + Utils.formatDistance(result.distance)));
        }
        Log.i(TAG, "Results: " + searchResults);
        listView.setSelection(0);
        adapter.notifyDataSetChanged();
        searched = true;
    }

    private static float distFrom(GeoPoint point1, GeoPoint point2) {
        double earthRadius = 6371000;
        double dLat = Math.toRadians(point2.getLatitude() - point1.getLatitude());
        double dLng = Math.toRadians(point2.getLongitude() - point1.getLongitude());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(point1.getLatitude()))
                * Math.cos(Math.toRadians(point2.getLatitude()))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return (float) (earthRadius * c);
    }

    void performSearch(String query) {
        GeoPoint lastLocation = new GeoPoint(
            MainActivity.lastLocation.getLatitude(),
            MainActivity.lastLocation.getLongitude());
        String viewBox = (lastLocation.getLongitude() - 1.8) + ","
            + (lastLocation.getLatitude() - 1.8) + ","
            + (lastLocation.getLongitude() + 1.8) + ","
            + (lastLocation.getLatitude() + 1.8);
        HttpsUtils.makePostRequest(MainActivity.client,
            "https://nominatim.openstreetmap.org/search?format=json&bounded=1&q="
                + query + "&viewbox=" + viewBox,
            null, "GET", new HttpsUtils.HttpCallback() {
                @Override
                public void onSuccess(String response) {
                    try {
                        JSONArray results = new JSONArray(response);
                        if (results.length() > 0) {
                            displayResults(results);
                        } else {
                            Log.i(TAG, "No results found for query: " + query);
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "An error occurred: " + e);
                    }
                }
                @Override
                public void onError(String errorMessage) {
                    Log.e(TAG, "Nominatim error:" + errorMessage);
                }
            });
    }

    // EE2 swipe-down to exit. Peek the event before the ListView
    // sees it so a clear vertical drag fires finish() while taps +
    // scroll still feed the list normally. Same shape as the
    // MainActivity handler, just simpler (no pan/pinch to preserve).
    private float mDownX, mDownY;
    private long mDownTime;
    private boolean mMoved;

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getPointerCount() == 1) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDownX = ev.getX();
                    mDownY = ev.getY();
                    mDownTime = ev.getEventTime();
                    mMoved = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(ev.getX() - mDownX) > 20f
                            || Math.abs(ev.getY() - mDownY) > 20f) {
                        mMoved = true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    float dy = ev.getY() - mDownY;
                    float dx = ev.getX() - mDownX;
                    if (dy > 80f && Math.abs(dy) > Math.abs(dx) * 1.3f) {
                        finish();
                        return true;
                    }
                    break;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SPEECH_REQUEST) {
            if (resultCode == RESULT_OK) {
                List<String> results = data.getStringArrayListExtra(
                    RecognizerIntent.EXTRA_RESULTS);
                performSearch(results.get(0));
            } else {
                finish();
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }
}
