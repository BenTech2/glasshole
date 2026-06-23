package com.cato.glassnav;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Bundle;
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

import java.util.ArrayList;
import java.util.List;

public class RouteActivity extends Activity {
    private List<CardBuilder> mCards;
    private CardScrollView mCardScrollView;
    private ExampleCardScrollAdapter mAdapter;
    private Utils.LocationInfo locationInfo;
    private boolean saved = false;
    private final static String TAG = "RouteActivity";
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSharedPreferences(GlassNavPluginService.PREFS_NAME, MODE_PRIVATE)
                .getBoolean("keep_screen_on", false)) {
            getWindow().addFlags(
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        try {
            JSONArray placesArray = new JSONArray(getSharedPreferences("places", Context.MODE_PRIVATE).getString("places", "[]"));
            for (int i = 0; i < placesArray.length(); i++) {
                if (placesArray.getJSONObject(i).getString("display_name").equals(Utils.selectedInfo.displayName)) {
                    saved = true;
                }
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        mCards = new ArrayList<>();
        createCards();
        mCardScrollView = new CardScrollView(this);
        mAdapter = new ExampleCardScrollAdapter();
        mCardScrollView.setAdapter(mAdapter);
        mCardScrollView.activate();
        setupClickListener();
        setContentView(mCardScrollView);
    }

    private void createCards(){
        mCards.add(new CardBuilder(this, CardBuilder.Layout.TEXT)
                .setText(Utils.selectedInfo.displayName));
        /*mCards.add(new CardBuilder(this, CardBuilder.Layout.MENU)
                .setText("Show route overview"));*/
        mCards.add(new CardBuilder(this, CardBuilder.Layout.MENU)
                .setText("Start walking")
                .setIcon(R.drawable.ic_menu_walk));
        mCards.add(new CardBuilder(this, CardBuilder.Layout.MENU)
                .setText("Start cycling")
                .setIcon(R.drawable.ic_menu_bike));
        mCards.add(new CardBuilder(this, CardBuilder.Layout.MENU)
                .setText("Start driving")
                .setIcon(R.drawable.ic_menu_drive));
        if (saved) {
            mCards.add(new CardBuilder(this, CardBuilder.Layout.MENU)
                    .setText("Unsave")
                    .setIcon(R.drawable.ic_bookmark_remove));
        } else {
            mCards.add(new CardBuilder(this, CardBuilder.Layout.MENU)
                    .setText("Save")
                    .setIcon(R.drawable.ic_bookmark_add));
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
            return 0;
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
                /*if (position == 1) { //Show route overview
                    MainActivity.showRouteOverview();
                    Intent intent = new Intent(RouteActivity.this, MainActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);
                    finish();
                }*/
                if (position == 1) { // Start walking
                    MainActivity.mode = MainActivity.Mode.WALK;
                    Utils.startMainActivity(RouteActivity.this, 0);
                } else if (position == 2) { // Start cycling
                    MainActivity.mode = MainActivity.Mode.CYCLE;
                    Utils.startMainActivity(RouteActivity.this, 0);
                } else if (position == 3) { // Start driving
                    MainActivity.mode = MainActivity.Mode.DRIVE;
                    Utils.startMainActivity(RouteActivity.this, 0);
                } else if (position == 4) {
                    if (saved) { // Unsave
                        SharedPreferences sharedPreferences = getSharedPreferences("places", Context.MODE_PRIVATE);
                        SharedPreferences.Editor editor = sharedPreferences.edit();
                        try {
                            JSONArray placesArray = new JSONArray(sharedPreferences.getString("places", "[]"));
                            for (int i = 0; i < placesArray.length(); i++) {
                                if (placesArray.getJSONObject(i).getString("display_name").equals(Utils.selectedInfo.displayName)) {
                                    placesArray.remove(i);
                                }
                            }
                            editor.putString("places", placesArray.toString());
                            editor.apply();
                            Log.i(TAG, "Unsaved a place");
                            saved = false;
                            mCards.get(4).setText("Save");
                            mCards.get(4).setIcon(R.drawable.ic_bookmark_add);
                            mAdapter.notifyDataSetChanged();
                        } catch (JSONException e) {
                            throw new RuntimeException(e);
                        }
                    } else { // Save
                        SharedPreferences sharedPreferences = getSharedPreferences("places", Context.MODE_PRIVATE);
                        SharedPreferences.Editor editor = sharedPreferences.edit();
                        try {
                            JSONArray placesArray = new JSONArray(sharedPreferences.getString("places", "[]"));
                            JSONObject place = new JSONObject();
                            place.put("name", Utils.selectedInfo.name);
                            place.put("display_name", Utils.selectedInfo.displayName);
                            place.put("lat", Utils.selectedInfo.location.getLatitude());
                            place.put("lon", Utils.selectedInfo.location.getLongitude());
                            placesArray.put(place);
                            editor.putString("places", placesArray.toString());
                            editor.apply();
                            Log.i(TAG, "Saved a place");
                            saved = true;
                            mCards.get(4).setText("Unsave");
                            mCards.get(4).setIcon(R.drawable.ic_bookmark_remove);
                            mAdapter.notifyDataSetChanged();
                            am.playSoundEffect(Sounds.SUCCESS);
                        } catch (JSONException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
        });
    }

}
