// SPDX-License-Identifier: Apache-2.0
// Stub of com.google.android.glass.widget.CardScrollAdapter — abstract
// adapter used by CardScrollView. Subclasses implement getCount(),
// getItem(int), getView(int, View, ViewGroup), findIdPosition() etc.
// Real impl provided by the Glass GDK at runtime.
package com.google.android.glass.widget;

import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

public abstract class CardScrollAdapter extends BaseAdapter {

    /** Returns the position of the item with the given id.
     *  Default impl returns AdapterView.INVALID_POSITION since most
     *  GlassNav adapters don't bother overriding it; the real GDK
     *  also provides a default like this. */
    public int findIdPosition(Object id) { return -1; }

    /** Returns the position of the given item. */
    public abstract int getPosition(Object item);

    public float getProposedCardScrollPosition(int position) { return position; }
    public int findItemPosition(Object item) { return getPosition(item); }

    @Override public long getItemId(int position) { return position; }
}
