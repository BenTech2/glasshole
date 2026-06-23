package com.cato.glassnav;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;

import com.google.android.glass.media.Sounds;
import com.google.android.glass.widget.CardBuilder;
import com.google.android.glass.widget.CardScrollAdapter;
import com.google.android.glass.widget.CardScrollView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.oscim.core.GeoPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class SearchActivity extends Activity {
    private static final int SPEECH_REQUEST = 0;
    private List<CardBuilder> mCards;
    private List<Utils.LocationInfo> searchResults = new ArrayList<>();
    private CardScrollView mCardScrollView;
    private ExampleCardScrollAdapter mAdapter;
    private final static String TAG = "SearchActivity";
    private boolean searched = false;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSharedPreferences(GlassNavPluginService.PREFS_NAME, MODE_PRIVATE)
                .getBoolean("keep_screen_on", false)) {
            getWindow().addFlags(
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        mCards = new ArrayList<CardBuilder>();
        createCards();
        mCardScrollView = new CardScrollView(this);
        mAdapter = new ExampleCardScrollAdapter();
        mCardScrollView.setAdapter(mAdapter);
        mCardScrollView.activate();
        setupClickListener();
        setContentView(mCardScrollView);
    }

    private void createCards() {
        if (MainActivity.navigation != null) {
            mCards.add(new CardBuilder(this, CardBuilder.Layout.MENU)
                    .setIcon(R.drawable.ic_close)
                    .setText("Stop navigation"));
        } else {
            mCards.add(new CardBuilder(this, CardBuilder.Layout.MENU)
                    .setIcon(R.drawable.ic_bookmark)
                    .setText("Saved"));
            mCards.add(new CardBuilder(this, CardBuilder.Layout.MENU)
                    .setText("Search")
                    .setIcon(R.drawable.ic_search)
                    .setFootnote("Uses the Nominatim API"));
        }
    }

    private class ExampleCardScrollAdapter extends CardScrollAdapter {

        @Override
        public int getPosition(Object item) {
            return mCards.indexOf(item);
        }

        @Override
        public int getCount() {
            return mCards.size();
        }

        @Override
        public Object getItem(int position) {
            return mCards.get(position);
        }

        @Override
        public int getViewTypeCount() {
            return CardBuilder.getViewTypeCount();
        }

        @Override
        public int getItemViewType(int position){
            return mCards.get(position).getItemViewType();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return mCards.get(position).getView(convertView, parent);
        }
    }


    private void setupClickListener() {
        mCardScrollView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                am.playSoundEffect(Sounds.TAP);
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
        SharedPreferences sharedPreferences = getSharedPreferences("places", Context.MODE_PRIVATE);
        Log.d(TAG, "Saved places: " + sharedPreferences.getString("places", "[]"));
        return new JSONArray(sharedPreferences.getString("places", "[]"));
    }

    private void displaySpeechRecognizer() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        startActivityForResult(intent, SPEECH_REQUEST);
    }

    private void displayResults(JSONArray results) {
        mCards.remove(1); mCards.remove(0);
        GeoPoint lastLocation = new GeoPoint(MainActivity.lastLocation.getLatitude(), MainActivity.lastLocation.getLongitude());
        for (int i = 0; i < results.length(); i++) {
            try {
                JSONObject result = results.getJSONObject(i);
                String name = result.getString("name");
                String displayName = result.getString("display_name");
                GeoPoint location = new GeoPoint(result.getDouble("lat"), result.getDouble("lon"));
                float distance = distFrom(location, lastLocation);
                Utils.LocationInfo searchResult = new Utils.LocationInfo(name, displayName, location, distance);
                searchResults.add(searchResult);
            } catch (JSONException e) {
                Log.e(TAG, "An error occurred: " + e);
            }
        }
        Collections.sort(searchResults, new Comparator<Utils.LocationInfo>() {

            public int compare(Utils.LocationInfo o1, Utils.LocationInfo o2) {
                // compare two instance of `Score` and return `int` as result.
                return Float.compare(o1.distance, o2.distance);
            }
        });
        for (Utils.LocationInfo result : searchResults) {
            mCards.add(new CardBuilder(this, CardBuilder.Layout.MENU)
                    .setText(result.name.isBlank() ? result.displayName : result.name)
                    .setFootnote("Distance: " + Utils.formatDistance(result.distance)));
        }
        Log.i(TAG, "Results: "+searchResults);
        mCardScrollView.setSelection(0);
        mAdapter.notifyDataSetChanged();
        searched=true;
    }

    private static float distFrom(GeoPoint point1, GeoPoint point2) {
        double earthRadius = 6371000; //meters
        double dLat = Math.toRadians(point2.getLatitude()-point1.getLatitude());
        double dLng = Math.toRadians(point2.getLongitude()-point1.getLongitude());
        double a = Math.sin(dLat/2) * Math.sin(dLat/2) +
                Math.cos(Math.toRadians(point1.getLatitude())) * Math.cos(Math.toRadians(point2.getLatitude())) *
                        Math.sin(dLng/2) * Math.sin(dLng/2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));

        return (float) (earthRadius * c);
    }

    void performSearch(String query) {
        GeoPoint lastLocation = new GeoPoint(MainActivity.lastLocation.getLatitude(), MainActivity.lastLocation.getLongitude());
        // Routing API allows a route of up to 200km, restrict search to box approximately around max radius
        String viewBox = (lastLocation.getLongitude() - 1.8)+","+ (lastLocation.getLatitude() - 1.8) +","+ (lastLocation.getLongitude() + 1.8) +","+ (lastLocation.getLatitude() + 1.8);
        HttpsUtils.makePostRequest(MainActivity.client, "https://nominatim.openstreetmap.org/search?format=json&bounded=1&q="+query+"&viewbox=" + viewBox, null, "GET", new HttpsUtils.HttpCallback() {
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
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SPEECH_REQUEST) {
            if (resultCode == RESULT_OK) {
                List<String> results = data.getStringArrayListExtra(
                        RecognizerIntent.EXTRA_RESULTS);
                performSearch(results.get(0));

            } else finish();
        }
        super.onActivityResult(requestCode, resultCode, data);
    }
}
