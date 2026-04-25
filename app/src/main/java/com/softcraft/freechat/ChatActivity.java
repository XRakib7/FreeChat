package com.softcraft.freechat;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;
import com.softcraft.freechat.adapter.MessageAdapter;
import com.softcraft.freechat.crypto.CryptoManager;
import com.softcraft.freechat.crypto.KeyManager;
import com.softcraft.freechat.firebase.FirebaseManager;
import com.softcraft.freechat.model.Chat;
import com.softcraft.freechat.model.Message;
import com.softcraft.freechat.model.User;
import com.softcraft.freechat.utils.Constants;
import com.softcraft.freechat.utils.ImageUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-featured chat screen.
 *
 * Handles:
 *  - Text message send/receive with E2E encryption
 *  - Image send/receive (compressed, encrypted, Base64 in RTDB)
 *  - Message status ticks (sent ✓, delivered ✓✓, read ✓✓ blue)
 *  - Online/last-seen indicator in toolbar
 *  - Typing indicator
 *  - Real-time message loading via ChildEventListener (efficient, no re-read)
 *  - Group and individual chat support
 */
public class ChatActivity extends AppCompatActivity {

    private static final String TAG = "ChatActivity";

    // UI
    private RecyclerView   recyclerView;
    private MessageAdapter adapter;
    private EditText       etMessage;
    private ImageButton    btnSend, btnAttach;
    private ProgressBar    progressBar;
    private TextView       tvStatus, tvTyping;
    private Toolbar        toolbar;

    // State
    private String myUid, chatId, peerUid, chatType, chatName;
    private byte[] sessionKey; // AES-256-GCM session key for this chat
    private final List<Message> messages = new ArrayList<>();
    private final List<String>  unreadMessageIds = new ArrayList<>();

    // Firebase listeners (kept to remove on destroy)
    private ChildEventListener messageListener;
    private ValueEventListener presenceListener, typingListener;

    // Typing debounce
    private final android.os.Handler typingHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean amTyping = false;

    // Image picker
    private final ActivityResultLauncher<Intent> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    sendImage(uri);
                }
            });

    // ─────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        // Extract intent extras
        chatId   = getIntent().getStringExtra(Constants.EXTRA_CHAT_ID);
        peerUid  = getIntent().getStringExtra(Constants.EXTRA_PEER_UID);
        chatName = getIntent().getStringExtra(Constants.EXTRA_CHAT_NAME);
        chatType = getIntent().getStringExtra(Constants.EXTRA_CHAT_TYPE);
        if (chatType == null) chatType = Constants.CHAT_INDIVIDUAL;

        myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        // Toolbar
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(chatName != null ? chatName : "Chat");
        }

        // UI refs
        recyclerView = findViewById(R.id.recyclerViewMessages);
        etMessage    = findViewById(R.id.etMessage);
        btnSend      = findViewById(R.id.btnSend);
        btnAttach    = findViewById(R.id.btnAttach);
        progressBar  = findViewById(R.id.progressBar);
        tvStatus     = findViewById(R.id.tvStatus); // subtitle: "online" / "last seen..."
        tvTyping     = findViewById(R.id.tvTyping);

        adapter = new MessageAdapter(messages, myUid, this);
        LinearLayoutManager llm = new LinearLayoutManager(this);
        llm.setStackFromEnd(true);
        recyclerView.setLayoutManager(llm);
        recyclerView.setAdapter(adapter);

        // Listeners
        btnSend.setOnClickListener(v -> sendTextMessage());
        btnAttach.setOnClickListener(v -> pickImage());
        setupTypingWatcher();

        // Load session key then messages
        resolveSessionKey();
    }

    // ─────────────────────────────────────────────
    //  Session Key Resolution
    // ─────────────────────────────────────────────

    private void resolveSessionKey() {
        progressBar.setVisibility(View.VISIBLE);

        if (Constants.CHAT_INDIVIDUAL.equals(chatType)) {
            resolveIndividualKey();
        } else {
            resolveGroupKey();
        }
    }

    private void resolveIndividualKey() {
        // Get peer's public key from Firebase, then derive shared secret
        FirebaseManager.getUser(peerUid, new FirebaseManager.Callback<User>() {
            @Override public void onSuccess(User peer) {
                try {
                    sessionKey = KeyManager.getOrDeriveSessionKey(chatId, peer.publicKey);
                    onKeyReady();
                } catch (Exception e) {
                    Log.e(TAG, "Key derivation failed: " + e.getMessage());
                    showError("Encryption setup failed");
                }
            }
            @Override public void onError(String error) {
                showError("Could not fetch peer info: " + error);
            }
        });
    }

    private void resolveGroupKey() {
        // Check memory cache first
        byte[] cached = KeyManager.getGroupKey(chatId);
        if (cached != null) {
            try {
                sessionKey = KeyManager.getOrDeriveGroupSessionKey(chatId, cached);
                onKeyReady();
            } catch (Exception e) {
                showError("Group key derivation failed");
            }
            return;
        }

        // Fetch wrapped group key from Firebase
        FirebaseManager.getMyGroupKey(chatId, myUid, new FirebaseManager.Callback<String>() {
            @Override public void onSuccess(String wrappedKey) {
                try {
                    byte[] raw = CryptoManager.unwrapGroupKey(
                            wrappedKey, KeyManager.getMyPrivateKey(), chatId);
                    KeyManager.cacheGroupKey(chatId, raw);
                    sessionKey = KeyManager.getOrDeriveGroupSessionKey(chatId, raw);
                    onKeyReady();
                } catch (Exception e) {
                    Log.e(TAG, "Unwrap group key failed: " + e.getMessage());
                    showError("Failed to decrypt group key");
                }
            }
            @Override public void onError(String error) {
                showError("Group key not found: " + error);
            }
        });
    }

    private void onKeyReady() {
        progressBar.setVisibility(View.GONE);
        startListeningMessages();
        if (Constants.CHAT_INDIVIDUAL.equals(chatType)) {
            startListeningPresence();
        }
        startListeningTyping();
    }

    // ─────────────────────────────────────────────
    //  Message Loading (ChildEventListener — incremental)
    // ─────────────────────────────────────────────

    private void startListeningMessages() {
        DatabaseReference msgRef = FirebaseManager.getMessagesRef(chatId)
                .orderByChild(Constants.FIELD_TIMESTAMP)
                .limitToLast(100)
                .getRef(); // NOTE: for orderByChild we need query

        // Use query for ordering
        com.google.firebase.database.Query query = FirebaseManager.getMessagesRef(chatId)
                .orderByChild(Constants.FIELD_TIMESTAMP)
                .limitToLast(100);

        messageListener = new ChildEventListener() {
            @Override public void onChildAdded(@NonNull DataSnapshot snapshot, String prev) {
                Message msg = snapshot.getValue(Message.class);
                if (msg == null) return;
                msg.messageId = snapshot.getKey();
                decryptAndAdd(msg);

                // Mark received messages as delivered
                if (!myUid.equals(msg.senderId) &&
                        !Constants.STATUS_READ.equals(msg.status) &&
                        !Constants.STATUS_DELIVERED.equals(msg.status)) {
                    FirebaseManager.updateMessageStatus(chatId, msg.messageId, Constants.STATUS_DELIVERED);
                    unreadMessageIds.add(msg.messageId);
                }
            }
            @Override public void onChildChanged(@NonNull DataSnapshot snapshot, String prev) {
                Message msg = snapshot.getValue(Message.class);
                if (msg == null) return;
                msg.messageId = snapshot.getKey();
                // Update status in existing message (for our own sent messages)
                for (int i = 0; i < messages.size(); i++) {
                    if (messages.get(i).messageId != null &&
                            messages.get(i).messageId.equals(msg.messageId)) {
                        messages.get(i).status = msg.status;
                        runOnUiThread(() -> adapter.notifyItemChanged(
                                messages.indexOf(messages.stream()
                                        .filter(m -> msg.messageId.equals(m.messageId))
                                        .findFirst().orElse(null))));
                        break;
                    }
                }
            }
            @Override public void onChildRemoved(@NonNull DataSnapshot snapshot) {
                String msgId = snapshot.getKey();
                for (int i = 0; i < messages.size(); i++) {
                    if (msgId != null && msgId.equals(messages.get(i).messageId)) {
                        messages.remove(i);
                        final int pos = i;
                        runOnUiThread(() -> adapter.notifyItemRemoved(pos));
                        break;
                    }
                }
            }
            @Override public void onChildMoved(@NonNull DataSnapshot snapshot, String prev) {}
            @Override public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Message listener cancelled: " + error.getMessage());
            }
        };
        query.addChildEventListener(messageListener);
    }

    private void decryptAndAdd(Message msg) {
        if (sessionKey == null) return;
        try {
            if (Constants.MSG_TYPE_TEXT.equals(msg.type)) {
                msg.decryptedText = CryptoManager.decryptToString(msg.cipher, msg.iv, sessionKey);
            } else if (Constants.MSG_TYPE_IMAGE.equals(msg.type)) {
                byte[] imgBytes = CryptoManager.decrypt(msg.cipher, msg.iv, sessionKey);
                msg.decryptedImage = imgBytes;
            } else if (Constants.MSG_TYPE_SYSTEM.equals(msg.type)) {
                // System messages stored in plain text in cipher field (no real encryption needed)
                msg.decryptedText = msg.cipher;
            }
        } catch (Exception e) {
            Log.e(TAG, "Decryption failed for message " + msg.messageId + ": " + e.getMessage());
            msg.decryptionFailed = true;
            msg.decryptedText    = "[Unable to decrypt message]";
        }
        runOnUiThread(() -> {
            messages.add(msg);
            adapter.notifyItemInserted(messages.size() - 1);
            recyclerView.scrollToPosition(messages.size() - 1);
        });
    }

    // ─────────────────────────────────────────────
    //  Send Text Message
    // ─────────────────────────────────────────────

    private void sendTextMessage() {
        String text = etMessage.getText().toString().trim();
        if (text.isEmpty() || sessionKey == null) return;

        etMessage.setText("");
        stopTyping();

        new Thread(() -> {
            try {
                CryptoManager.EncryptedPayload enc = CryptoManager.encryptString(text, sessionKey);
                Message msg = new Message(myUid, enc.ciphertext, enc.iv, Constants.MSG_TYPE_TEXT);

                runOnUiThread(() ->
                        FirebaseManager.sendMessage(chatId, msg, new FirebaseManager.Callback<String>() {
                            @Override public void onSuccess(String messageId) {
                                FirebaseManager.updateChatLastMessage(chatId, "[Message]");
                            }
                            @Override public void onError(String error) {
                                Toast.makeText(ChatActivity.this,
                                        "Send failed: " + error, Toast.LENGTH_SHORT).show();
                            }
                        })
                );
            } catch (Exception e) {
                Log.e(TAG, "Encrypt failed: " + e.getMessage());
                runOnUiThread(() -> Toast.makeText(this,
                        "Encryption error", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ─────────────────────────────────────────────
    //  Send Image Message
    // ─────────────────────────────────────────────

    private void pickImage() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.READ_EXTERNAL_STORAGE}, 100);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        pickImageLauncher.launch(intent);
    }

    private void sendImage(Uri uri) {
        if (sessionKey == null) return;
        progressBar.setVisibility(View.VISIBLE);

        new Thread(() -> {
            try {
                // 1. Compress image
                byte[] jpegBytes = ImageUtils.uriToCompressedJpeg(this, uri);

                // 2. Encrypt compressed bytes
                CryptoManager.EncryptedPayload enc = CryptoManager.encrypt(jpegBytes, sessionKey);

                // 3. Send to Firebase (the encrypted bytes are already Base64 in EncryptedPayload)
                Message msg = new Message(myUid, enc.ciphertext, enc.iv, Constants.MSG_TYPE_IMAGE);

                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    FirebaseManager.sendMessage(chatId, msg, new FirebaseManager.Callback<String>() {
                        @Override public void onSuccess(String messageId) {
                            FirebaseManager.updateChatLastMessage(chatId, "[Image]");
                        }
                        @Override public void onError(String error) {
                            Toast.makeText(ChatActivity.this,
                                    "Image send failed: " + error, Toast.LENGTH_SHORT).show();
                        }
                    });
                });
            } catch (Exception e) {
                Log.e(TAG, "Image send failed: " + e.getMessage());
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(this, "Failed to send image", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    // ─────────────────────────────────────────────
    //  Presence (Online / Last Seen)
    // ─────────────────────────────────────────────

    private void startListeningPresence() {
        presenceListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean online   = snapshot.child("online").getValue(Boolean.class);
                Long    lastSeen = snapshot.child("lastSeen").getValue(Long.class);
                runOnUiThread(() -> {
                    if (Boolean.TRUE.equals(online)) {
                        tvStatus.setText("online");
                        tvStatus.setTextColor(getResources().getColor(R.color.online_green, null));
                    } else if (lastSeen != null) {
                        tvStatus.setText("last seen " + formatLastSeen(lastSeen));
                        tvStatus.setTextColor(getResources().getColor(android.R.color.darker_gray, null));
                    }
                });
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        };
        FirebaseManager.listenPresence(peerUid, presenceListener);
    }

    private String formatLastSeen(long ts) {
        long diff = System.currentTimeMillis() - ts;
        long mins = diff / 60000;
        if (mins < 1)  return "just now";
        if (mins < 60) return mins + " min ago";
        long hours = mins / 60;
        if (hours < 24) return hours + "h ago";
        return new java.text.SimpleDateFormat("dd MMM", java.util.Locale.getDefault())
                .format(new java.util.Date(ts));
    }

    // ─────────────────────────────────────────────
    //  Typing Indicator
    // ─────────────────────────────────────────────

    private void setupTypingWatcher() {
        etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                if (s.length() > 0 && !amTyping) {
                    amTyping = true;
                    FirebaseManager.setTyping(chatId, myUid, true);
                }
                typingHandler.removeCallbacksAndMessages(null);
                typingHandler.postDelayed(() -> stopTyping(), 2000);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void stopTyping() {
        if (amTyping) {
            amTyping = false;
            FirebaseManager.setTyping(chatId, myUid, false);
        }
    }

    private void startListeningTyping() {
        typingListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                boolean anyoneTyping = false;
                for (DataSnapshot child : snapshot.getChildren()) {
                    if (!child.getKey().equals(myUid)) {
                        Boolean typing = child.getValue(Boolean.class);
                        if (Boolean.TRUE.equals(typing)) { anyoneTyping = true; break; }
                    }
                }
                final boolean show = anyoneTyping;
                runOnUiThread(() ->
                        tvTyping.setVisibility(show ? View.VISIBLE : View.GONE));
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        };
        FirebaseManager.listenTyping(chatId, typingListener);
    }

    // ─────────────────────────────────────────────
    //  Mark as Read
    // ─────────────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        FirebaseManager.setOnline(myUid, true);
        if (!unreadMessageIds.isEmpty()) {
            FirebaseManager.markMessagesRead(chatId, myUid, new ArrayList<>(unreadMessageIds));
            unreadMessageIds.clear();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopTyping();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        KeyManager.evictSessionKey(chatId);
        if (messageListener != null) {
            FirebaseManager.getMessagesRef(chatId)
                    .orderByChild(Constants.FIELD_TIMESTAMP)
                    .limitToLast(100)
                    .getRef()
                    .removeEventListener(messageListener);
        }
        if (presenceListener != null && peerUid != null) {
            FirebaseManager.removePresenceListener(peerUid, presenceListener);
        }
        if (typingListener != null) {
            FirebaseManager.removeTypingListener(chatId, typingListener);
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) { onBackPressed(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void showError(String msg) {
        runOnUiThread(() -> {
            progressBar.setVisibility(View.GONE);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        });
    }
}