// SPDX-License-Identifier: Apache-2.0
// Stub of com.google.android.glass.widget.CardBuilder — the real GDK
// CardBuilder constructs Glass-style card views (text + icon + image
// + menu layouts). Real impl provided by the GDK at runtime.
package com.google.android.glass.widget;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

public class CardBuilder {
    public enum Layout {
        TEXT,
        TEXT_FIXED,
        COLUMNS,
        COLUMNS_FIXED,
        CAPTION,
        TITLE,
        AUTHOR,
        MENU,
        EMBED_INSIDE,
        ALERT,
    }

    public CardBuilder(Context context, Layout layout) { /* stub */ }

    public CardBuilder setText(CharSequence text) { return this; }
    public CardBuilder setText(int resId) { return this; }
    public CardBuilder setIcon(Drawable icon) { return this; }
    public CardBuilder setIcon(int resId) { return this; }
    public CardBuilder setFootnote(CharSequence footnote) { return this; }
    public CardBuilder setFootnote(int resId) { return this; }
    public CardBuilder setTimestamp(CharSequence timestamp) { return this; }
    public CardBuilder setHeading(CharSequence heading) { return this; }
    public CardBuilder showStackIndicator(boolean visible) { return this; }
    public CardBuilder addImage(Drawable image) { return this; }
    public CardBuilder addImage(int resId) { return this; }
    public CardBuilder setEmbeddedLayout(int layoutResId) { return this; }

    public View getView() { return null; }
    public View getView(View convertView, ViewGroup parent) { return null; }
    public int getItemViewType() { return 0; }

    public static int getViewTypeCount() { return Layout.values().length; }
}
