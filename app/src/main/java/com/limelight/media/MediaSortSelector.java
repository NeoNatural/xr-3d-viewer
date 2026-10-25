package com.limelight.media;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import com.limelight.R;

import java.util.List;

/** Shared, persistent sort selection for local and SMB browsers. */
@SuppressLint("ViewConstructor") // Created programmatically with a required change listener.
public final class MediaSortSelector extends Spinner {
    private static final String PREFS = "media_browser_sort";
    private static final String ORDER = "order";
    private MediaDirectoryOrder.SortOrder selected;

    public MediaSortSelector(Context context, Runnable onChanged) {
        super(context);
        selected = MediaDirectoryOrder.SortOrder.fromPreference(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ORDER, null));
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(context,
                R.array.media_sort_options, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        setAdapter(adapter);
        setSelection(selected.ordinal());
        setContentDescription(context.getString(R.string.media_sort_order));
        setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) { }
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                 int position, long id) {
                MediaDirectoryOrder.SortOrder next = MediaDirectoryOrder.SortOrder.values()[position];
                if (selected == next) return;
                selected = next;
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString(ORDER, selected.name()).apply();
                onChanged.run();
            }
        });
    }

    public void sort(List<MediaEntry> entries) {
        MediaDirectoryOrder.sort(entries, selected);
    }
}
