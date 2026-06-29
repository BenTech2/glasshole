package com.cato.glassnav;

import android.os.AsyncTask;
import android.util.Log;

import org.json.JSONObject;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class HttpsUtils {
    private static final String TAG = "HttpsUtils";

    // Interface to handle the response
    public interface HttpCallback {
        void onSuccess(String response);
        void onError(String errorMessage);
    }

    public static void makePostRequest(OkHttpClient client, String url, JSONObject jsonBody, String method, HttpCallback callback) {
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... voids) {
                try {
                    Request request = null;
                    Log.i(TAG, "URL: " + url);
                    if (method.equals("GET")) {
                        request = new Request.Builder()
                                .url(url)
                                .addHeader("User-Agent", "GlassNav/1.0")
                                .addHeader("Content-Type", "application/json")
                                .addHeader("Accept", "application/json")
                                .build();
                    } else if (method.equals("POST")) {
                        RequestBody requestBody;
                        if (jsonBody != null) {
                            requestBody = RequestBody.create(
                                    MediaType.parse("application/json; charset=utf-8"),
                                    jsonBody.toString()
                            );
                            request = new Request.Builder()
                                    .url(url)
                                    .post(requestBody)
                                    .addHeader("User-Agent", "GlassNav/1.0")
                                    .addHeader("Content-Type", "application/json")
                                    .addHeader("Accept", "application/json")
                                    .build();
                        } else {
                            request = new Request.Builder()
                                    .url(url)
                                    .addHeader("User-Agent", "GlassNav/1.0")
                                    .addHeader("Accept", "application/json")
                                    .build();
                        }
                    }

                    Response response = client.newCall(request).execute();

                    if (!response.isSuccessful()) {
                        String errorBody = response.body().string();
                        Log.e(TAG, "Unsuccessful HTTP Response Code: " + response.code());
                        Log.e(TAG, "Unsuccessful HTTP Response Message: " + response.message());
                        Log.e(TAG, "Unsuccessful HTTP Response Body: " + errorBody);
                        return "Error in response: " + errorBody;
                    }

                    return response.body().string();

                } catch (Exception e) {
                    Log.e(TAG, "Complete Error Details:", e);
                    return "Error in request: " + e.getMessage();
                }
            }

            @Override
            protected void onPostExecute(String result) {
                // Check if the result indicates an error
                if (result.startsWith("Error")) {
                    // Call onError method if there's an error
                    if (callback != null) {
                        callback.onError(result);
                    }
                } else {
                    // Call onSuccess method with the response
                    if (callback != null) {
                        callback.onSuccess(result);
                    }
                }
            }
        }.execute();
    }
}