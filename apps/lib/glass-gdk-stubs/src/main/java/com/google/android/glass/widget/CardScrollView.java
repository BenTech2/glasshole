// SPDX-License-Identifier: Apache-2.0
// Stub of com.google.android.glass.widget.CardScrollView — the
// horizontal card carousel for Glass nav patterns. Real impl
// provided by the Glass GDK at runtime.
package com.google.android.glass.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.AdapterView;
import android.widget.FrameLayout;

public class CardScrollView extends FrameLayout {
    public CardScrollView(Context context) { super(context); }
    public CardScrollView(Context context, AttributeSet attrs) { super(context, attrs); }

    public void setAdapter(CardScrollAdapter adapter) { /* stub */ }
    public CardScrollAdapter getAdapter() { return null; }

    public void activate() { /* stub */ }
    public void deactivate() { /* stub */ }

    public void setHorizontalScrollBarEnabled(boolean enabled) { /* stub */ }

    public void setOnItemClickListener(AdapterView.OnItemClickListener listener) { /* stub */ }
    public void setOnItemSelectedListener(AdapterView.OnItemSelectedListener listener) { /* stub */ }

    public int getSelectedItemPosition() { return 0; }
    public void setSelection(int position) { /* stub */ }

    public View getSelectedView() { return null; }
}
