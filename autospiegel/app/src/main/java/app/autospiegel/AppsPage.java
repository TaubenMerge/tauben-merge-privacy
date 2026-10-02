package app.autospiegel;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The radio's own apps, e.g. a navigation app with a map or the car settings. They keep
 * working without the phone (and have internet through the phone's hotspot).
 */
final class AppsPage extends Page {
    private final List<ResolveInfo> apps = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private BaseAdapter adapter;

    AppsPage(RadioActivity activity) {
        super(activity);
    }

    @Override
    View view() {
        GridView grid = new GridView(a);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(Ui.dp(a, 120));
        grid.setVerticalSpacing(Ui.dp(a, 8));
        grid.setHorizontalSpacing(Ui.dp(a, 8));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        int p = Ui.dp(a, 12);
        grid.setPadding(p, p, p, p);
        grid.setClipToPadding(false);
        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return apps.size();
            }

            @Override
            public Object getItem(int position) {
                return apps.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout cell = (LinearLayout) convertView;
                if (cell == null) {
                    cell = Ui.column(a);
                    cell.setGravity(Gravity.CENTER_HORIZONTAL);
                    int cp = Ui.dp(a, 8);
                    cell.setPadding(cp, cp, cp, cp);
                    cell.setBackground(Ui.round(a, Ui.CARD, 16));
                    cell.addView(new ImageView(a), Ui.size(a, 56, 56));
                    TextView label = Ui.text(a, "", 15, Ui.TEXT, false, 2);
                    label.setGravity(Gravity.CENTER);
                    cell.addView(label, Ui.margins(Ui.fullWidth(), a, 0, 6, 0, 0));
                }
                Drawable icon;
                try {
                    icon = apps.get(position).loadIcon(a.getPackageManager());
                } catch (RuntimeException e) {
                    icon = null;
                }
                ((ImageView) cell.getChildAt(0)).setImageDrawable(icon);
                ((TextView) cell.getChildAt(1)).setText(labels.get(position));
                return cell;
            }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                ResolveInfo info = apps.get(position);
                Intent intent = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(info.activityInfo.packageName, info.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    a.startActivity(intent);
                } catch (ActivityNotFoundException | SecurityException e) {
                    Toast.makeText(a, R.string.radio_apps_failed, Toast.LENGTH_SHORT).show();
                }
            }
        });
        return grid;
    }

    @Override
    void refresh() {
        if (!apps.isEmpty()) {
            return;
        }
        final PackageManager pm = a.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        final List<ResolveInfo> found = new ArrayList<>();
        for (ResolveInfo info : pm.queryIntentActivities(launcher, 0)) {
            if (!info.activityInfo.packageName.equals(a.getPackageName())) {
                found.add(info);
            }
        }
        final List<String> names = new ArrayList<>();
        for (ResolveInfo info : found) {
            names.add(String.valueOf(info.loadLabel(pm)));
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            order.add(i);
        }
        Collections.sort(order, new Comparator<Integer>() {
            @Override
            public int compare(Integer x, Integer y) {
                return names.get(x).compareToIgnoreCase(names.get(y));
            }
        });
        for (int i : order) {
            apps.add(found.get(i));
            labels.add(names.get(i));
        }
        adapter.notifyDataSetChanged();
    }
}
