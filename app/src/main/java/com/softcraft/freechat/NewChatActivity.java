package com.softcraft.freechat;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import com.softcraft.freechat.firebase.FirebaseManager;
import com.softcraft.freechat.model.Chat;
import com.softcraft.freechat.model.User;
import com.softcraft.freechat.utils.Constants;

import java.util.ArrayList;
import java.util.List;

/**
 * Search users by phone number to start a new 1-to-1 chat.
 */
public class NewChatActivity extends AppCompatActivity {

    private EditText  etSearch;
    private ProgressBar progressBar;
    private RecyclerView recyclerView;
    private String myUid;

    // Simple single-result adapter
    private final List<User> results = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_new_chat);

        myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("New Chat");
        }

        etSearch     = findViewById(R.id.etSearch);
        progressBar  = findViewById(R.id.progressBar);
        recyclerView = findViewById(R.id.recyclerViewResults);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        // Simple text adapter — shows user name + phone, tap to open chat
        com.softcraft.freechat.adapter.UserSearchAdapter adapter =
                new com.softcraft.freechat.adapter.UserSearchAdapter(results, user -> openChatWith(user));
        recyclerView.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                String phone = s.toString().trim();
                if (phone.length() >= 8) searchByPhone(phone, adapter);
            }
        });
    }

    private void searchByPhone(String phone, com.softcraft.freechat.adapter.UserSearchAdapter adapter) {
        progressBar.setVisibility(View.VISIBLE);
        FirebaseManager.getUserByPhone(phone, new FirebaseManager.Callback<User>() {
            @Override public void onSuccess(User user) {
                progressBar.setVisibility(View.GONE);
                results.clear();
                if (!user.uid.equals(myUid)) results.add(user);
                adapter.notifyDataSetChanged();
            }
            @Override public void onError(String error) {
                progressBar.setVisibility(View.GONE);
                results.clear();
                adapter.notifyDataSetChanged();
            }
        });
    }

    private void openChatWith(User peer) {
        String chatId = Chat.individualChatId(myUid, peer.uid);

        // Create chat node if it doesn't exist
        Chat chat = Chat.individual(chatId, myUid, peer.uid, "");
        FirebaseManager.saveChat(chat, new FirebaseManager.Callback<Void>() {
            @Override public void onSuccess(Void result) {
                Intent intent = new Intent(NewChatActivity.this, ChatActivity.class);
                intent.putExtra(Constants.EXTRA_CHAT_ID,   chatId);
                intent.putExtra(Constants.EXTRA_PEER_UID,  peer.uid);
                intent.putExtra(Constants.EXTRA_CHAT_NAME, peer.displayName);
                intent.putExtra(Constants.EXTRA_CHAT_TYPE, Constants.CHAT_INDIVIDUAL);
                startActivity(intent);
                finish();
            }
            @Override public void onError(String error) {
                Toast.makeText(NewChatActivity.this, "Error: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) { onBackPressed(); return true; }
        return super.onOptionsItemSelected(item);
    }
}