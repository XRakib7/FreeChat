package com.softcraft.freechat;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.softcraft.freechat.crypto.CryptoManager;
import com.softcraft.freechat.crypto.KeyManager;
import com.softcraft.freechat.firebase.FirebaseManager;
import com.softcraft.freechat.model.Chat;
import com.softcraft.freechat.model.Message;
import com.softcraft.freechat.model.User;
import com.softcraft.freechat.utils.Constants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Create a new group chat.
 *
 * Key distribution flow:
 *  1. Admin generates a random 32-byte group key
 *  2. For each member, wrap the group key using their X25519 public key
 *  3. Store wrapped keys in /groupKeys/{groupId}/{memberUid}
 *  4. Each member unwraps their copy when they first open the group
 */
public class GroupCreateActivity extends AppCompatActivity {

    private EditText   etGroupName, etPhones;
    private Button     btnCreate;
    private ProgressBar progressBar;
    private String myUid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_create);

        myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("New Group");
        }

        etGroupName  = findViewById(R.id.etGroupName);
        etPhones     = findViewById(R.id.etPhones);
        btnCreate    = findViewById(R.id.btnCreate);
        progressBar  = findViewById(R.id.progressBar);

        btnCreate.setOnClickListener(v -> createGroup());
    }

    private void createGroup() {
        String groupName = etGroupName.getText().toString().trim();
        if (TextUtils.isEmpty(groupName)) {
            etGroupName.setError("Enter group name"); return;
        }

        String phonesRaw = etPhones.getText().toString().trim();
        if (TextUtils.isEmpty(phonesRaw)) {
            etPhones.setError("Enter at least one phone number"); return;
        }

        String[] phones = phonesRaw.split("[,\\n]+");
        if (phones.length == 0) {
            Toast.makeText(this, "No valid phone numbers", Toast.LENGTH_SHORT).show(); return;
        }

        setLoading(true);
        resolveMembersAndCreate(groupName, phones);
    }

    private void resolveMembersAndCreate(String groupName, String[] phones) {
        List<User> members = new ArrayList<>();
        AtomicInteger pending = new AtomicInteger(phones.length);

        for (String p : phones) {
            String phone = p.trim();
            if (phone.isEmpty()) { if (pending.decrementAndGet() == 0) doCreate(groupName, members); continue; }
            FirebaseManager.getUserByPhone(phone, new FirebaseManager.Callback<User>() {
                @Override public void onSuccess(User user) {
                    synchronized (members) { members.add(user); }
                    if (pending.decrementAndGet() == 0) doCreate(groupName, members);
                }
                @Override public void onError(String error) {
                    if (pending.decrementAndGet() == 0) doCreate(groupName, members);
                }
            });
        }
    }

    private void doCreate(String groupName, List<User> memberUsers) {
        if (memberUsers.isEmpty()) {
            runOnUiThread(() -> {
                setLoading(false);
                Toast.makeText(this, "No valid members found", Toast.LENGTH_SHORT).show();
            });
            return;
        }

        // Fetch admin (self) to include in group
        FirebaseManager.getUser(myUid, new FirebaseManager.Callback<User>() {
            @Override public void onSuccess(User me) {
                memberUsers.add(0, me);
                finalizeGroupCreation(groupName, memberUsers);
            }
            @Override public void onError(String error) {
                finalizeGroupCreation(groupName, memberUsers);
            }
        });
    }

    private void finalizeGroupCreation(String groupName, List<User> memberUsers) {
        new Thread(() -> {
            try {
                String groupId = "group_" + UUID.randomUUID().toString().replace("-", "");

                // Build participants map
                Map<String, Boolean> participants = new HashMap<>();
                for (User u : memberUsers) participants.put(u.uid, true);

                // Generate group key
                byte[] groupKeyRaw = KeyManager.generateGroupKey();

                // Wrap group key for each member
                Map<String, String> wrappedKeys = new HashMap<>();
                for (User u : memberUsers) {
                    if (u.publicKey == null) continue;
                    org.bouncycastle.crypto.params.X25519PublicKeyParameters memberPub =
                            CryptoManager.publicKeyFromBase64(u.publicKey);
                    String wrapped = CryptoManager.wrapGroupKey(groupKeyRaw, memberPub, groupId);
                    wrappedKeys.put(u.uid, wrapped);
                }

                // Create chat object
                Chat group = Chat.group(groupId, groupName, myUid, participants);

                // Encrypt and send a system message "Group created by [name]"
                KeyManager.cacheGroupKey(groupId, groupKeyRaw);
                byte[] sessionKey = KeyManager.getOrDeriveGroupSessionKey(groupId, groupKeyRaw);

                String sysText = groupName + " group created";
                // For system messages we store plaintext in cipher field
                Message sysMsg = new Message(myUid, sysText, "", Constants.MSG_TYPE_SYSTEM);

                // Save everything
                runOnUiThread(() -> {
                    FirebaseManager.saveGroupKeys(groupId, wrappedKeys, new FirebaseManager.Callback<Void>() {
                        @Override public void onSuccess(Void r) {
                            FirebaseManager.saveChat(group, new FirebaseManager.Callback<Void>() {
                                @Override public void onSuccess(Void r2) {
                                    FirebaseManager.sendMessage(groupId, sysMsg, new FirebaseManager.Callback<String>() {
                                        @Override public void onSuccess(String msgId) {
                                            setLoading(false);
                                            Intent intent = new Intent(GroupCreateActivity.this, ChatActivity.class);
                                            intent.putExtra(Constants.EXTRA_CHAT_ID,   groupId);
                                            intent.putExtra(Constants.EXTRA_CHAT_NAME, groupName);
                                            intent.putExtra(Constants.EXTRA_CHAT_TYPE, Constants.CHAT_GROUP);
                                            startActivity(intent);
                                            finish();
                                        }
                                        @Override public void onError(String e) { setLoading(false); }
                                    });
                                }
                                @Override public void onError(String e) { setLoading(false);
                                    Toast.makeText(GroupCreateActivity.this, "Error: "+e, Toast.LENGTH_SHORT).show(); }
                            });
                        }
                        @Override public void onError(String e) { setLoading(false);
                            Toast.makeText(GroupCreateActivity.this, "Error saving keys: "+e, Toast.LENGTH_SHORT).show(); }
                    });
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    setLoading(false);
                    Toast.makeText(this, "Group creation failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnCreate.setEnabled(!loading);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) { onBackPressed(); return true; }
        return super.onOptionsItemSelected(item);
    }
}