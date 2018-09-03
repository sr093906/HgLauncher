package mono.hg;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.support.v4.util.ArraySet;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.PopupMenu;
import android.support.v7.widget.RecyclerView;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.sothree.slidinguppanel.SlidingUpPanelLayout;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import mono.hg.adapters.AppAdapter;
import mono.hg.adapters.PinnedAppAdapter;
import mono.hg.helpers.IconPackHelper;
import mono.hg.helpers.RecyclerClick;
import mono.hg.receivers.PackageChangesReceiver;
import mono.hg.wrappers.OnTouchListener;

public class MainActivity extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener {

    boolean anim, icon_hide, list_order, shade_view,
            keyboard_focus, dark_theme, dark_theme_black, web_search_enabled,
            comfy_padding, tap_to_drawer, favourites_panel;
    Integer app_count;
    String launch_anim, search_provider, fav_orientation;
    private ArrayList<AppDetail> appList = new ArrayList<>();
    private ArrayList<AppDetail> pinnedAppList = new ArrayList<>();
    private Set<String> excludedAppList = new ArraySet<>();
    private Set<String> pinnedAppSet;
    private PackageManager manager;
    private PinnedAppAdapter pinnedApps = new PinnedAppAdapter(pinnedAppList);
    private AppAdapter apps = new AppAdapter(appList);
    private RecyclerView list, pinned_list;
    private FrameLayout searchContainer, pinnedAppsContainer;
    private EditText searchBar;
    private SlidingUpPanelLayout slidingHome;
    private View snackHolder, touchReceiver;
    private SharedPreferences prefs;
    private SharedPreferences.Editor editPrefs;

    private BroadcastReceiver packageReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Load preferences before setting layout to allow for quick theme change.
        loadPref(true);

        setContentView(R.layout.activity_main);

        manager = getPackageManager();

        LinearLayoutManager appListManager = new LinearLayoutManager(this,
                LinearLayoutManager.VERTICAL, false);

        final LinearLayoutManager pinnedAppsManager = new LinearLayoutManager(this,
                LinearLayoutManager.VERTICAL, false);

        FrameLayout appListContainer = findViewById(R.id.app_list_container);

        searchContainer = findViewById(R.id.search_container);
        pinnedAppsContainer = findViewById(R.id.pinned_apps_container);
        searchBar = findViewById(R.id.search);
        slidingHome = findViewById(R.id.slide_home);

        touchReceiver = findViewById(R.id.touch_receiver);
        View wallpaperShade = findViewById(R.id.wallpaper_shade);

        snackHolder = findViewById(R.id.snackHolder);

        list = findViewById(R.id.apps_list);
        pinned_list = findViewById(R.id.pinned_apps_list);

        apps.setHasStableIds(true);
        pinnedApps.setHasStableIds(true);

        list.setDrawingCacheEnabled(true);
        list.setDrawingCacheQuality(View.DRAWING_CACHE_QUALITY_LOW);
        list.setHasFixedSize(true);

        list.setAdapter(apps);
        list.setLayoutManager(appListManager);
        list.setItemAnimator(new DefaultItemAnimator());

        pinned_list.setAdapter(pinnedApps);
        pinned_list.setLayoutManager(pinnedAppsManager);

        // Get a list of our hidden apps, default to null if there aren't any.
        excludedAppList.addAll(prefs.getStringSet("hidden_apps", excludedAppList));

        // Workaround v21+ statusbar transparency issue.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            ViewGroup.MarginLayoutParams homeParams = (ViewGroup.MarginLayoutParams) slidingHome.getLayoutParams();
            homeParams.topMargin = Utils.getStatusBarHeight(this, getResources());
        }

        // Restore search bar visibility when available.
        if (savedInstanceState != null) {
            searchContainer.setVisibility(savedInstanceState.getInt("searchVisibility"));
        }

        // Empty out margins if they are not needed.
        if (!comfy_padding) {
            ViewGroup.MarginLayoutParams searchParams = (ViewGroup.MarginLayoutParams) searchContainer.getLayoutParams();
            ViewGroup.MarginLayoutParams listParams = (ViewGroup.MarginLayoutParams) appListContainer.getLayoutParams();
            searchParams.setMargins(0, 0, 0, 0);
            listParams.setMargins(0, 0, 0, 0);
        }

        // Get icons from icon pack.
        //TODO: This seems super slow.
        if (!prefs.getString("icon_pack", "default").equals("default")) {
            new getIconTask(this).execute();
        }

        // Start loading apps and initialising click listeners.
        loadApps(false);
        addListeners();

        registerForContextMenu(touchReceiver);

        if (packageReceiver == null) {
            registerPackageReceiver();
        }

        // Save our current app count.
        //TODO: There are better ways to accomplish this.
        app_count = appList.size() - 1;

        // Get pinned apps here, after the initialisation of getIconTask.
        pinnedAppSet = new HashSet<>(prefs.getStringSet("pinned_apps", new HashSet<String>()));
        for (String pinnedApp : pinnedAppSet) {
            Utils.loadSingleApp(this, pinnedApp, pinnedApps, pinnedAppList, true);
        }

        // Favourites bar params coaster: set its gravity, width, and height based on orientation.
        FrameLayout.LayoutParams pinContainerParams =  new FrameLayout.LayoutParams(pinnedAppsContainer.getLayoutParams());
        switch (fav_orientation) {
            case "left":
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    pinContainerParams.gravity = Gravity.LEFT;
                } else {
                    pinContainerParams.gravity = Gravity.START;
                }
                pinContainerParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
                pinContainerParams.width = getResources().getDimensionPixelSize(R.dimen.panel_size_vertical);
                pinnedAppsManager.setOrientation(LinearLayoutManager.VERTICAL);
                if (dark_theme) {
                    pinnedAppsContainer.setBackgroundResource(R.drawable.panel_left_shadow_dark);
                } else {
                    pinnedAppsContainer.setBackgroundResource(R.drawable.panel_left_shadow);
                }
                pinContainerParams.topMargin = Utils.getStatusBarHeight(this, getResources());
                break;
            case "right":
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    pinContainerParams.gravity = Gravity.RIGHT;
                } else {
                    pinContainerParams.gravity = Gravity.END;
                }
                pinContainerParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
                pinContainerParams.width = getResources().getDimensionPixelSize(R.dimen.panel_size_vertical);
                pinnedAppsManager.setOrientation(LinearLayoutManager.VERTICAL);
                if (dark_theme) {
                    pinnedAppsContainer.setBackgroundResource(R.drawable.panel_right_shadow_dark);
                } else {
                    pinnedAppsContainer.setBackgroundResource(R.drawable.panel_right_shadow);
                }
                pinContainerParams.topMargin = Utils.getStatusBarHeight(this, getResources());
                break;
            case "bottom":
                pinContainerParams.gravity = Gravity.BOTTOM;
                pinContainerParams.height = getResources().getDimensionPixelSize(R.dimen.panel_size_horizontal);
                pinContainerParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
                pinnedAppsManager.setOrientation(LinearLayoutManager.HORIZONTAL);
                if (dark_theme) {
                    pinnedAppsContainer.setBackgroundResource(R.drawable.panel_bottom_shadow_dark);
                } else {
                    pinnedAppsContainer.setBackgroundResource(R.drawable.panel_bottom_shadow);
                }
                break;
        }
        pinnedAppsContainer.setLayoutParams(pinContainerParams);

        // Switch on wallpaper shade.
        if (shade_view) {
            // Tints the navigation bar with a semi-transparent shade.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                getWindow().setNavigationBarColor(getResources().getColor(R.color.navigationBarShade));
            }
            wallpaperShade.setBackgroundResource(R.drawable.image_inner_shadow);
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_main, menu);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        return onOptionsItemSelected(item);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_settings:
                Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
                startActivity(intent);
                return true;
            case R.id.action_force_refresh:
                recreate();
                return true;
            case R.id.update_wallpaper:
                intent = new Intent(Intent.ACTION_SET_WALLPAPER);
                startActivity(Intent.createChooser(intent, getString(R.string.action_wallpaper)));
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
        switch (key) {
            case "dark_theme":
            case "dark_theme_black":
            case "shade_view_switch":
            case "comfy_padding":
            case "icon_pack":
            case "fav_orientation":
            case "dummy_restore":
                recreate();
                break;
            case "favourites_panel_switch":
                if (favourites_panel && pinnedAppsContainer.getVisibility() == View.VISIBLE) {
                    pinnedAppsContainer.setVisibility(View.GONE);
                }
                break;
            case "icon_hide_switch":
                icon_hide = prefs.getBoolean("icon_hide_switch", false);
                loadApps(true);
                apps.setUpdateFilter(true);
                break;
            case "list_order":
                list_order = prefs.getString("list_order", "alphabetical").equals("invertedAlphabetical");
                loadApps(true);
                apps.setUpdateFilter(true);
                break;
            case "removedApp":
                editPrefs.putBoolean("removedApp", false).commit();
                String packageToRemove = prefs.getString("removed_app", "none");
                AppDetail objPackage = new AppDetail(null, null, packageToRemove, false);
                if (!packageToRemove.equals("none") && appList.contains(objPackage)) {
                    appList.remove(objPackage);
                    apps.notifyItemRemoved(appList.indexOf(objPackage));
                }
                editPrefs.remove("removed_app").commit();
                apps.setUpdateFilter(true);
                break;
            case "addApp":
                editPrefs.putBoolean("addApp", false).commit();
                editPrefs.remove("added_app").commit();
                // HACK: Recreate after receiving installation.
                // A workaround for app list getting stuck in search result due to filters.
                recreate();
                break;
        }
    }

    @Override
    public void onBackPressed() {
        // Hides the panel if back is pressed.
        parseAction("panel_up", null);
    }

    @Override
    public void onPause() {
        super.onPause();
        try {
            unregisterReceiver(packageReceiver);
        } catch (IllegalArgumentException e) {
            Utils.sendLog(3, e.toString());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        loadPref(false);
        searchBar.setText(null);
        parseAction("panel_up", null);
        registerPackageReceiver();

        if (prefs.getBoolean("addApp", false)) {
            editPrefs.putBoolean("addApp", false).commit();
            Utils.loadSingleApp(this, prefs.getString("added_app", "none"), apps, appList, false);
            editPrefs.remove("added_app").commit();
        } else if (prefs.getBoolean("removedApp", false)) {
            editPrefs.putBoolean("removedApp", false).commit();
            String packageToRemove = prefs.getString("removed_app", "none");
            AppDetail objPackage = new AppDetail(null, null, packageToRemove, false);
            if (!packageToRemove.equals("none") && appList.contains(objPackage)) {
                appList.remove(objPackage);
                apps.notifyItemRemoved(appList.indexOf(objPackage));
            }
            editPrefs.remove("removed_app").commit();
        }
        apps.setUpdateFilter(true);
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        // Save search bar visibility state.
        savedInstanceState.putInt("searchVisibility", searchContainer.getVisibility());
        super.onSaveInstanceState(savedInstanceState);
    }

    private void loadApps(Boolean shouldForceRefresh) {
        Intent i = new Intent(Intent.ACTION_MAIN, null);
        i.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> availableActivities = manager.queryIntentActivities(i, 0);

        if (!list_order) {
            Collections.sort(availableActivities, Collections
                    .reverseOrder(new ResolveInfo.DisplayNameComparator(manager)));
        } else {
            Collections.sort(availableActivities, new ResolveInfo.DisplayNameComparator(manager));
        }
        // Clear the list to make sure that we aren't just adding over an existing list.
        appList.clear();
        if (shouldForceRefresh) {
            list.getRecycledViewPool().clear();
            apps.notifyDataSetChanged();
        } else {
            apps.notifyItemRangeChanged(0, 0);
        }

        // Fetch and add every app into our list, but ignore those that are in the exclusion list.
        for (ResolveInfo ri : availableActivities) {
            String packageName = ri.activityInfo.packageName;
            if (!excludedAppList.contains(packageName)) {
                String appName = ri.loadLabel(manager).toString();
                Drawable icon = null;
                Drawable getIcon = null;
                // Only show icons if user chooses so.
                if (!icon_hide) {
                    if (!prefs.getString("icon_pack", "default").equals("default")) {
                        getIcon = new IconPackHelper().getIconDrawable(this, packageName);
                    }
                    if (getIcon == null) {
                        icon = ri.activityInfo.loadIcon(manager);
                    } else {
                        icon = getIcon;
                    }
                }
                AppDetail app = new AppDetail(icon, appName, packageName,false);
                appList.add(app);
                apps.notifyItemInserted(appList.size());
            }
        }

        // Update our view cache size, now that we have got all apps on the list
        list.setItemViewCacheSize(appList.size() - 1);
    }

    // A method to launch an app based on package name.
    private void launchApp(String packageName) {
        Intent i = manager.getLaunchIntentForPackage(packageName);
        // Attempt to catch exceptions instead of crash landing directly to the floor.
        try {
            // Override app launch animation when needed.
            startActivity(i);
            switch (launch_anim) {
                case "pull_up":
                    overridePendingTransition(R.anim.pull_up, 0);
                    break;
                case "slide_in":
                    overridePendingTransition(R.anim.slide_in, 0);
                    break;
            }
        } catch (ActivityNotFoundException e) {
            Toast.makeText(MainActivity.this, R.string.err_activity_not_found, Toast.LENGTH_LONG).show();
        } catch (NullPointerException e) {
            Toast.makeText(MainActivity.this, R.string.err_activity_null, Toast.LENGTH_LONG).show();
        }
    }

    private static class getIconTask extends AsyncTask<Void, Void, Void> {
        private WeakReference<MainActivity> activityRef;

        getIconTask(MainActivity context) {
            activityRef = new WeakReference<>(context);
        }

        @Override
        protected Void doInBackground(Void... params) {
            MainActivity activity = activityRef.get();
            if (activity != null) {
                new IconPackHelper().loadIconPack(activity);
            }
            return null;
        }
    }

    private void parseAction(String action, @Nullable View actionContext) {
        InputMethodManager inputManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

        switch (action) {
            case "panel_down":
                if (slidingHome.getPanelState() == SlidingUpPanelLayout.PanelState.EXPANDED) {
                    slidingHome.setPanelState(SlidingUpPanelLayout.PanelState.COLLAPSED);
                }
                break;
            case "panel_up":
                if (slidingHome.getPanelState() == SlidingUpPanelLayout.PanelState.COLLAPSED
                        || slidingHome.getPanelState() == SlidingUpPanelLayout.PanelState.ANCHORED) {
                    slidingHome.setPanelState(SlidingUpPanelLayout.PanelState.EXPANDED);
                }
                break;
            case "hide_keyboard":
                if (inputManager != null && actionContext != null) {
                    inputManager.hideSoftInputFromWindow(actionContext.getWindowToken(), 0);
                }
                break;
            case "show_keyboard":
                if (inputManager != null && actionContext != null) {
                    inputManager.showSoftInput(actionContext, InputMethodManager.SHOW_IMPLICIT);
                }
        }
    }

    // Load available preferences.
    //TODO: This is suboptimal. Maybe try coming up with a better hax?
    private void loadPref(Boolean isInit) {
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        editPrefs = prefs.edit();
        if (isInit) {
            prefs.registerOnSharedPreferenceChangeListener(this);
        }

        launch_anim = prefs.getString("launch_anim", "default");
        icon_hide = prefs.getBoolean("icon_hide_switch", false);
        list_order = prefs.getString("list_order", "alphabetical").equals("invertedAlphabetical");
        shade_view = prefs.getBoolean("shade_view_switch", false);
        keyboard_focus = prefs.getBoolean("keyboard_focus", false);
        comfy_padding = prefs.getBoolean("comfy_padding", false);
        tap_to_drawer = prefs.getBoolean("tap_to_drawer", false);
        dark_theme = prefs.getBoolean("dark_theme", false);
        dark_theme_black = prefs.getBoolean("dark_theme_black", false);
        web_search_enabled = prefs.getBoolean("web_search_enabled", true);
        String search_provider_set = prefs.getString("search_provider", "google");
        fav_orientation = prefs.getString("fav_orientation", "left");
        favourites_panel = prefs.getBoolean("favourites_panel_switch", true);

        switch (search_provider_set) {
            case "google":
                search_provider = "https://www.google.com/search?q=";
                break;
            case "ddg":
                search_provider = "https://www.duckduckgo.com/?q=";
                break;
            case "searx":
                search_provider = "https://www.searx.me/?q=";
        }

        if (isInit) {
            // Set the app theme!
            if (dark_theme && !dark_theme_black) {
                setTheme(R.style.AppTheme_Gray_NoActionBar);
            } else if (dark_theme) {
                setTheme(R.style.AppTheme_Dark_NoActionBar);
            } else {
                setTheme(R.style.AppTheme_NoActionBar);
            }
        }
    }

    private void registerPackageReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        intentFilter.addDataScheme("package");
        packageReceiver = new PackageChangesReceiver();
        registerReceiver(packageReceiver, intentFilter);
    }

    private void addListeners() {
        // Implement listener for the search bar.
        searchBar.addTextChangedListener(new TextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // Begin filtering our list.
                apps.getFilter().filter(s);
                if (apps.shouldUpdateFilter()) {
                    apps.setUpdateFilter(false);
                }
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void afterTextChanged(Editable s) {
                // Don't allow spamming of empty spaces.
                if (s.length() > 0 && s.charAt(0) == ' ') {
                    s.delete(0, 1);
                }

                final String searchBarText = searchBar.getText().toString().trim();
                // Scroll back down to the start of the list if search query is empty.
                if (searchBarText.equals("")) {
                    list.getLayoutManager().scrollToPosition(app_count);
                } else if (!searchBarText.equals("") && web_search_enabled) {
                    // Prompt user if they want to search their query online.
                    String searchHint = String.format(getResources().getString(R.string.search_web_hint), searchBarText);
                    Snackbar.make(snackHolder, searchHint, Snackbar.LENGTH_LONG)
                            .setAction(R.string.search_web_button, new View.OnClickListener() {
                                @Override
                                public void onClick(View view) {
                                    Intent link = new Intent(Intent.ACTION_VIEW,
                                            Uri.parse(search_provider + searchBarText));
                                    synchronized (this) {
                                        startActivity(link);
                                        apps.getFilter().filter(null);
                                    }
                                }
                            }).show();
                }
            }
        });

        // Listen for keyboard enter/search key input.
        searchBar.setOnEditorActionListener(new EditText.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_NULL
                        && !searchBar.getText().toString().equals("")) {
                    if (!appList.isEmpty()) {
                        launchApp(appList.get(0).getPackageName());
                        return true;
                    }
                }
                return false;
            }
        });

        // Scroll app list down when it is being pushed by the keyboard.
        list.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right,int bottom, int oldLeft, int oldTop,int oldRight, int oldBottom) {
                list.scrollToPosition(list.getAdapter().getItemCount() - 1);
            }
        });

        // Add short click/click listener to the app list.
        RecyclerClick.addTo(list).setOnItemClickListener(new RecyclerClick.OnItemClickListener() {
            @Override
            public void onItemClicked(RecyclerView recyclerView, int position, View v) {
                synchronized (this) {
                    launchApp(appList.get(position).getPackageName());
                    apps.getFilter().filter(null);
                }
            }
        });

        // Add long click action to app list. Long click shows a menu to manage selected app.
        RecyclerClick.addTo(list).setOnItemLongClickListener(new RecyclerClick.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClicked(RecyclerView recyclerView, final int position, View v) {
                // Parse package URI for use in uninstallation and package info call.
                final String packageName = appList.get(position).getPackageName();
                final Uri packageNameUri = Uri.parse("package:" + packageName);

                // Inflate the app menu.
                PopupMenu appMenu = new PopupMenu(MainActivity.this, v);
                appMenu.getMenuInflater().inflate(R.menu.menu_app, appMenu.getMenu());

                // Remove uninstall menu if the app is a system app.
                //TODO: System apps can be 'downgraded' if they are updated. Maybe check for that?
                try {
                    ApplicationInfo appFlags = manager.getApplicationInfo(packageName, 0);
                    if ((appFlags.flags & ApplicationInfo.FLAG_SYSTEM) == 1) {
                        appMenu.getMenu().removeItem(R.id.action_uninstall);
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    Utils.sendLog(3, e.toString());
                } finally {
                    // Show the menu.
                    appMenu.show();
                }

                //TODO: Why does this look so hackish.
                appMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    public boolean onMenuItemClick(MenuItem item) {
                        switch (item.getItemId()) {
                            case R.id.action_pin:
                                Utils.loadSingleApp(MainActivity.this, appList.get(position).getPackageName(), pinnedApps, pinnedAppList, true);
                                pinnedAppSet.add(packageName);
                                editPrefs.putStringSet("pinned_apps", pinnedAppSet).apply();
                                if (!favourites_panel) {
                                    Toast.makeText(MainActivity.this, R.string.warn_pinning, Toast.LENGTH_SHORT).show();
                                }
                                break;
                            case R.id.action_info:
                                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        packageNameUri));
                                break;
                            case R.id.action_uninstall:
                                startActivity(new Intent(Intent.ACTION_DELETE, packageNameUri));
                                break;
                            case R.id.action_hide:
                                // Add the app's package name to the exclusion list.
                                excludedAppList.add(packageName);
                                editPrefs.putStringSet("hidden_apps", excludedAppList).apply();
                                // Reload the app list!
                                appList.remove(new AppDetail(null, null, packageName, false));
                                apps.notifyItemRemoved(position);
                                if (searchBar.getText().toString().equals("")) {
                                    apps.setUpdateFilter(true);
                                } else {
                                    //TODO: Remove this when loadApps become less of a behemoth.
                                    recreate();
                                }
                                break;
                        }
                        return true;
                    }
                });
                return false;
            }
        });

        // Add long click action to pinned apps.
        RecyclerClick.addTo(pinned_list).setOnItemLongClickListener(new RecyclerClick.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClicked(RecyclerView recyclerView, final int position, View v) {
                // Parse package URI for use in uninstallation and package info call.
                final String packageName = pinnedAppList.get(position).getPackageName();
                final Uri packageNameUri = Uri.parse("package:" + packageName);

                // Inflate the app menu.
                PopupMenu appMenu = new PopupMenu(MainActivity.this, v);
                appMenu.getMenuInflater().inflate(R.menu.menu_pinned_app, appMenu.getMenu());

                // Remove uninstall menu if the app is a system app.
                try {
                    ApplicationInfo appFlags = manager.getApplicationInfo(packageName, 0);
                    if ((appFlags.flags & ApplicationInfo.FLAG_SYSTEM) == 1) {
                        appMenu.getMenu().removeItem(R.id.action_uninstall);
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    Utils.sendLog(3, e.toString());
                } finally {
                    // Show the menu.
                    appMenu.show();
                }

                //TODO: Still looks hackish.
                appMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    public boolean onMenuItemClick(MenuItem item) {
                        switch (item.getItemId()) {
                            case R.id.action_unpin:
                                pinnedAppList.remove(position);
                                pinnedApps.notifyItemRemoved(position);
                                pinnedAppSet.remove(packageName);
                                editPrefs.putStringSet("pinned_apps", pinnedAppSet).commit();
                                break;
                            case R.id.action_info:
                                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        packageNameUri));
                                break;
                            case R.id.action_uninstall:
                                startActivity(new Intent(Intent.ACTION_DELETE, packageNameUri));
                                break;
                        }
                        return true;
                    }
                });
                return false;
            }
        });

        RecyclerClick.addTo(pinned_list).setOnItemClickListener(new RecyclerClick.OnItemClickListener() {
            @Override
            public void onItemClicked(RecyclerView recyclerView, int position, View v) {
                launchApp(pinnedAppList.get(position).getPackageName());
            }
        });

        slidingHome.addPanelSlideListener(new SlidingUpPanelLayout.PanelSlideListener() {
            @Override
            public void onPanelSlide(View view, float v) {
                // Hide the keyboard once the panel has slid to a certain point.
                if (v <= 0.35f) {
                    parseAction("hide_keyboard", searchBar);
                }
            }

            @Override
            public void onPanelStateChanged(View panel, SlidingUpPanelLayout.PanelState previousState, SlidingUpPanelLayout.PanelState newState) {
                if (newState == SlidingUpPanelLayout.PanelState.COLLAPSED
                        || newState == SlidingUpPanelLayout.PanelState.DRAGGING) {
                    // Empty out search bar text
                    searchBar.setText(null);

                    // Automatically show keyboard when the panel is called.
                    if (keyboard_focus && previousState != SlidingUpPanelLayout.PanelState.COLLAPSED) {
                        parseAction("show_keyboard", searchBar);
                        searchBar.requestFocus();
                    }
                    // Animate search container entering the view.
                    searchContainer.animate().alpha(1.0f).setDuration(100)
                            .setListener(new AnimatorListenerAdapter() {
                                @Override
                                public void onAnimationEnd(Animator animation) {
                                    searchContainer.setVisibility(View.VISIBLE);
                                    searchContainer.clearAnimation();
                                }
                            });
                } else if (newState == SlidingUpPanelLayout.PanelState.EXPANDED) {
                    // Hide keyboard if container is invisible.
                    parseAction("hide_keyboard", searchBar);

                    // Also animate the container when it's disappearing.
                    searchContainer.animate().alpha(0.0f).setDuration(200)
                            .setListener(new AnimatorListenerAdapter() {
                                @Override
                                public void onAnimationEnd(Animator animation) {
                                    searchContainer.setVisibility(View.INVISIBLE);
                                    searchContainer.clearAnimation();
                                }
                            });
                } else if (newState == SlidingUpPanelLayout.PanelState.ANCHORED) {
                    slidingHome.setPanelState(SlidingUpPanelLayout.PanelState.EXPANDED);
                }
            }
        });


        // Handle touch events in touchReceiver.
        touchReceiver.setOnTouchListener(new OnTouchListener(this) {
            @Override
            public void onSwipeLeft() {
                // Dismiss favourites panel.
                if (favourites_panel && pinnedAppsContainer.getVisibility() == View.VISIBLE
                        && fav_orientation.equals("left")) {
                    Animation slide = AnimationUtils.loadAnimation(MainActivity.this, R.anim.slide_out_left);
                    pinnedAppsContainer.setAnimation(slide);
                    pinnedAppsContainer.setVisibility(View.INVISIBLE);
                } else if (favourites_panel &&pinnedAppsContainer.getVisibility() == View.INVISIBLE
                        && fav_orientation.equals("right")) {
                    Animation slide = AnimationUtils.loadAnimation(MainActivity.this, R.anim.slide_in_right);
                    pinnedAppsContainer.setAnimation(slide);
                    pinnedAppsContainer.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onSwipeRight() {
                // Show favourites panel on swipe.
                if (favourites_panel && pinnedAppsContainer.getVisibility() == View.INVISIBLE
                        && fav_orientation.equals("left")) {
                    Animation slide = AnimationUtils.loadAnimation(MainActivity.this, R.anim.slide_out_right);
                    pinnedAppsContainer.setAnimation(slide);
                    pinnedAppsContainer.setVisibility(View.VISIBLE);
                } else if (favourites_panel && pinnedAppsContainer.getVisibility() == View.VISIBLE
                        && fav_orientation.equals("right")) {
                    Animation slide = AnimationUtils.loadAnimation(MainActivity.this, R.anim.slide_in_left);
                    pinnedAppsContainer.setAnimation(slide);
                    pinnedAppsContainer.setVisibility(View.INVISIBLE);
                }
            }

            @Override
            public void onSwipeUp() {
                // Show favourites panel on swipe up when its orientation is in the bottom.
                if (favourites_panel && pinnedAppsContainer.getVisibility() == View.INVISIBLE
                        && fav_orientation.equals("bottom")) {
                    Animation slide = AnimationUtils.loadAnimation(MainActivity.this, R.anim.pull_up);
                    pinnedAppsContainer.setAnimation(slide);
                    pinnedAppsContainer.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onSwipeDown() {
                // Show the app panel and dismiss favourites panel when swiped down.
                if (pinnedAppsContainer.getVisibility() == View.VISIBLE) {
                    Animation push = AnimationUtils.loadAnimation(MainActivity.this, R.anim.push_down);
                    pinnedAppsContainer.setAnimation(push);
                    pinnedAppsContainer.setVisibility(View.INVISIBLE);
                }
                parseAction("panel_down", null);
            }

            @Override
            public void onLongPress() {
                // Show context menu when touchReceiver is long pressed.
                touchReceiver.showContextMenu();
            }

            @Override
            public void onClick() {
                // Dismiss favourites bar.
                if (pinnedAppsContainer.getVisibility() == View.VISIBLE) {
                    Animation push = AnimationUtils.loadAnimation(MainActivity.this, R.anim.push_down);
                    pinnedAppsContainer.setAnimation(push);
                    pinnedAppsContainer.setVisibility(View.INVISIBLE);
                }

                // Imitate sliding panel drag view behaviour; show the app panel on click.
                if (tap_to_drawer) {
                    parseAction("panel_down", null);
                }
            }
        });
    }
}