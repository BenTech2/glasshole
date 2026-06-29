package com.cato.glassnav;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Bundle;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Pick walk / cycle / drive for the shared destination. Originally
 * used the Glass GDK CardBuilder / CardScrollView, which crashes on
 * EE2 (no GDK on the classpath). Reworked to plain Android views
 * so the same code runs on XE / EE1 / EE2.
 */
public class RouteActivity extends Activity {
    private final static String TAG = "RouteActivity";

    /** Each row in the picker — icon drawable + text. */
    private static class Row {
        final int iconRes;
        String text;
        Row(int iconRes, String text) { this.iconRes = iconRes; this.text = text; }
    }

    private final List<Row> rows = new ArrayList<>();
    private RowAdapter adapter;
    private ListView listView;
    private boolean saved = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSharedPreferences(GlassNavPluginService.PREFS_NAME, MODE_PRIVATE)
                .getBoolean("keep_screen_on", false)) {
            getWindow().addFlags(
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        try {
            JSONArray placesArray = new JSONArray(
                getSharedPreferences("places", Context.MODE_PRIVATE)
                    .getString("places", "[]"));
            for (int i = 0; i < placesArray.length(); i++) {
                if (placesArray.getJSONObject(i).getString("display_name")
                        .equals(Utils.selectedInfo.displayName)) {
                    saved = true;
                }
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        buildRows();

        listView = new ListView(this);
        listView.setBackgroundColor(Color.BLACK);
        listView.setDivider(null);
        listView.setSelector(android.R.color.transparent);
        listView.setVerticalFadingEdgeEnabled(true);
        adapter = new RowAdapter();
        listView.setAdapter(adapter);
        setupClickListener();
        setContentView(listView);
    }

    private void buildRows() {
        rows.clear();
        // First row is the destination name (display-only header).
        rows.add(new Row(0, Utils.selectedInfo.displayName));
        rows.add(new Row(R.drawable.ic_menu_walk, "Start walking"));
        rows.add(new Row(R.drawable.ic_menu_bike, "Start cycling"));
        rows.add(new Row(R.drawable.ic_menu_drive, "Start driving"));
        rows.add(new Row(
            saved ? R.drawable.ic_bookmark_remove : R.drawable.ic_bookmark_add,
            saved ? "Unsave" : "Save"));
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
            if (row == null) {
                row = new LinearLayout(RouteActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int pad = dp(20);
                row.setPadding(pad, dp(14), pad, dp(14));

                icon = new ImageView(RouteActivity.this);
                LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(36), dp(36));
                iconLp.rightMargin = dp(14);
                icon.setLayoutParams(iconLp);

                text = new TextView(RouteActivity.this);
                text.setTextColor(Color.WHITE);
                text.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                text.setLayoutParams(textLp);
                row.addView(icon);
                row.addView(text);
                row.setTag(new View[] { icon, text });
            } else {
                View[] tag = (View[]) row.getTag();
                icon = (ImageView) tag[0];
                text = (TextView) tag[1];
            }
            // Position 0 is the destination header — large text, no icon.
            if (position == 0) {
                icon.setVisibility(View.GONE);
                text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
                text.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            } else if (r.iconRes != 0) {
                icon.setVisibility(View.VISIBLE);
                icon.setImageResource(r.iconRes);
                text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f);
                text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            } else {
                icon.setVisibility(View.GONE);
                text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
            }
            text.setText(r.text);
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
                if (position == 1) {
                    MainActivity.mode = MainActivity.Mode.WALK;
                    Utils.startMainActivity(RouteActivity.this, 0);
                } else if (position == 2) {
                    MainActivity.mode = MainActivity.Mode.CYCLE;
                    Utils.startMainActivity(RouteActivity.this, 0);
                } else if (position == 3) {
                    MainActivity.mode = MainActivity.Mode.DRIVE;
                    Utils.startMainActivity(RouteActivity.this, 0);
                } else if (position == 4) {
                    toggleSave(am);
                }
            }
        });
    }

    // EE2 swipe-down to exit — same pattern as SearchActivity.
    private float mDownX, mDownY;
    private boolean mMoved;

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getPointerCount() == 1) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDownX = ev.getX();
                    mDownY = ev.getY();
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

    private void toggleSave(AudioManager am) {
        SharedPreferences sp = getSharedPreferences("places", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        try {
            JSONArray places = new JSONArray(sp.getString("places", "[]"));
            if (saved) {
                for (int i = 0; i < places.length(); i++) {
                    if (places.getJSONObject(i).getString("display_name")
                            .equals(Utils.selectedInfo.displayName)) {
                        places.remove(i);
                        break;
                    }
                }
                editor.putString("places", places.toString()).apply();
                Log.i(TAG, "Unsaved a place");
                saved = false;
            } else {
                JSONObject place = new JSONObject();
                place.put("name", Utils.selectedInfo.name);
                place.put("display_name", Utils.selectedInfo.displayName);
                place.put("lat", Utils.selectedInfo.location.getLatitude());
                place.put("lon", Utils.selectedInfo.location.getLongitude());
                places.put(place);
                editor.putString("places", places.toString()).apply();
                Log.i(TAG, "Saved a place");
                saved = true;
                if (am != null) am.playSoundEffect(AudioManager.FX_KEY_CLICK);
            }
            buildRows();
            adapter.notifyDataSetChanged();
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }
}
