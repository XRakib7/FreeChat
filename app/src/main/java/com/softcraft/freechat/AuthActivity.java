package com.softcraft.freechat;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.FirebaseException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.PhoneAuthCredential;
import com.google.firebase.auth.PhoneAuthOptions;
import com.google.firebase.auth.PhoneAuthProvider;
import com.hbb20.CountryCodePicker;
import com.softcraft.freechat.utils.Constants;
import com.softcraft.freechat.utils.SecurePrefs;

import java.util.concurrent.TimeUnit;

/**
 * Phone number authentication using Firebase Phone Auth.
 *
 * Flow:
 *  1. User enters phone number with country code picker
 *  2. Firebase sends SMS OTP
 *  3. User enters OTP → Firebase verifies → user is authenticated
 *  4. Go to ProfileSetupActivity if new user, MainActivity if returning
 */
public class AuthActivity extends AppCompatActivity {

    private CountryCodePicker ccp;
    private EditText etPhone, etOtp;
    private Button btnSendOtp, btnVerify;
    private LinearLayout layoutPhone, layoutOtp;
    private ProgressBar progressBar;
    private TextView tvResend;

    private FirebaseAuth mAuth;
    private String verificationId;
    private PhoneAuthProvider.ForceResendingToken resendToken;
    private String fullPhoneNumber;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth);

        mAuth = FirebaseAuth.getInstance();

        ccp         = findViewById(R.id.ccp);
        etPhone     = findViewById(R.id.etPhone);
        etOtp       = findViewById(R.id.etOtp);
        btnSendOtp  = findViewById(R.id.btnSendOtp);
        btnVerify   = findViewById(R.id.btnVerify);
        layoutPhone = findViewById(R.id.layoutPhone);
        layoutOtp   = findViewById(R.id.layoutOtp);
        progressBar = findViewById(R.id.progressBar);
        tvResend    = findViewById(R.id.tvResend);

        ccp.registerCarrierNumberEditText(etPhone);

        btnSendOtp.setOnClickListener(v -> sendOtp());
        btnVerify.setOnClickListener(v -> verifyOtp());
        tvResend.setOnClickListener(v -> resendOtp());
    }

    private void sendOtp() {
        String number = etPhone.getText().toString().trim();
        if (TextUtils.isEmpty(number)) {
            etPhone.setError("Enter phone number");
            return;
        }
        fullPhoneNumber = ccp.getFullNumberWithPlus();
        setLoading(true);

        PhoneAuthOptions options = PhoneAuthOptions.newBuilder(mAuth)
                .setPhoneNumber(fullPhoneNumber)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(this)
                .setCallbacks(new PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                    @Override
                    public void onVerificationCompleted(@androidx.annotation.NonNull PhoneAuthCredential credential) {
                        // Auto-verification on same device (rare but handle it)
                        signInWithCredential(credential);
                    }
                    @Override
                    public void onVerificationFailed(@androidx.annotation.NonNull FirebaseException e) {
                        setLoading(false);
                        Toast.makeText(AuthActivity.this,
                                "Verification failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                    @Override
                    public void onCodeSent(@androidx.annotation.NonNull String vId,
                                           @androidx.annotation.NonNull PhoneAuthProvider.ForceResendingToken token) {
                        verificationId = vId;
                        resendToken    = token;
                        setLoading(false);
                        // Switch to OTP entry screen
                        layoutPhone.setVisibility(View.GONE);
                        layoutOtp.setVisibility(View.VISIBLE);
                        Toast.makeText(AuthActivity.this,
                                "OTP sent to " + fullPhoneNumber, Toast.LENGTH_SHORT).show();
                    }
                })
                .build();

        PhoneAuthProvider.verifyPhoneNumber(options);
    }

    private void verifyOtp() {
        String otp = etOtp.getText().toString().trim();
        if (otp.length() < 6) {
            etOtp.setError("Enter 6-digit OTP");
            return;
        }
        setLoading(true);
        PhoneAuthCredential credential = PhoneAuthProvider.getCredential(verificationId, otp);
        signInWithCredential(credential);
    }

    private void resendOtp() {
        if (resendToken == null || fullPhoneNumber == null) return;
        setLoading(true);
        PhoneAuthOptions options = PhoneAuthOptions.newBuilder(mAuth)
                .setPhoneNumber(fullPhoneNumber)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(this)
                .setForceResendingToken(resendToken)
                .setCallbacks(new PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                    @Override public void onVerificationCompleted(@androidx.annotation.NonNull PhoneAuthCredential c) { signInWithCredential(c); }
                    @Override public void onVerificationFailed(@androidx.annotation.NonNull FirebaseException e) {
                        setLoading(false);
                        Toast.makeText(AuthActivity.this, e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                    @Override public void onCodeSent(@androidx.annotation.NonNull String vId,
                                                     @androidx.annotation.NonNull PhoneAuthProvider.ForceResendingToken token) {
                        verificationId = vId; resendToken = token;
                        setLoading(false);
                        Toast.makeText(AuthActivity.this, "OTP resent", Toast.LENGTH_SHORT).show();
                    }
                }).build();
        PhoneAuthProvider.verifyPhoneNumber(options);
    }

    private void signInWithCredential(PhoneAuthCredential credential) {
        mAuth.signInWithCredential(credential).addOnCompleteListener(task -> {
            setLoading(false);
            if (task.isSuccessful() && task.getResult() != null) {
                String uid = task.getResult().getUser().getUid();
                SecurePrefs.put(Constants.PREF_UID, uid);
                SecurePrefs.put(Constants.PREF_PHONE, fullPhoneNumber);
                boolean isNew = task.getResult().getAdditionalUserInfo() != null
                        && task.getResult().getAdditionalUserInfo().isNewUser();
                if (isNew || !SecurePrefs.getBoolean(Constants.PREF_PROFILE_DONE, false)) {
                    startActivity(new Intent(this, ProfileSetupActivity.class));
                } else {
                    startActivity(new Intent(this, MainActivity.class));
                }
                finish();
            } else {
                Toast.makeText(this, "Authentication failed. Check OTP.", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnSendOtp.setEnabled(!loading);
        btnVerify.setEnabled(!loading);
    }
}