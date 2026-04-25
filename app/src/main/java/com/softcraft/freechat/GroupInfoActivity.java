package com.softcraft.freechat;

import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Base64;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.softcraft.freechat.adapter.UserSearchAdapter;
import com.softcraft.freechat.crypto.CryptoManager;
import com.softcraft.freechat.crypto.KeyManager;
import com.softcraft.freechat.firebase.FirebaseManager;
import com.softcraft.freechat.model.User;
import com.softcraft.freechat.utils.Constants;
import com.softcraft.freechat.utils.ImageUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Group Info screen — view participants, rename group,
 * add/remove members (admin only), leave group.
 */
public class GroupInfoActivity extends AppCompatActivity {

    private ImageView     ivGroupAvatar;
    private TextView      tvGroupName, tvMemberCount;
    private EditText      etNewMember;
    private Button        btnAddMember, btnLeaveGroup;
    private RecyclerView  rvMembers;
    private LinearLayout  layoutAdminControls;

    private String myUid, groupId, groupName, groupAdmin;
    private boolean isAdmin = false;
    private final List<User> memberList = new ArrayList<>();
    private UserSearchAdapter memberAdapter;

    private final ActivityResultLauncher<Intent> pickAvatarLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    try {
                        byte[] compressed = ImageUtils.bitmapToJpeg(
                                BitmapFactory.decodeStream(getContentResolver().openInputStream(uri)),
                                Constants.AVATAR_MAX_PX, Constants.AVATAR_QUALITY);
                        String b64 = Base64.encodeToString(compressed, Base64.NO_WRAP);
                        ivGroupAvatar.setImageBitmap(
                                BitmapFactory.decodeByteArray(compressed, 0, compressed.length));
                        // Save avatar to group chat node
                        FirebaseDatabase.getInstance().getReference()
                                .child(Constants.DB_CHATS).child(groupId)
                                .child("groupAvatarB64").setValue(b64);
                    } catch (Exception e) {
                        Toast.makeText(this, "Failed to update avatar", Toast.LENGTH_SHORT).show();
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_info);

        myUid     = FirebaseAuth.getInstance().getCurrentUser().getUid();
        groupId   = getIntent().getStringExtra(Constants.EXTRA_CHAT_ID);
        groupName = getIntent().getStringExtra(Constants.EXTRA_CHAT_NAME);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Group Info");
        }

        ivGroupAvatar   = findViewById(R.id.ivGroupAvatar);
        tvGroupName     = findViewById(R.id.tvGroupName);
        tvMemberCount   = findViewById(R.id.tvMemberCount);
        etNewMember     = findViewById(R.id.etNewMember);
        btnAddMember    = findViewById(R.id.btnAddMember);
        btnLeaveGroup   = findViewById(R.id.btnLeaveGroup);
        rvMembers       = findViewById(R.id.rvMembers);
        layoutAdminControls = findViewById(R.id.layoutAdminControls);

        tvGroupName.setText(groupName);

        memberAdapter = new UserSearchAdapter(memberList, user -> showMemberOptions(user));
        rvMembers.setLayoutManager(new LinearLayoutManager(this));
        rvMembers.setAdapter(memberAdapter);

        ivGroupAvatar.setOnClickListener(v -> {
            if (isAdmin) {
                pickAvatarLauncher.launch(new Intent(Intent.ACTION_PICK,
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI));
            }
        });

        btnAddMember.setOnClickListener(v -> addMember());
        btnLeaveGroup.setOnClickListener(v -> confirmLeave());

        loadGroupInfo();
    }

    private void loadGroupInfo() {
        FirebaseManager.getChat(groupId, new FirebaseManager.Callback<com.softcraft.freechat.model.Chat>() {
            @Override public void onSuccess(com.softcraft.freechat.model.Chat chat) {
                groupAdmin = chat.groupAdmin;
                isAdmin    = myUid.equals(groupAdmin);

                runOnUiThread(() -> {
                    layoutAdminControls.setVisibility(isAdmin ? View.VISIBLE : View.GONE);

                    // Load group avatar
                    if (chat.groupAvatarB64 != null && !chat.groupAvatarB64.isEmpty()) {
                        byte[] b = Base64.decode(chat.groupAvatarB64, Base64.NO_WRAP);
                        ivGroupAvatar.setImageBitmap(BitmapFactory.decodeByteArray(b, 0, b.length));
                    }

                    // Rename group (admin only)
                    tvGroupName.setOnClickListener(v -> {
                        if (!isAdmin) return;
                        EditText et = new EditText(GroupInfoActivity.this);
                        et.setText(groupName);
                        new AlertDialog.Builder(GroupInfoActivity.this)
                                .setTitle("Rename Group")
                                .setView(et)
                                .setPositiveButton("Save", (d, w) -> {
                                    String newName = et.getText().toString().trim();
                                    if (!TextUtils.isEmpty(newName)) {
                                        groupName = newName;
                                        tvGroupName.setText(newName);
                                        FirebaseDatabase.getInstance().getReference()
                                                .child(Constants.DB_CHATS).child(groupId)
                                                .child("groupName").setValue(newName);
                                    }
                                })
                                .setNegativeButton("Cancel", null)
                                .show();
                    });

                    // Load members
                    loadMembers(new ArrayList<>(chat.participants.keySet()));
                });
            }
            @Override public void onError(String error) {
                Toast.makeText(GroupInfoActivity.this, "Error: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadMembers(List<String> uids) {
        memberList.clear();
        AtomicInteger pending = new AtomicInteger(uids.size());
        for (String uid : uids) {
            FirebaseManager.getUser(uid, new FirebaseManager.Callback<User>() {
                @Override public void onSuccess(User user) {
                    synchronized (memberList) { memberList.add(user); }
                    if (pending.decrementAndGet() == 0) {
                        runOnUiThread(() -> {
                            tvMemberCount.setText(memberList.size() + " participants");
                            memberAdapter.notifyDataSetChanged();
                        });
                    }
                }
                @Override public void onError(String e) {
                    pending.decrementAndGet();
                }
            });
        }
    }

    private void showMemberOptions(User user) {
        if (user.uid.equals(myUid) || !isAdmin) return;
        new AlertDialog.Builder(this)
                .setTitle(user.displayName)
                .setItems(new String[]{"Remove from group"}, (d, which) -> {
                    if (which == 0) removeMember(user);
                })
                .show();
    }

    private void removeMember(User user) {
        // Remove from participants
        DatabaseReference chatRef = FirebaseDatabase.getInstance().getReference()
                .child(Constants.DB_CHATS).child(groupId);
        chatRef.child("participants").child(user.uid).removeValue();

        // Remove from userChats index
        FirebaseDatabase.getInstance().getReference()
                .child(Constants.DB_USER_CHATS).child(user.uid).child(groupId).removeValue();

        // Remove their group key
        FirebaseDatabase.getInstance().getReference()
                .child(Constants.DB_GROUP_KEYS).child(groupId).child(user.uid).removeValue();

        // Send system message
        sendSystemMessage(user.displayName + " was removed from the group");

        memberList.removeIf(u -> u.uid.equals(user.uid));
        memberAdapter.notifyDataSetChanged();
        tvMemberCount.setText(memberList.size() + " participants");
    }

    private void addMember() {
        String phone = etNewMember.getText().toString().trim();
        if (TextUtils.isEmpty(phone)) { etNewMember.setError("Enter phone number"); return; }

        FirebaseManager.getUserByPhone(phone, new FirebaseManager.Callback<User>() {
            @Override public void onSuccess(User user) {
                // Check not already a member
                for (User m : memberList) {
                    if (m.uid.equals(user.uid)) {
                        Toast.makeText(GroupInfoActivity.this,
                                "Already a member", Toast.LENGTH_SHORT).show();
                        return;
                    }
                }

                // Wrap group key for new member
                new Thread(() -> {
                    try {
                        byte[] rawGroupKey = KeyManager.getGroupKey(groupId);
                        if (rawGroupKey == null) {
                            runOnUiThread(() -> Toast.makeText(GroupInfoActivity.this,
                                    "Group key not loaded. Open the chat first.", Toast.LENGTH_LONG).show());
                            return;
                        }
                        org.bouncycastle.crypto.params.X25519PublicKeyParameters memberPub =
                                CryptoManager.publicKeyFromBase64(user.publicKey);
                        String wrapped = CryptoManager.wrapGroupKey(rawGroupKey, memberPub, groupId);

                        Map<String, String> wrappedKeys = new HashMap<>();
                        wrappedKeys.put(user.uid, wrapped);

                        runOnUiThread(() ->
                                FirebaseManager.saveGroupKeys(groupId, wrappedKeys,
                                        new FirebaseManager.Callback<Void>() {
                                            @Override public void onSuccess(Void r) {
                                                // Add to participants
                                                FirebaseDatabase.getInstance().getReference()
                                                        .child(Constants.DB_CHATS).child(groupId)
                                                        .child("participants").child(user.uid).setValue(true);
                                                // Add to userChats index
                                                FirebaseDatabase.getInstance().getReference()
                                                        .child(Constants.DB_USER_CHATS)
                                                        .child(user.uid).child(groupId).setValue(true);
                                                memberList.add(user);
                                                memberAdapter.notifyDataSetChanged();
                                                tvMemberCount.setText(memberList.size() + " participants");
                                                etNewMember.setText("");
                                                sendSystemMessage(user.displayName + " was added to the group");
                                            }
                                            @Override public void onError(String e) {
                                                Toast.makeText(GroupInfoActivity.this,
                                                        "Error: " + e, Toast.LENGTH_SHORT).show();
                                            }
                                        }));
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(GroupInfoActivity.this,
                                "Failed to add member: " + e.getMessage(), Toast.LENGTH_LONG).show());
                    }
                }).start();
            }
            @Override public void onError(String error) {
                Toast.makeText(GroupInfoActivity.this, "User not found", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void confirmLeave() {
        new AlertDialog.Builder(this)
                .setTitle("Leave group")
                .setMessage("Are you sure you want to leave " + groupName + "?")
                .setPositiveButton("Leave", (d, w) -> leaveGroup())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void leaveGroup() {
        // Remove self from participants
        FirebaseDatabase.getInstance().getReference()
                .child(Constants.DB_CHATS).child(groupId)
                .child("participants").child(myUid).removeValue();
        // Remove from userChats
        FirebaseDatabase.getInstance().getReference()
                .child(Constants.DB_USER_CHATS).child(myUid).child(groupId).removeValue();
        // Remove own group key
        FirebaseDatabase.getInstance().getReference()
                .child(Constants.DB_GROUP_KEYS).child(groupId).child(myUid).removeValue();

        sendSystemMessage(SecurePrefs_getDisplayName() + " left the group");

        // If admin is leaving, assign a new admin (first remaining member)
        if (isAdmin && !memberList.isEmpty()) {
            for (User m : memberList) {
                if (!m.uid.equals(myUid)) {
                    FirebaseDatabase.getInstance().getReference()
                            .child(Constants.DB_CHATS).child(groupId)
                            .child("groupAdmin").setValue(m.uid);
                    break;
                }
            }
        }

        KeyManager.evictSessionKey(groupId);
        // Go back to MainActivity
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    private void sendSystemMessage(String text) {
        com.softcraft.freechat.model.Message msg = new com.softcraft.freechat.model.Message(
                myUid, text, "", Constants.MSG_TYPE_SYSTEM);
        FirebaseManager.sendMessage(groupId, msg, new FirebaseManager.Callback<String>() {
            @Override public void onSuccess(String id) {}
            @Override public void onError(String e) {}
        });
    }

    private String SecurePrefs_getDisplayName() {
        return com.softcraft.freechat.utils.SecurePrefs.get(Constants.PREF_DISPLAY_NAME, "A member");
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) { onBackPressed(); return true; }
        return super.onOptionsItemSelected(item);
    }
}