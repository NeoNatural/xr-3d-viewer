package com.limelight.smb;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.StaticImageXrActivity;
import com.limelight.binding.video.StillImageDepthBatcher;
import com.limelight.media.MediaEntry;
import com.limelight.media.MediaDirectoryOrder;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal direct SMB browser. Network operations never run on the UI thread. */
public final class SmbBrowserActivity extends Activity {
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Deque<String> parents = new ArrayDeque<>();
    private final List<MediaEntry> entries = new ArrayList<>();
    private EditText hostField;
    private EditText shareField;
    private EditText domainField;
    private EditText userField;
    private EditText passwordField;
    private Spinner savedTargets;
    private Button forgetButton;
    private final List<SmbProfileStore.Profile> profiles = new ArrayList<>();
    private TextView status;
    private Button connectButton;
    private Button changeNasButton;
    private Button lastImageButton;
    private LinearLayout connectionPanel;
    private ListView listView;
    private SmbThumbnailAdapter adapter;
    private SmbStorage storage;
    private String currentUri;
    private boolean busy;
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StillImageDepthBatcher.shared(this).prewarm();

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int)(16 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        connectionPanel = new LinearLayout(this);
        connectionPanel.setOrientation(LinearLayout.VERTICAL);
        layout.addView(connectionPanel);
        savedTargets = new Spinner(this);
        connectionPanel.addView(savedTargets);
        forgetButton = new Button(this);
        forgetButton.setText("Forget selected NAS");
        connectionPanel.addView(forgetButton);
        hostField = field(connectionPanel, "NAS host or IP", InputType.TYPE_CLASS_TEXT);
        shareField = field(connectionPanel, "Share", InputType.TYPE_CLASS_TEXT);
        domainField = field(connectionPanel, "Domain (optional)", InputType.TYPE_CLASS_TEXT);
        userField = field(connectionPanel, "Username", InputType.TYPE_CLASS_TEXT);
        passwordField = field(connectionPanel, "Password", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        connectButton = new Button(this);
        connectButton.setText("Connect");
        connectionPanel.addView(connectButton);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        layout.addView(actions);
        changeNasButton = new Button(this);
        changeNasButton.setText("Change NAS");
        changeNasButton.setVisibility(View.GONE);
        actions.addView(changeNasButton, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        lastImageButton = new Button(this);
        lastImageButton.setText("Open last image");
        lastImageButton.setSingleLine(true);
        lastImageButton.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        lastImageButton.setVisibility(View.GONE);
        actions.addView(lastImageButton, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 2));
        status = new TextView(this);
        status.setText("Enter a NAS and share to browse directly over SMB.");
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        layout.addView(status);

        listView = new ListView(this);
        layout.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(layout);

        connectButton.setOnClickListener(v -> connect());
        changeNasButton.setOnClickListener(v -> {
            connectionPanel.setVisibility(View.VISIBLE);
            changeNasButton.setVisibility(View.GONE);
        });
        lastImageButton.setOnClickListener(v -> openLastImage());
        savedTargets.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                                 int position, long id) {
                forgetButton.setEnabled(position > 0);
                if (position > 0 && position <= profiles.size()) {
                    SmbProfileStore.Profile profile = profiles.get(position - 1);
                    hostField.setText(profile.host);
                    shareField.setText(profile.share);
                    domainField.setText(profile.domain);
                    userField.setText(profile.username);
                    passwordField.setText(profile.password);
                }
            }
        });
        forgetButton.setOnClickListener(v -> forgetSelected());
        listView.setOnItemClickListener((parent, view, position, id) -> open(entries.get(position)));
        refreshProfiles();
    }

    private void refreshProfiles() {
        profiles.clear();
        ArrayList<String> names = new ArrayList<>();
        names.add("Saved NAS targets");
        try {
            profiles.addAll(SmbProfileStore.load(this));
            for (SmbProfileStore.Profile profile : profiles) names.add(profile.label());
        } catch (IOException e) {
            status.setText(e.getMessage());
        }
        ArrayAdapter<String> savedAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, names);
        savedAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        savedTargets.setAdapter(savedAdapter);
        forgetButton.setEnabled(false);
    }

    private void forgetSelected() {
        int selected = savedTargets.getSelectedItemPosition();
        if (selected <= 0 || selected > profiles.size()) return;
        try {
            SmbProfileStore.remove(this, profiles.get(selected - 1));
            refreshProfiles();
        } catch (IOException e) {
            status.setText(e.getMessage());
        }
    }

    private EditText field(LinearLayout layout, String hint, int inputType) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setHint(hint);
        edit.setInputType(inputType);
        layout.addView(edit);
        return edit;
    }

    private void connect() {
        if (busy) {
            return;
        }
        String host = hostField.getText().toString().trim();
        String share = shareField.getText().toString().trim();
        String domain = domainField.getText().toString().trim();
        String user = userField.getText().toString();
        String password = passwordField.getText().toString();
        if (host.isEmpty() || share.isEmpty()) {
            status.setText("Host and share are required.");
            return;
        }
        setBusy(true, "Connecting…");
        network.execute(() -> {
            SmbStorage candidate = null;
            try {
                candidate = new SmbStorage(host, share, domain, user, password);
                List<MediaEntry> found = candidate.list(candidate.rootUri());
                String saveError = null;
                try {
                    SmbProfileStore.save(this, new SmbProfileStore.Profile(
                            host, share, domain, user, password));
                } catch (IOException e) {
                    saveError = e.getMessage();
                }
                SmbClientManager.replace(candidate);
                SmbStorage connected = candidate;
                String finalSaveError = saveError;
                runOnUiThread(() -> {
                    if (destroyed) return;
                    storage = connected;
                    parents.clear();
                    refreshProfiles();
                    connectionPanel.setVisibility(View.GONE);
                    changeNasButton.setVisibility(View.VISIBLE);
                    showDirectory(connected.rootUri(), found);
                    if (finalSaveError != null) status.setText("Connected, but " + finalSaveError);
                });
            } catch (Exception error) {
                if (candidate != null && candidate != SmbClientManager.active()) {
                    try { candidate.close(); } catch (IOException ignored) { }
                }
                showError("SMB connection failed", error);
            }
        });
    }

    private void navigate(String uri, boolean saveParent, boolean goingBack) {
        if (busy || storage == null) {
            return;
        }
        setBusy(true, "Loading " + uri);
        network.execute(() -> {
            try {
                List<MediaEntry> found = storage.list(uri);
                runOnUiThread(() -> {
                    if (destroyed) return;
                    if (saveParent && currentUri != null) parents.push(currentUri);
                    if (goingBack) parents.pop();
                    showDirectory(uri, found);
                });
            } catch (Exception error) {
                showError("SMB folder failed", error);
            }
        });
    }

    private void showDirectory(String uri, List<MediaEntry> found) {
        currentUri = uri;
        if (adapter != null) adapter.close();
        entries.clear();
        entries.addAll(found);
        SmbClientManager.setDirectorySnapshot(uri, found);
        adapter = new SmbThumbnailAdapter(this, storage, entries);
        listView.setAdapter(adapter);
        updateLastImageButton();
        setBusy(false, uri + " — " + found.size() + " items");
    }

    private void updateLastImageButton() {
        String filename = SmbBrowseHistory.lastFilename(this, currentUri);
        boolean found = MediaDirectoryOrder.fileNamed(entries, filename) != null;
        lastImageButton.setVisibility(found ? View.VISIBLE : View.GONE);
        if (found) lastImageButton.setText("Open last image: " + filename);
    }

    private void openLastImage() {
        String filename = SmbBrowseHistory.lastFilename(this, currentUri);
        MediaEntry entry = MediaDirectoryOrder.fileNamed(entries, filename);
        if (entry != null) open(entry);
        else updateLastImageButton();
    }

    private void open(MediaEntry entry) {
        if (entry.directory) {
            navigate(entry.uri, true, false);
            return;
        }
        String name = entry.name.toLowerCase(Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".png") || name.endsWith(".webp")) {
            Intent intent = new Intent(this, StaticImageXrActivity.class);
            intent.putExtra(StaticImageXrActivity.EXTRA_SMB_URI, entry.uri);
            intent.putExtra(StaticImageXrActivity.EXTRA_SMB_DIRECTORY, currentUri);
            intent.putExtra(StaticImageXrActivity.EXTRA_OPEN_STARTED_NS, System.nanoTime());
            // The paused browser otherwise keeps its 12 MiB thumbnail cache and
            // ImageViews alive throughout the immersive session.
            listView.setAdapter(null);
            if (adapter != null) adapter.close();
            adapter = null;
            startActivity(intent);
        } else {
            Toast.makeText(this, "Image files are supported in this build.",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter == null && storage != null && currentUri != null) {
            adapter = new SmbThumbnailAdapter(this, storage, entries);
            listView.setAdapter(adapter);
        }
        if (lastImageButton != null && currentUri != null) updateLastImageButton();
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        connectButton.setEnabled(!value);
        listView.setEnabled(!value);
        status.setText(message);
    }

    private void showError(String prefix, Exception error) {
        runOnUiThread(() -> {
            if (!destroyed) {
                setBusy(false, prefix + ": " + error.getMessage());
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (!busy && !parents.isEmpty()) {
            navigate(parents.peek(), false, true);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (adapter != null) adapter.close();
        network.shutdownNow();
        super.onDestroy();
    }
}
