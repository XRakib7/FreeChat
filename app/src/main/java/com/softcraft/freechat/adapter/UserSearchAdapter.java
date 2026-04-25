package com.softcraft.freechat.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.softcraft.freechat.R;
import com.softcraft.freechat.model.User;

import java.util.List;

public class UserSearchAdapter extends RecyclerView.Adapter<UserSearchAdapter.VH> {

    public interface OnUserClickListener { void onClick(User user); }

    private final List<User>          users;
    private final OnUserClickListener listener;

    public UserSearchAdapter(List<User> users, OnUserClickListener listener) {
        this.users    = users;
        this.listener = listener;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_user_search, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        User user = users.get(pos);
        h.tvName.setText(user.displayName);
        h.tvPhone.setText(user.phone);
        h.tvAbout.setText(user.about != null ? user.about : "");
        h.itemView.setOnClickListener(v -> listener.onClick(user));
    }

    @Override public int getItemCount() { return users.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvName, tvPhone, tvAbout;
        VH(View v) {
            super(v);
            tvName  = v.findViewById(R.id.tvName);
            tvPhone = v.findViewById(R.id.tvPhone);
            tvAbout = v.findViewById(R.id.tvAbout);
        }
    }
}