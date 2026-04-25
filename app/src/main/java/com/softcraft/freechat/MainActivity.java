package com.softcraft.freechat;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import com.softcraft.freechat.adapter.ChatListAdapter;
import com.softcraft.freechat.firebase.FirebaseManager;
import com.softcraft.freechat.model.Chat;
import com.softcraft.freechat.model.User;
import com.softcraft.freechat.utils.Constants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main screen — shows list of all conversations.
 * Similar to WhatsApp's "Chats" tab.
 *
 * Features:
 *  - Real-time updates via Firebase listeners
 *  - Sorted by last message time (newest first)
 *  - FAB to start new chat
 *  - Overflow menu: New Group, Profile, Logout
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private RecyclerView    recyclerView;
    private ChatListAdapter adapter;
    private ProgressBar     progressBar;
    private FloatingActionButton fabNewChat;

    private final List<Chat> chatList = new ArrayList<>();
    private String myUid;
    private ValueEventListener userChatsListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Free Chat");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 300);
            }
        }

        myUid       = FirebaseAuth.getInstance().getCurrentUser().getUid();
        recyclerView = findViewById(R.id.recyclerViewChats);
        progressBar  = findViewById(R.id.progressBar);
        fabNewChat   = findViewById(R.id.fabNewChat);

        adapter = new ChatListAdapter(chatList, myUid, chat -> openChat(chat));
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        fabNewChat.setOnClickListener(v ->
                startActivity(new Intent(this, NewChatActivity.class)));

        loadChats();
        FirebaseManager.setOnline(myUid, true);
    }

    private void loadChats() {
        progressBar.setVisibility(View.VISIBLE);
        userChatsListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    progressBar.setVisibility(View.GONE);
                    return;
                }
                List<String> chatIds = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    chatIds.add(child.getKey());
                }
                loadChatDetails(chatIds);
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {
                progressBar.setVisibility(View.GONE);
            }
        };
        FirebaseManager.listenUserChats(myUid, userChatsListener);
    }

    private void loadChatDetails(List<String> chatIds) {
        if (chatIds.isEmpty()) { progressBar.setVisibility(View.GONE); return; }
        chatList.clear();
        AtomicInteger pending = new AtomicInteger(chatIds.size());

        for (String chatId : chatIds) {
            FirebaseManager.getChat(chatId, new FirebaseManager.Callback<Chat>() {
                @Override public void onSuccess(Chat chat) {
                    chat.chatId = chatId;

                    if (Constants.CHAT_INDIVIDUAL.equals(chat.chatType)) {
                        // Find the peer's UID
                        String peerUid = null;
                        for (String uid : chat.participants.keySet()) {
                            if (!uid.equals(myUid)) { peerUid = uid; break; }
                        }
                        if (peerUid == null) { checkDone(pending); return; }
                        final String finalPeerUid = peerUid;
                        FirebaseManager.getUser(finalPeerUid, new FirebaseManager.Callback<User>() {
                            @Override public void onSuccess(User peer) {
                                chat.peerUser = peer;
                                addOrUpdateChat(chat);
                                checkDone(pending);
                            }
                            @Override public void onError(String error) { checkDone(pending); }
                        });
                    } else {
                        addOrUpdateChat(chat);
                        checkDone(pending);
                    }
                }
                @Override public void onError(String error) { checkDone(pending); }
            });
        }
    }

    private synchronized void addOrUpdateChat(Chat chat) {
        for (int i = 0; i < chatList.size(); i++) {
            if (chatList.get(i).chatId.equals(chat.chatId)) {
                chatList.set(i, chat);
                return;
            }
        }
        chatList.add(chat);
    }

    private void checkDone(AtomicInteger pending) {
        if (pending.decrementAndGet() == 0) {
            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                Collections.sort(chatList,
                        (a, b) -> Long.compare(b.lastMsgTime, a.lastMsgTime));
                adapter.notifyDataSetChanged();
            });
        }
    }

    private void openChat(Chat chat) {
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(Constants.EXTRA_CHAT_ID,   chat.chatId);
        intent.putExtra(Constants.EXTRA_CHAT_TYPE,  chat.chatType);
        if (chat.peerUser != null) {
            intent.putExtra(Constants.EXTRA_PEER_UID,  chat.peerUser.uid);
            intent.putExtra(Constants.EXTRA_CHAT_NAME, chat.peerUser.displayName);
        } else {
            intent.putExtra(Constants.EXTRA_CHAT_NAME, chat.groupName);
        }
        startActivity(intent);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_new_group) {
            startActivity(new Intent(this, GroupCreateActivity.class));
            return true;
        }
        if (item.getItemId() == R.id.action_profile) {
            startActivity(new Intent(this, ProfileActivity.class));
            return true;
        }
        if (item.getItemId() == R.id.action_logout) {
            new AlertDialog.Builder(this)
                    .setTitle("Log out")
                    .setMessage("Are you sure you want to log out?")
                    .setPositiveButton("Log out", (d, w) -> logout())
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void logout() {
        FirebaseManager.setOnline(myUid, false);
        FirebaseAuth.getInstance().signOut();
        com.softcraft.freechat.utils.SecurePrefs.putBoolean(Constants.PREF_PROFILE_DONE, false);
        com.softcraft.freechat.crypto.KeyManager.clearAllKeys();
        startActivity(new Intent(this, AuthActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (userChatsListener != null) {
            FirebaseManager.removeUserChatsListener(myUid, userChatsListener);
        }
        FirebaseManager.setOnline(myUid, false);
    }
}