package io.github.xtls.xray4magisk.adapters;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.snackbar.Snackbar;
import io.github.xtls.xray4magisk.App;
import io.github.xtls.xray4magisk.BuildConfig;
import io.github.xtls.xray4magisk.ConfigManager;
import io.github.xtls.xray4magisk.R;
import io.github.xtls.xray4magisk.ui.activity.AppListActivity;
import io.github.xtls.xray4magisk.util.GlideApp;
import rikka.widget.switchbar.SwitchBar;

import java.util.*;

import static android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS;

@SuppressLint("NotifyDataSetChanged")
public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> implements Filterable {

    private final AppListActivity activity;
    private final PackageManager pm;
    private final SharedPreferences preferences;
    private final HashSet<AppInfo> checkedList = new HashSet<>();
    private final List<AppInfo> searchList = new ArrayList<>();
    private final SwitchBar.OnCheckedChangeListener switchBarOnCheckedChangeListener = new SwitchBar.OnCheckedChangeListener() {
        @Override
        public boolean onCheckedChanged(SwitchBar view, boolean isChecked) {
            // ProxyListUtil
            // TODO: 2021/4/21
            whiteListMode=isChecked;
            notifyDataSetChanged();
            return true;
        }
    };
    private List<AppInfo> showList = new ArrayList<>();
    private ApplicationInfo selectedInfo;
    private boolean refreshing = false;
    private boolean whiteListMode = true;

    public AppListAdapter(AppListActivity activity) {
        this.activity = activity;
        preferences = App.getPreferences();
        pm = activity.getPackageManager();
        refresh(activity);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(activity).inflate(R.layout.item_app, parent, false);
        return new ViewHolder(v);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        View root;
        ImageView appIcon;
        TextView appName;
        TextView appDescription;
        MaterialCheckBox checkBox;

        ViewHolder(View itemView) {
            super(itemView);
            root = itemView.findViewById(R.id.item_root);
            appIcon = itemView.findViewById(R.id.app_icon);
            appName = itemView.findViewById(R.id.app_name);
            appDescription = itemView.findViewById(R.id.description);
            checkBox = itemView.findViewById(R.id.checkbox);
            checkBox.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.root.setAlpha(whiteListMode ? 1.0f : .5f);
        AppInfo appInfo = showList.get(position);
        boolean android = appInfo.packageName.equals("android");
        CharSequence appName;
        int appId = appInfo.applicationInfo.uid - (appInfo.applicationInfo.uid / 100000) * 100000;
        appName = String.format("%s (%s)", appInfo.label, appId);
        holder.appName.setText(appName);
        GlideApp.with(holder.appIcon)
                .load(appInfo.packageInfo)
                .into(new CustomTarget<Drawable>() {
                    @Override
                    public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                        holder.appIcon.setImageDrawable(resource);
                    }

                    @Override
                    public void onLoadCleared(@Nullable Drawable placeholder) {

                    }

                    @Override
                    public void onLoadFailed(@Nullable Drawable errorDrawable) {
                        holder.appIcon.setImageDrawable(pm.getDefaultActivityIcon());
                    }
                });
        SpannableStringBuilder sb = new SpannableStringBuilder(android ? "" : activity.getString(R.string.app_description, appInfo.packageName, appInfo.packageInfo.versionName));
        holder.appDescription.setVisibility(View.VISIBLE);
        if (android) {
            holder.appDescription.setVisibility(View.GONE);
        }
        holder.appDescription.setText(sb);

        holder.itemView.setOnCreateContextMenuListener((menu, v, menuInfo) -> {
            activity.getMenuInflater().inflate(R.menu.menu_app_item, menu);
            if (appId < 10000) {
                menu.removeItem(R.id.menu_app_store);
                menu.removeItem(R.id.menu_app_info);
            }
        });

        holder.checkBox.setOnCheckedChangeListener(null);
        holder.checkBox.setChecked(checkedList.contains(appInfo));

        holder.checkBox.setOnCheckedChangeListener((v, isChecked) -> onCheckedChange(v, isChecked, appInfo));
        holder.itemView.setOnClickListener(v -> holder.checkBox.toggle());
        holder.itemView.setOnLongClickListener(v -> {
            selectedInfo = appInfo.applicationInfo;
            return false;
        });
    }

    @Override
    public long getItemId(int position) {
        PackageInfo info = showList.get(position).packageInfo;
        return (info.packageName + "!" + info.applicationInfo.uid / 100000).hashCode();
    }

    @Override
    public Filter getFilter() {
        return new ApplicationFilter();
    }

    @Override
    public int getItemCount() {
        return showList.size();
    }

    public void refresh(Context context) {
        synchronized (this) {
            if (refreshing) {
                return;
            }
            refreshing = true;
        }
        // force?
        activity.binding.progress.setVisibility(View.INVISIBLE);
        activity.binding.progress.setIndeterminate(true);
        activity.binding.progress.setVisibility(View.VISIBLE);

        activity.binding.masterSwitch.setOnCheckedChangeListener(null);
        // TODO: 2021/4/21
        // whiteListMode = ProxyListUtil.isWhiteListMode
        activity.binding.masterSwitch.setChecked(whiteListMode);
        activity.binding.masterSwitch.setOnCheckedChangeListener(switchBarOnCheckedChangeListener);
        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> loadApps(context));
    }

    private class ApplicationFilter extends Filter {

        private boolean lowercaseContains(String s, String filter) {
            return !TextUtils.isEmpty(s) && s.toLowerCase().contains(filter);
        }

        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            if (constraint.toString().isEmpty()) {
                showList = searchList;
            } else {
                ArrayList<AppInfo> filtered = new ArrayList<>();
                String filter = constraint.toString().toLowerCase();
                for (AppInfo info : searchList) {
                    if (lowercaseContains(info.label.toString(), filter)
                            || lowercaseContains(info.packageName, filter)) {
                        filtered.add(info);
                    }
                }
                showList = filtered;
            }
            return null;
        }

        @Override
        protected void publishResults(CharSequence constraint, FilterResults results) {
            notifyDataSetChanged();
        }
    }

    public static class AppInfo {
        public PackageInfo packageInfo;
        public ApplicationInfo applicationInfo;
        public String packageName;
        public CharSequence label = null;

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            AppInfo appInfo = (AppInfo) o;
            return packageName.equals(appInfo.packageName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(packageName);
        }
    }

    private void loadApps(Context context) {
        List<PackageInfo> appList = AppHelper.getAppList(context);
        checkedList.clear();
        searchList.clear();
        showList.clear();

        checkedList.addAll(ConfigManager.getProxyList());
        HashSet<AppInfo> installedList = new HashSet<>();
        boolean emptyCheckedList = checkedList.isEmpty();
        for (PackageInfo info : appList) {
            int uid = info.applicationInfo.uid;
            if (info.packageName.equals("android") && uid / 100000 != 0) {
                continue;
            }
            AppInfo appInfo = new AppInfo();
            appInfo.packageInfo = info;
            appInfo.label = getAppLabel(info.applicationInfo, pm);
            appInfo.packageName = info.packageName;
            appInfo.applicationInfo = info.applicationInfo;
            installedList.add(appInfo);
            if (shouldHideApp(info)) {
                continue;
            }
            searchList.add(appInfo);
        }
        checkedList.retainAll(installedList);
        if (emptyCheckedList) {
            ConfigManager.setProxyList(checkedList, whiteListMode);
        }
        showList = sortApps(searchList);
        synchronized (this) {
            refreshing = false;
        }
        activity.onDataReady();
    }

    private boolean shouldHideApp(PackageInfo info) {
        if (info.packageName.equals(BuildConfig.APPLICATION_ID)) {
            return true;
        }
        if (info.packageName.equals("android")) {
            return false;
        }
        if (checkedList.contains(info)) {
            return false;
        }
        if (!preferences.getBoolean("show_modules", false)) {
            if (info.applicationInfo.metaData != null && info.applicationInfo.metaData.containsKey("xposedmodule")) {
                return true;
            }
        }
        if (!preferences.getBoolean("show_games", false)) {
            if (info.applicationInfo.category == ApplicationInfo.CATEGORY_GAME) {
                return true;
            }
            //noinspection deprecation
            if ((info.applicationInfo.flags & ApplicationInfo.FLAG_IS_GAME) != 0) {
                return true;
            }
        }
        if ((info.applicationInfo.flags & ApplicationInfo.FLAG_HAS_CODE) == 0) {
            return true;
        }
        return !preferences.getBoolean("show_system_apps", false) && (info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    public static String getAppLabel(ApplicationInfo info, PackageManager pm) {
        return info.loadLabel(pm).toString();
    }

    private List<AppInfo> sortApps(List<AppInfo> list) {
        Comparator<PackageInfo> comparator = AppHelper.getAppListComparator(preferences.getInt("list_sort", 0), pm);
        Comparator<AppInfo> frameworkComparator = (a, b) -> {
            if (a.packageName.equals("android") == b.packageName.equals("android")) {
                return comparator.compare(a.packageInfo, b.packageInfo);
            } else if (a.packageName.equals("android")) {
                return -1;
            } else {
                return 1;
            }
        };
        list.sort((a, b) -> {
            boolean aChecked = checkedList.contains(a);
            boolean bChecked = checkedList.contains(b);
            if (aChecked == bChecked) {
                return frameworkComparator.compare(a, b);
            } else if (aChecked) {
                return -1;
            } else {
                return 1;
            }
        });
        return list;
    }

    public boolean onContextItemSelected(@NonNull MenuItem item) {
        ApplicationInfo info = selectedInfo;
        if (info == null) {
            return false;
        }
        int itemId = item.getItemId();
        if (itemId == R.id.menu_app_store) {
            Uri uri = Uri.parse("market://details?id=" + info.packageName);
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                activity.startActivity(intent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else if (itemId == R.id.menu_app_info) {
            activity.startActivity(new Intent(ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", info.packageName, null)));
        } else {
            return false;
        }
        return true;
    }

    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.menu_app_list, menu);
        //Intent intent = AppHelper.getSettingsIntent(modulePackageName, pm);
        //List<String> scopeList = ModuleUtil.getInstance().getModule(modulePackageName).getScopeList();
        menu.findItem(R.id.item_show_system).setChecked(preferences.getBoolean("show_system_apps", false));
        menu.findItem(R.id.item_show_games).setChecked(preferences.getBoolean("show_games", false));
        switch (preferences.getInt("list_sort", 0)) {
            case 7:
                menu.findItem(R.id.item_sort_by_update_time_reverse).setChecked(true);
                break;
            case 6:
                menu.findItem(R.id.item_sort_by_update_time).setChecked(true);
                break;
            case 5:
                menu.findItem(R.id.item_sort_by_install_time_reverse).setChecked(true);
                break;
            case 4:
                menu.findItem(R.id.item_sort_by_install_time).setChecked(true);
                break;
            case 3:
                menu.findItem(R.id.item_sort_by_package_name_reverse).setChecked(true);
                break;
            case 2:
                menu.findItem(R.id.item_sort_by_package_name).setChecked(true);
                break;
            case 1:
                menu.findItem(R.id.item_sort_by_name_reverse).setChecked(true);
                break;
            case 0:
                menu.findItem(R.id.item_sort_by_name).setChecked(true);
                break;
        }
    }

    protected void onCheckedChange(CompoundButton buttonView, boolean isChecked, AppInfo appInfo) {
        if (isChecked) {
            checkedList.add(appInfo);
        } else {
            checkedList.remove(appInfo);
        }
        if (!ConfigManager.setProxyList(checkedList, whiteListMode)) {
            activity.makeSnackBar(R.string.failed_to_save_proxy_list, Snackbar.LENGTH_SHORT);
            if (!isChecked) {
                checkedList.add(appInfo);
            } else {
                checkedList.remove(appInfo);
            }
            buttonView.setChecked(!isChecked);
        }
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.item_show_system) {
            item.setChecked(!item.isChecked());
            preferences.edit().putBoolean("show_system_apps", item.isChecked()).apply();
        } else if (itemId == R.id.item_show_games) {
            item.setChecked(!item.isChecked());
            preferences.edit().putBoolean("show_games", item.isChecked()).apply();
        } else if (!AppHelper.onOptionsItemSelected(item, preferences)) {
            return false;
        }
        refresh(activity);
        return true;
    }
}