package com.softcraft.freechat.adapter;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.softcraft.freechat.ImageViewActivity;
import com.softcraft.freechat.R;
import com.softcraft.freechat.model.Message;
import com.softcraft.freechat.utils.Constants;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Adapter for the message list in ChatActivity.
 * Three view types: text-sent, text-received, image-sent, image-received.
 * System messages shown centered.
 */
public class MessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_SENT_TEXT     = 0;
    private static final int VIEW_RECEIVED_TEXT = 1;
    private static final int VIEW_SENT_IMAGE    = 2;
    private static final int VIEW_RECEIVED_IMAGE= 3;
    private static final int VIEW_SYSTEM        = 4;

    private final List<Message> messages;
    private final String        myUid;
    private final Context       ctx;

    public MessageAdapter(List<Message> messages, String myUid, Context ctx) {
        this.messages = messages;
        this.myUid    = myUid;
        this.ctx      = ctx;
    }

    @Override
    public int getItemViewType(int position) {
        Message msg = messages.get(position);
        if (Constants.MSG_TYPE_SYSTEM.equals(msg.type)) return VIEW_SYSTEM;
        boolean mine  = myUid.equals(msg.senderId);
        boolean image = Constants.MSG_TYPE_IMAGE.equals(msg.type);
        if (mine)  return image ? VIEW_SENT_IMAGE    : VIEW_SENT_TEXT;
        else       return image ? VIEW_RECEIVED_IMAGE : VIEW_RECEIVED_TEXT;
    }

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case VIEW_SENT_TEXT:
            case VIEW_SENT_IMAGE:
                return new MsgVH(inf.inflate(R.layout.item_message_sent, parent, false));
            case VIEW_RECEIVED_TEXT:
            case VIEW_RECEIVED_IMAGE:
                return new MsgVH(inf.inflate(R.layout.item_message_received, parent, false));
            default: // system
                return new SysVH(inf.inflate(R.layout.item_message_system, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
        Message msg = messages.get(pos);

        if (holder instanceof SysVH) {
            ((SysVH) holder).tvSys.setText(msg.decryptedText);
            return;
        }

        MsgVH h = (MsgVH) holder;
        h.tvTime.setText(formatTime(msg.timestamp));

        // Status ticks (only for sent messages)
        if (myUid.equals(msg.senderId) && h.tvStatus != null) {
            h.tvStatus.setText(statusTick(msg.status));
            h.tvStatus.setTextColor(statusColor(msg.status));
        }

        if (Constants.MSG_TYPE_IMAGE.equals(msg.type)) {
            h.tvMessage.setVisibility(View.GONE);
            h.ivImage.setVisibility(View.VISIBLE);

            if (msg.decryptedImage != null) {
                Glide.with(ctx).load(msg.decryptedImage).into(h.ivImage);
                h.ivImage.setOnClickListener(v -> {
                    // Open full-screen image viewer
                    Intent intent = new Intent(ctx, ImageViewActivity.class);
                    intent.putExtra(Constants.EXTRA_IMAGE_BYTES, msg.decryptedImage);
                    ctx.startActivity(intent);
                });
            } else if (msg.decryptionFailed) {
                h.ivImage.setImageResource(R.drawable.ic_broken_image);
            }
        } else {
            h.tvMessage.setVisibility(View.VISIBLE);
            h.ivImage.setVisibility(View.GONE);
            h.tvMessage.setText(msg.decryptionFailed
                    ? "[Unable to decrypt]"
                    : msg.decryptedText);
        }
    }

    @Override public int getItemCount() { return messages.size(); }

    private String statusTick(String status) {
        if (status == null) return "";
        switch (status) {
            case Constants.STATUS_SENDING:   return "🕐";
            case Constants.STATUS_SENT:      return "✓";
            case Constants.STATUS_DELIVERED: return "✓✓";
            case Constants.STATUS_READ:      return "✓✓";
            case Constants.STATUS_FAILED:    return "✗";
            default: return "";
        }
    }

    private int statusColor(String status) {
        if (Constants.STATUS_READ.equals(status))
            return ctx.getResources().getColor(R.color.status_read, null);
        return ctx.getResources().getColor(R.color.status_delivered, null);
    }

    private String formatTime(long ts) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(ts));
    }

    // ── ViewHolders ──

    static class MsgVH extends RecyclerView.ViewHolder {
        TextView  tvMessage, tvTime, tvStatus;
        ImageView ivImage;
        MsgVH(View v) {
            super(v);
            tvMessage = v.findViewById(R.id.tvMessage);
            tvTime    = v.findViewById(R.id.tvTime);
            tvStatus  = v.findViewById(R.id.tvStatus);
            ivImage   = v.findViewById(R.id.ivImage);
        }
    }

    static class SysVH extends RecyclerView.ViewHolder {
        TextView tvSys;
        SysVH(View v) { super(v); tvSys = v.findViewById(R.id.tvSystemMessage); }
    }
}