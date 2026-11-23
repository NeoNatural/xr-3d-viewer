package com.limelight.web;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.EnglishActivity;
import com.limelight.R;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

import java.util.List;

public final class WebBrowserActivity extends EnglishActivity implements GeckoSession.NavigationDelegate {
    private static final int WATCH_VIDEO = 1;
    private GeckoView browser;
    private HorizontalScrollView toolbar;
    private LinearLayout actions;
    private TextView origin;
    private Button watch;
    private WebVideoTab tab;
    private boolean canGoBack;
    private String location = WebVideoTab.BILIBILI;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column);
        toolbar = new HorizontalScrollView(this);
        actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(actions);
        column.addView(toolbar);
        addSite(getString(R.string.web_bilibili), WebVideoTab.BILIBILI);
        addSite("YouTube", WebVideoTab.YOUTUBE);
        Button enterUrl = new Button(this);
        enterUrl.setText(R.string.web_enter_url);
        enterUrl.setOnClickListener(v -> showAddressDialog());
        actions.addView(enterUrl);
        CheckBox fullResolution = new CheckBox(this);
        fullResolution.setText(R.string.web_full_resolution);
        fullResolution.setChecked(getPreferences(MODE_PRIVATE).getBoolean("fullResolution", false));
        fullResolution.setOnCheckedChangeListener((button, checked) ->
                getPreferences(MODE_PRIVATE).edit().putBoolean("fullResolution", checked).apply());
        actions.addView(fullResolution);
        origin = new TextView(this);
        origin.setSingleLine(true);
        actions.addView(origin);
        browser = new GeckoView(this);
        column.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1));
        watch = new Button(this);
        watch.setText(R.string.web_watch_3d);
        watch.setOnClickListener(v -> enterXr());
        root.addView(watch, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));
        setContentView(root);

        tab = (WebVideoTab) getLastNonConfigurationInstance();
        if (state != null) {
            location = state.getString("location", location);
            if (tab == null) tab = WebVideoTab.presenting(state.getString(WebVideoTab.EXTRA_TAB));
            canGoBack = state.getBoolean("canGoBack");
        }
        boolean newTab = tab == null;
        if (newTab) tab = new WebVideoTab(this);
        tab.session.setNavigationDelegate(this);
        tab.stateChanged = this::updateUi;
        if (WebVideoTab.presenting(tab.id) == null) browser.setSession(tab.session);
        if (newTab) tab.session.loadUri(location);
        updateUi();
    }

    private void addSite(String label, String url) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(v -> tab.session.loadUri(url));
        actions.addView(button);
    }

    private void showAddressDialog() {
        EditText address = new EditText(this);
        address.setSingleLine(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setHint("https://example.com");
        address.setText(location);
        address.selectAll();

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.web_enter_url)
                .setView(address)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.web_open_url, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            Button open = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            open.setOnClickListener(v -> {
                String url = WebAddress.fromInput(address.getText().toString());
                if (url == null) {
                    address.setError(getString(R.string.web_invalid_url));
                    address.requestFocus();
                    return;
                }
                dialog.dismiss();
                tab.session.loadUri(url);
            });
            address.setOnEditorActionListener((view, action, event) -> {
                if (action == EditorInfo.IME_ACTION_GO || (event != null
                        && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN)) {
                    open.performClick();
                    return true;
                }
                return false;
            });
        });
        address.requestFocus();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dialog.show();
    }

    private void updateUi() {
        if (tab.crashed) {
            Toast.makeText(this, R.string.web_crash, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        toolbar.setVisibility(tab.fullscreen ? View.GONE : View.VISIBLE);
        watch.setVisibility(tab.fullscreen ? View.VISIBLE : View.GONE);
        watch.setEnabled(WebVideoTab.presenting(tab.id) == null);
        origin.setText(Uri.parse(location).getHost());
    }

    private void enterXr() {
        if (!tab.fullscreen || browser.getSession() == null) return;
        tab.present();
        watch.setEnabled(false);
        browser.releaseSession();
        tab.session.setActive(true);
        startActivityForResult(new Intent(this, WebVideoXrActivity.class)
                .putExtra(WebVideoTab.EXTRA_TAB, tab.id)
                .putExtra(WebVideoXrActivity.FULL_RESOLUTION,
                        getPreferences(MODE_PRIVATE).getBoolean("fullResolution", false)), WATCH_VIDEO);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != WATCH_VIDEO || tab.crashed) return;
        if (tab.leaveXr != null) tab.leaveXr.run();
        tab.endPresentation();
        if (browser.getSession() == null) browser.setSession(tab.session);
        updateUi();
    }

    @Override public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession session, LoadRequest request) {
        if (!WebAddress.isWebScheme(Uri.parse(request.uri).getScheme())) {
            return GeckoResult.fromValue(AllowOrDeny.DENY);
        }
        // Bilibili opens many video links in a new tab. Keep the selected video in this session.
        if (request.target == TARGET_WINDOW_NEW) {
            if (request.hasUserGesture) session.loadUri(request.uri);
            return GeckoResult.fromValue(AllowOrDeny.DENY);
        }
        return GeckoResult.fromValue(AllowOrDeny.ALLOW);
    }

    @Override public void onLocationChange(GeckoSession session, String url,
            List<GeckoSession.PermissionDelegate.ContentPermission> permissions, Boolean userGesture) {
        if (url != null && WebAddress.isWebScheme(Uri.parse(url).getScheme())) {
            location = url;
            origin.setText(Uri.parse(url).getHost());
        }
    }

    @Override public void onCanGoBack(GeckoSession session, boolean value) { canGoBack = value; }

    @Override public void onBackPressed() {
        if (tab.fullscreen) tab.session.exitFullScreen();
        else if (canGoBack) tab.session.goBack();
        else super.onBackPressed();
    }

    @Override public Object onRetainNonConfigurationInstance() { return tab; }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("location", location);
        state.putString(WebVideoTab.EXTRA_TAB, tab.id);
        state.putBoolean("canGoBack", canGoBack);
    }

    @Override protected void onDestroy() {
        browser.releaseSession();
        tab.stateChanged = null;
        tab.session.setNavigationDelegate(null);
        if (!isChangingConfigurations() && (isFinishing() || WebVideoTab.presenting(tab.id) == null)) {
            if (tab.leaveXr != null) tab.leaveXr.run();
            tab.endPresentation();
            if (tab.session.isOpen()) tab.session.close();
        }
        super.onDestroy();
    }
}
