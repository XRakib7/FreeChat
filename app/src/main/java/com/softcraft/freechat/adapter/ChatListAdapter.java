package com.softcraft.freechat.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.softcraft.freechat.R;
import com.softcraft.freechat.model.Chat;
import com.softcraft.freechat.utils.Constants;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ChatListAdapter extends RecyclerView.Adapter<ChatListAdapter.VH> {

    public interface OnChatClickListener {
        void onClick(Chat chat);
    }

    private final List<Chat>            chats;
    private final String                myUid;
    private final OnChatClickListener   listener;

    public ChatListAdapter(List<Chat> chats, String myUid, OnChatClickListener listener) {
        this.chats    = chats;
        this.myUid    = myUid;
        this.listener = listener;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_chat, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        Chat chat = chats.get(pos);

        // Display name
        if (Constants.CHAT_INDIVIDUAL.equals(chat.chatType) && chat.peerUser != null) {
            h.tvName.setText(chat.peerUser.displayName);
            // Avatar from Base64
            if (chat.peerUser.avatarB64 != null && !chat.peerUser.avatarB64.isEmpty()) {
                byte[] imgBytes = android.util.Base64.decode(chat.peerUser.avatarB64, android.util.Base64.NO_WRAP);
                Glide.with(h.ivAvatar).load(imgBytes).circleCrop().into(h.ivAvatar);
            } else {
                h.ivAvatar.setImageResource(R.drawable.ic_default_avatar);
            }
        } else {
            h.tvName.setText(chat.groupName != null ? chat.groupName : "Group");
            h.ivAvatar.setImageResource(R.drawable.ic_group);
        }

        // Last message preview (this is the [Message] / [Image] placeholder — never decrypted content)
        h.tvLastMsg.setText(chat.lastMsg != null ? chat.lastMsg : "");

        // Timestamp
        if (chat.lastMsgTime > 0) {
            h.tvTime.setText(formatTime(chat.lastMsgTime));
        }

        h.itemView.setOnClickListener(v -> listener.onClick(chat));
    }

    @Override public int getItemCount() { return chats.size(); }

    private String formatTime(long ts) {
        long now  = System.currentTimeMillis();
        long diff = now - ts;
        if (diff < 60_000) return "now";
        if (diff < 86_400_000) return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(ts));
        if (diff < 7 * 86_400_000L) return new SimpleDateFormat("EEE", Locale.getDefault()).format(new Date(ts));
        return new SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(new Date(ts));
    }

    static class VH extends RecyclerView.ViewHolder {
        ImageView ivAvatar;
        TextView  tvName, tvLastMsg, tvTime;
        VH(View v) {
            super(v);
            ivAvatar  = v.findViewById(R.id.ivAvatar);
            tvName    = v.findViewById(R.id.tvName);
            tvLastMsg = v.findViewById(R.id.tvLastMsg);
            tvTime    = v.findViewById(R.id.tvTime);
        }
    }
}