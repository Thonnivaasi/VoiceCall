package com.voicecall;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.SoundPool;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class MainActivity extends AppCompatActivity {

    private static final int AUDIO_PORT = 50005;
    private static final int DISCOVERY_PORT = 50006;
    private static final int SAMPLE_RATE = 8000;
    private static final int BUFFER_SIZE = 1024;
    private static final int RECONNECT_TIMEOUT_MS = 30000;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final String FIXED_KEY = "VoiceCallAppKey1";

    private Button btnHost, btnCall, btnEndCall, btnMute, btnSpeaker;
    private EditText etRoomCode;
    private TextView tvStatus, tvRoomCode, tvTimer, tvQuality, tvLabel;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private AudioManager audioManager;
    private SoundPool soundPool;
    private int soundDial, soundConnect, soundDisconnect;
    private DatagramSocket audioSocket;
    private DatagramSocket discoverySocket;
    private InetAddress remoteAddress;
    private int remotePort;
    private AtomicBoolean isRunning = new AtomicBoolean(false);
    private AtomicBoolean isMuted = new AtomicBoolean(false);
    private boolean isSpeakerOn = false;
    private String currentRoomCode;
    private int reconnectAttempts = 0;
    private long callStartTime;
    private long lastPacketTime;
    private Handler timerHandler = new Handler(Looper.getMainLooper());
    private Handler qualityHandler = new Handler(Looper.getMainLooper());
    private CallService callService;
    private boolean serviceBound = false;
    private byte[] encryptionKey;
    private boolean hasVibrated = false;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            CallService.LocalBinder binder = (CallService.LocalBinder) service;
            callService = binder.getService();
            serviceBound = true;
        }
        @Override
        public void onServiceDisconnected(ComponentName name) { serviceBound = false; }
    };

    private BroadcastReceiver endCallReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) { endCall(); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        btnHost = findViewById(R.id.btnHost);
        btnCall = findViewById(R.id.btnCall);
        btnEndCall = findViewById(R.id.btnEndCall);
        btnMute = findViewById(R.id.btnMute);
        btnSpeaker = findViewById(R.id.btnSpeaker);
        etRoomCode = findViewById(R.id.etRoomCode);
        tvStatus = findViewById(R.id.tvStatus);
        tvRoomCode = findViewById(R.id.tvRoomCode);
        tvTimer = findViewById(R.id.tvTimer);
        tvQuality = findViewById(R.id.tvQuality);
        tvLabel = findViewById(R.id.tvLabel);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        soundPool = new SoundPool.Builder().setMaxStreams(3).build();
        soundDial = soundPool.load(this, R.raw.dial_tone, 1);
        soundConnect = soundPool.load(this, R.raw.connect_tone, 1);
        soundDisconnect = soundPool.load(this, R.raw.disconnect_tone, 1);
        String[] perms = {Manifest.permission.RECORD_AUDIO};
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms = new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS};
        }
        ActivityCompat.requestPermissions(this, perms, 1);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(endCallReceiver, new IntentFilter("com.voicecall.END_CALL_ACTION"), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(endCallReceiver, new IntentFilter("com.voicecall.END_CALL_ACTION"));
        }
        btnHost.setOnClickListener(v -> {
            if (!checkPermission()) { tvStatus.setText("Status: Mic permission needed!"); return; }
            startHostMode();
        });
        btnCall.setOnClickListener(v -> {
            if (!checkPermission()) { tvStatus.setText("Status: Mic permission needed!"); return; }
            String code = etRoomCode.getText().toString().trim();
            if (code.length() != 6) { tvStatus.setText("Status: Enter 6 digit room code!"); return; }
            connectToHost(code);
        });
        btnEndCall.setOnClickListener(v -> endCall());
        btnMute.setOnClickListener(v -> toggleMute());
        btnSpeaker.setOnClickListener(v -> toggleSpeaker());
    }

    private void startHostMode() {
        currentRoomCode = String.format("%06d", new Random().nextInt(999999));
        encryptionKey = deriveKey(currentRoomCode + FIXED_KEY);
        tvRoomCode.setText("Room Code: " + currentRoomCode);
        tvRoomCode.setVisibility(View.VISIBLE);
        tvLabel.setText("Share this code with the other phone");
        tvLabel.setVisibility(View.VISIBLE);
        tvStatus.setText("Status: Waiting for call...");
        soundPool.play(soundDial, 1f, 1f, 0, 0, 1f);
        startCallService();
        new Thread(() -> {
            try {
                discoverySocket = new DatagramSocket(DISCOVERY_PORT);
                byte[] buf = new byte[512];
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                while (!isRunning.get()) {
                    discoverySocket.receive(pkt);
                    String msg = new String(pkt.getData(), 0, pkt.getLength());
                    if (msg.startsWith("FIND:" + currentRoomCode)) {
                        String myIp = getDeviceIpAddress();
                        String response = "HOST:" + myIp + ":" + AUDIO_PORT;
                        byte[] resp = response.getBytes();
                        discoverySocket.send(new DatagramPacket(resp, resp.length, pkt.getAddress(), pkt.getPort()));
                        runOnUiThread(() -> tvStatus.setText("Status: Guest found! Connecting..."));
                    }
                }
            } catch (Exception ignored) {}
        }).start();
        new Thread(() -> {
            try {
                audioSocket = new DatagramSocket(AUDIO_PORT);
                byte[] buf = new byte[BUFFER_SIZE + 64];
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                audioSocket.receive(pkt);
                remoteAddress = pkt.getAddress();
                remotePort = pkt.getPort();
                isRunning.set(true);
                lastPacketTime = System.currentTimeMillis();
                runOnUiThread(this::onCallConnected);
                startAudioStreaming();
            } catch (Exception e) {
                if (isRunning.get()) runOnUiThread(() -> tvStatus.setText("Status: Error - " + e.getMessage()));
            }
        }).start();
    }

    private void connectToHost(String roomCode) {
        currentRoomCode = roomCode;
        encryptionKey = deriveKey(roomCode + FIXED_KEY);
        tvStatus.setText("Status: Searching for host...");
        soundPool.play(soundDial, 1f, 1f, 0, -1, 1f);
        startCallService();
        new Thread(() -> {
            try {
                DatagramSocket discSocket = new DatagramSocket();
                discSocket.setSoTimeout(3000);
                String findMsg = "FIND:" + roomCode;
                byte[] findBytes = findMsg.getBytes();
                String[] subnets = {"192.168.43.255", "192.168.1.255", "192.168.0.255", "10.0.0.255", "172.20.10.255"};
                InetAddress hostAddress = null;
                int hostPort = AUDIO_PORT;
                for (int attempt = 0; attempt < 3 && hostAddress == null; attempt++) {
                    for (String subnet : subnets) {
                        try { discSocket.send(new DatagramPacket(findBytes, findBytes.length, InetAddress.getByName(subnet), DISCOVERY_PORT)); } catch (Exception ignored) {}
                    }
                    try {
                        byte[] respBuf = new byte[256];
                        DatagramPacket respPkt = new DatagramPacket(respBuf, respBuf.length);
                        discSocket.receive(respPkt);
                        String response = new String(respPkt.getData(), 0, respPkt.getLength());
                        if (response.startsWith("HOST:")) {
                            String[] parts = response.split(":");
                            hostAddress = InetAddress.getByName(parts[1]);
                            hostPort = Integer.parseInt(parts[2]);
                        }
                    } catch (Exception ignored) {}
                }
                discSocket.close();
                if (hostAddress == null) {
                    runOnUiThread(() -> { soundPool.stop(soundDial); tvStatus.setText("Status: Host not found. Check room code."); stopCallService(); });
                    return;
                }
                remoteAddress = hostAddress;
                remotePort = hostPort;
                audioSocket = new DatagramSocket();
                byte[] hello = encryptData("HELLO".getBytes());
                audioSocket.send(new DatagramPacket(hello, hello.length, remoteAddress, remotePort));
                isRunning.set(true);
                lastPacketTime = System.currentTimeMillis();
                runOnUiThread(() -> { soundPool.stop(soundDial); onCallConnected(); });
                startAudioStreaming();
            } catch (Exception e) {
                runOnUiThread(() -> { soundPool.stop(soundDial); tvStatus.setText("Status: Error - " + e.getMessage()); stopCallService(); });
            }
        }).start();
    }

    private void startAudioStreaming() {
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (!checkPermission()) return;
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuffer, BUFFER_SIZE * 2));
        audioTrack = new AudioTrack(AudioManager.STREAM_VOICE_CALL, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), BUFFER_SIZE * 2), AudioTrack.MODE_STREAM);
        audioRecord.startRecording();
        audioTrack.play();
        new Thread(() -> {
            byte[] buffer = new byte[BUFFER_SIZE];
            while (isRunning.get()) {
                try {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        byte[] data = isMuted.get() ? new byte[read] : buffer.clone();
                        byte[] encrypted = encryptData(data);
                        audioSocket.send(new DatagramPacket(encrypted, encrypted.length, remoteAddress, remotePort));
                    }
                } catch (Exception e) { if (isRunning.get()) attemptReconnect(); break; }
            }
        }).start();
        new Thread(() -> {
            byte[] buffer = new byte[BUFFER_SIZE + 64];
            while (isRunning.get()) {
                try {
                    DatagramPacket pkt = new DatagramPacket(buffer, buffer.length);
                    audioSocket.setSoTimeout(RECONNECT_TIMEOUT_MS);
                    audioSocket.receive(pkt);
                    lastPacketTime = System.currentTimeMillis();
                    byte[] decrypted = decryptData(pkt.getData(), pkt.getLength());
                    if (decrypted != null) audioTrack.write(decrypted, 0, decrypted.length);
                } catch (java.net.SocketTimeoutException e) {
                    if (isRunning.get()) { runOnUiThread(() -> tvStatus.setText("Status: Connection weak...")); attemptReconnect(); }
                    break;
                } catch (Exception e) { if (isRunning.get()) attemptReconnect(); break; }
            }
        }).start();
        qualityHandler.postDelayed(qualityRunnable, 2000);
    }

    private Runnable qualityRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRunning.get()) {
                long t = System.currentTimeMillis() - lastPacketTime;
                String quality = t < 200 ? "Excellent" : t < 500 ? "Good" : t < 1500 ? "Poor" : "No Signal";
                tvQuality.setText("Signal: " + quality);
                if (serviceBound && callService != null)
                    callService.updateNotification("Call Active", "Duration: " + tvTimer.getText() + " | " + quality);
                qualityHandler.postDelayed(this, 2000);
            }
        }
    };

    private Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRunning.get()) {
                long elapsed = System.currentTimeMillis() - callStartTime;
                long seconds = (elapsed / 1000) % 60;
                long minutes = (elapsed / 1000) / 60;
                tvTimer.setText(String.format("%02d:%02d", minutes, seconds));
                timerHandler.postDelayed(this, 1000);
            }
        }
    };

    private void attemptReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            runOnUiThread(() -> { tvStatus.setText("Status: Call dropped."); soundPool.play(soundDisconnect, 1f, 1f, 0, 0, 1f); });
            return;
        }
        reconnectAttempts++;
        runOnUiThread(() -> tvStatus.setText("Reconnecting... (" + reconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS + ")"));
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isRunning.get() && remoteAddress != null) {
                try {
                    byte[] hello = encryptData("HELLO".getBytes());
                    audioSocket.send(new DatagramPacket(hello, hello.length, remoteAddress, remotePort));
                    runOnUiThread(() -> tvStatus.setText("Status: Connected!"));
                    reconnectAttempts = 0;
                } catch (Exception e) { attemptReconnect(); }
            }
        }, 1000);
    }

    private void onCallConnected() {
        callStartTime = System.currentTimeMillis();
        reconnectAttempts = 0;
        hasVibrated = false;
        tvStatus.setText("Status: Connected!");
        tvRoomCode.setVisibility(View.GONE);
        tvLabel.setVisibility(View.GONE);
        tvTimer.setVisibility(View.VISIBLE);
        tvQuality.setVisibility(View.VISIBLE);
        btnHost.setVisibility(View.GONE);
        btnCall.setVisibility(View.GONE);
        etRoomCode.setVisibility(View.GONE);
        btnEndCall.setVisibility(View.VISIBLE);
        btnMute.setVisibility(View.VISIBLE);
        btnSpeaker.setVisibility(View.VISIBLE);
        soundPool.play(soundConnect, 1f, 1f, 0, 0, 1f);

        timerHandler.post(timerRunnable);
        qualityHandler.post(qualityRunnable);
        if (serviceBound && callService != null)
            callService.updateNotification("Call Active", "VoiceCall connected");
    }

    private void endCall() {
        if (!isRunning.get() && audioSocket == null) { resetUI(); stopCallService(); return; }
        isRunning.set(false);
        timerHandler.removeCallbacks(timerRunnable);
        qualityHandler.removeCallbacks(qualityRunnable);
        soundPool.play(soundDisconnect, 1f, 1f, 0, 0, 1f);
        new Thread(() -> {
            try { if (audioRecord != null) { audioRecord.stop(); audioRecord.release(); } } catch (Exception ignored) {}
            try { if (audioTrack != null) { audioTrack.stop(); audioTrack.release(); } } catch (Exception ignored) {}
            try { if (audioSocket != null) audioSocket.close(); } catch (Exception ignored) {}
            try { if (discoverySocket != null) discoverySocket.close(); } catch (Exception ignored) {}
            audioRecord = null; audioTrack = null; audioSocket = null; discoverySocket = null; remoteAddress = null;
        }).start();
        if (isSpeakerOn) {
            audioManager.setMode(AudioManager.MODE_NORMAL);
            audioManager.setSpeakerphoneOn(false);
            isSpeakerOn = false;
        }
        isMuted.set(false);
        hasVibrated = false;
        stopCallService();
        resetUI();
    }

    private void resetUI() {
        runOnUiThread(() -> {
            tvStatus.setText("Status: Idle");
            tvTimer.setVisibility(View.GONE);
            tvTimer.setText("00:00");
            tvQuality.setVisibility(View.GONE);
            tvRoomCode.setVisibility(View.GONE);
            tvLabel.setVisibility(View.GONE);
            btnHost.setVisibility(View.VISIBLE);
            btnCall.setVisibility(View.VISIBLE);
            etRoomCode.setVisibility(View.VISIBLE);
            btnEndCall.setVisibility(View.GONE);
            btnMute.setVisibility(View.GONE);
            btnSpeaker.setVisibility(View.GONE);
            btnMute.setText("MUTE");
            btnMute.setBackgroundTintList(getColorStateList(android.R.color.holo_blue_dark));
            btnSpeaker.setText("SPEAKER");
            btnSpeaker.setBackgroundTintList(getColorStateList(android.R.color.holo_blue_dark));
        });
    }

    private void toggleMute() {
        boolean muted = !isMuted.get();
        isMuted.set(muted);
        btnMute.setText(muted ? "UNMUTE" : "MUTE");
        btnMute.setBackgroundTintList(getColorStateList(muted ? android.R.color.holo_red_dark : android.R.color.holo_blue_dark));
    }

    private void toggleSpeaker() {
        isSpeakerOn = !isSpeakerOn;
        if (isSpeakerOn) {
            audioManager.setMode(AudioManager.MODE_NORMAL);
            audioManager.setSpeakerphoneOn(true);
        } else {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(false);
        }
        btnSpeaker.setText(isSpeakerOn ? "EARPIECE" : "SPEAKER");
        btnSpeaker.setBackgroundTintList(getColorStateList(isSpeakerOn ? android.R.color.holo_green_dark : android.R.color.holo_blue_dark));
    }

    private void vibrate() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                v.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(300);
        }
    }

    private byte[] deriveKey(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] key = digest.digest(password.getBytes("UTF-8"));
            byte[] result = new byte[16];
            System.arraycopy(key, 0, result, 0, 16);
            return result;
        } catch (Exception e) { return new byte[16]; }
    }

    private byte[] encryptData(byte[] data) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(encryptionKey, "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            return cipher.doFinal(data);
        } catch (Exception e) { return data; }
    }

    private byte[] decryptData(byte[] data, int length) {
        try {
            byte[] trimmed = new byte[length];
            System.arraycopy(data, 0, trimmed, 0, length);
            SecretKeySpec keySpec = new SecretKeySpec(encryptionKey, "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            return cipher.doFinal(trimmed);
        } catch (Exception e) { return null; }
    }

    private String getDeviceIpAddress() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            int ip = wm.getConnectionInfo().getIpAddress();
            if (ip != 0) return Formatter.formatIpAddress(ip);
        } catch (Exception ignored) {}
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                Enumeration<java.net.InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress addr = addresses.nextElement();
                    String ip = addr.getHostAddress();
                    if (!addr.isLoopbackAddress() && ip != null && !ip.contains(":")) return ip;
                }
            }
        } catch (Exception ignored) {}
        return "0.0.0.0";
    }

    private boolean checkPermission() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void startCallService() {
        Intent intent = new Intent(this, CallService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void stopCallService() {
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false; }
        stopService(new Intent(this, CallService.class));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(endCallReceiver); } catch (Exception ignored) {}
        endCall();
    }
}