package org.c0fle4.FontRedirect;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {

    private List<AppInfo> items = new ArrayList<>();
    private final OnSelectionChanged listener;

    public interface OnSelectionChanged {
        void onSelectionChanged(String packageName, boolean selected);
    }

    public AppListAdapter(OnSelectionChanged listener) {
        this.listener = listener;
    }

    public void setItems(List<AppInfo> items) {
        this.items = items;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppInfo info = items.get(position);
        holder.name.setText(info.getAppName());
        holder.pkg.setText(info.getPackageName());
        holder.icon.setImageDrawable(info.getIcon());
        holder.check.setChecked(info.isSelected());
        holder.itemView.setOnClickListener(v -> {
            boolean newState = !info.isSelected();
            info.setSelected(newState);
            holder.check.setChecked(newState);
            listener.onSelectionChanged(info.getPackageName(), newState);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final TextView pkg;
        final CheckBox check;

        ViewHolder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.app_icon);
            name = itemView.findViewById(R.id.app_name);
            pkg = itemView.findViewById(R.id.app_package);
            check = itemView.findViewById(R.id.app_checkbox);
        }
    }
}
