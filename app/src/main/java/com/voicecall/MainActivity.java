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
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
import java.util.Enumeration;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    private static final int AUDIO_PORT = 50005;
    private static final int DISCOVERY_PORT = 50006;
    private static final int SAMPLE_RATE = 8000;
    private static final int BUFFER_SIZE = 1024;

    private Button btnHost, btnCall, btnEndCall, btnMute, btnSpeaker;
    private EditText etRoomCode;
    private TextView tvStatus, tvRoomCode, tvTimer, tvQuality, tvLabel;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private AudioManager audioManager;
    private DatagramSocket audioSocket;
    private DatagramSocket discoverySocket;
    private InetAddress remoteAddress;
    private int remotePort;
    private AtomicBoolean isRunning = new AtomicBoolean(false);
    private AtomicBoolean isMuted = new AtomicBoolean(false);
    private boolean isSpeakerOn = false;
    private String currentRoomCode;
    private long callStartTime;
    private long lastPacketTime;
    private Handler timerHandler = new Handler(Looper.getMainLooper());
    private Handler qualityHandler = new Handler(Looper.getMainLooper());
    private CallService callService;
    private boolean serviceBound = false;

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
        tvRoomCode.setText("Room Code: " + currentRoomCode);
        tvRoomCode.setVisibility(View.VISIBLE);
        tvLabel.setText("Share this code with the other phone");
        tvLabel.setVisibility(View.VISIBLE);
        tvStatus.setText("Status: Waiting for call...");
        btnHost.setVisibility(View.GONE);
        btnCall.setVisibility(View.GONE);
        etRoomCode.setVisibility(View.GONE);
        btnEndCall.setVisibility(View.VISIBLE);
        startCallService();

        // Discovery thread - keeps responding to guest searches
        new Thread(() -> {
            try {
                discoverySocket = new DatagramSocket(DISCOVERY_PORT);
                discoverySocket.setSoTimeout(0);
                byte[] buf = new byte[512];
                while (!isRunning.get()) {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    discoverySocket.receive(pkt);
                    String msg = new String(pkt.getData(), 0, pkt.getLength()).trim();
                    if (msg.equals("FIND:" + currentRoomCode)) {
                        String myIp = getDeviceIpAddress();
                        String response = "HOST:" + myIp;
                        byte[] resp = response.getBytes();
                        discoverySocket.send(new DatagramPacket(resp, resp.length,
                                pkt.getAddress(), pkt.getPort()));
                        runOnUiThread(() -> tvStatus.setText("Status: Guest found! Connecting..."));
                    }
                }
            } catch (Exception ignored) {}
        }).start();

        // Audio thread - waits for first packet from guest
        new Thread(() -> {
            try {
                audioSocket = new DatagramSocket(AUDIO_PORT);
                audioSocket.setSoTimeout(0);
                byte[] buf = new byte[BUFFER_SIZE * 2];
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                audioSocket.receive(pkt);
                remoteAddress = pkt.getAddress();
                remotePort = pkt.getPort();
                isRunning.set(true);
                lastPacketTime = System.currentTimeMillis();
                runOnUiThread(this::onCallConnected);
                startAudioStreaming();
            } catch (Exception e) {
                if (isRunning.get())
                    runOnUiThread(() -> tvStatus.setText("Error: " + e.getMessage()));
            }
        }).start();
    }

    private void connectToHost(String roomCode) {
        currentRoomCode = roomCode;
        tvStatus.setText("Status: Searching...");
        btnHost.setVisibility(View.GONE);
        btnCall.setVisibility(View.GONE);
        etRoomCode.setVisibility(View.GONE);
        btnEndCall.setVisibility(View.VISIBLE);
        startCallService();

        new Thread(() -> {
            try {
                // Try to find host by broadcasting on all subnets
                DatagramSocket discSocket = new DatagramSocket();
                discSocket.setBroadcast(true);
                discSocket.setSoTimeout(2000);

                String findMsg = "FIND:" + roomCode;
                byte[] findBytes = findMsg.getBytes();

                String[] subnets = {
                    "192.168.43.255",
                    "192.168.1.255",
                    "192.168.0.255",
                    "192.168.2.255",
                    "10.0.0.255",
                    "172.20.10.255",
                    "172.16.0.255"
                };

                InetAddress hostAddress = null;

                // Try multiple times with all subnets
                for (int attempt = 0; attempt < 5 && hostAddress == null; attempt++) {
                    runOnUiThread(() -> tvStatus.setText("Status: Searching... attempt " + (attempt + 1)));

                    for (String subnet : subnets) {
                        try {
                            DatagramPacket findPkt = new DatagramPacket(
                                    findBytes, findBytes.length,
                                    InetAddress.getByName(subnet), DISCOVERY_PORT);
                            discSocket.send(findPkt);
                        } catch (Exception ignored) {}
                    }

                    // Also try direct IP scan on common hotspot subnet
                    try {
                        String myIp = getDeviceIpAddress();
                        if (!myIp.equals("0.0.0.0")) {
                            String subnet = myIp.substring(0, myIp.lastIndexOf(".") + 1);
                            for (int i = 1; i <= 20; i++) {
                                try {
                                    DatagramPacket findPkt = new DatagramPacket(
                                            findBytes, findBytes.length,
                                            InetAddress.getByName(subnet + i), DISCOVERY_PORT);
                                    discSocket.send(findPkt);
                                } catch (Exception ignored) {}
                            }
                        }
                    } catch (Exception ignored) {}

                    // Wait for response
                    try {
                        byte[] respBuf = new byte[256];
                        DatagramPacket respPkt = new DatagramPacket(respBuf, respBuf.length);
                        discSocket.receive(respPkt);
                        String response = new String(respPkt.getData(), 0, respPkt.getLength()).trim();
                        if (response.startsWith("HOST:")) {
                            String ip = response.substring(5);
                            hostAddress = InetAddress.getByName(ip);
                        }
                    } catch (Exception ignored) {}

                    if (hostAddress == null) Thread.sleep(500);
                }

                discSocket.close();

                if (hostAddress == null) {
                    runOnUiThread(() -> {
                        tvStatus.setText("Status: Host not found. Check room code.");
                        resetUI();
                        stopCallService();
                    });
                    return;
                }

                final InetAddress finalHost = hostAddress;
                remoteAddress = finalHost;
                remotePort = AUDIO_PORT;

                audioSocket = new DatagramSocket();
                audioSocket.setSoTimeout(0);

                // Send hello packet to host
                byte[] hello = "HELLO".getBytes();
                audioSocket.send(new DatagramPacket(hello, hello.length, remoteAddress, remotePort));

                isRunning.set(true);
                lastPacketTime = System.currentTimeMillis();
                runOnUiThread(this::onCallConnected);
                startAudioStreaming();

            } catch (Exception e) {
                runOnUiThread(() -> {
                    tvStatus.setText("Error: " + e.getMessage());
                    resetUI();
                    stopCallService();
                });
            }
        }).start();
    }

    private void startAudioStreaming() {
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (!checkPermission()) return;

        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minBuffer, BUFFER_SIZE * 2));

        audioTrack = new AudioTrack(AudioManager.STREAM_VOICE_CALL, SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(AudioTrack.getMinBufferSize(SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
                        BUFFER_SIZE * 2), AudioTrack.MODE_STREAM);

        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioRecord.startRecording();
        audioTrack.play();

        // Send audio thread
        new Thread(() -> {
            byte[] buffer = new byte[BUFFER_SIZE];
            while (isRunning.get()) {
                try {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        byte[] data = isMuted.get() ? new byte[read] : buffer.clone();
                        audioSocket.send(new DatagramPacket(data, read, remoteAddress, remotePort));
                    }
                } catch (Exception e) {
                    if (isRunning.get()) break;
                }
            }
        }).start();

        // Receive audio thread - no timeout, runs forever
        new Thread(() -> {
            byte[] buffer = new byte[BUFFER_SIZE * 2];
            while (isRunning.get()) {
                try {
                    DatagramPacket pkt = new DatagramPacket(buffer, buffer.length);
                    audioSocket.receive(pkt);
                    lastPacketTime = System.currentTimeMillis();
                    // Skip HELLO packets
                    String check = new String(pkt.getData(), 0, Math.min(pkt.getLength(), 5));
                    if (!check.equals("HELLO")) {
                        audioTrack.write(pkt.getData(), 0, pkt.getLength());
                    }
                } catch (Exception e) {
                    if (isRunning.get()) break;
                }
            }
        }).start();

        // Keep-alive thread - sends silent packet every 3 seconds to keep connection alive
        new Thread(() -> {
            byte[] keepAlive = new byte[BUFFER_SIZE];
            while (isRunning.get()) {
                try {
                    Thread.sleep(3000);
                    if (isRunning.get()) {
                        audioSocket.send(new DatagramPacket(keepAlive, keepAlive.length,
                                remoteAddress, remotePort));
                    }
                } catch (Exception ignored) {}
            }
        }).start();

        qualityHandler.postDelayed(qualityRunnable, 2000);
    }

    private Runnable qualityRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRunning.get()) {
                long t = System.currentTimeMillis() - lastPacketTime;
                String quality = t < 500 ? "Excellent" : t < 2000 ? "Good" : t < 5000 ? "Poor" : "No Signal";
                tvQuality.setText("Signal: " + quality);
                if (serviceBound && callService != null)
                    callService.updateNotification("Call Active",
                            "Duration: " + tvTimer.getText() + " | " + quality);
                qualityHandler.postDelayed(this, 3000);
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

    private void onCallConnected() {
        callStartTime = System.currentTimeMillis();
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
        timerHandler.post(timerRunnable);
        qualityHandler.post(qualityRunnable);
        if (serviceBound && callService != null)
            callService.updateNotification("Call Active", "VoiceCall connected");
    }

    private void endCall() {
        isRunning.set(false);
        timerHandler.removeCallbacks(timerRunnable);
        qualityHandler.removeCallbacks(qualityRunnable);
        new Thread(() -> {
            try { if (audioRecord != null) { audioRecord.stop(); audioRecord.release(); } } catch (Exception ignored) {}
            try { if (audioTrack != null) { audioTrack.stop(); audioTrack.release(); } } catch (Exception ignored) {}
            try { if (audioSocket != null) audioSocket.close(); } catch (Exception ignored) {}
            try { if (discoverySocket != null) discoverySocket.close(); } catch (Exception ignored) {}
            audioRecord = null; audioTrack = null; audioSocket = null;
            discoverySocket = null; remoteAddress = null;
        }).start();
        audioManager.setMode(AudioManager.MODE_NORMAL);
        audioManager.setSpeakerphoneOn(false);
        isSpeakerOn = false;
        isMuted.set(false);
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
        btnMute.setBackgroundTintList(getColorStateList(
                muted ? android.R.color.holo_red_dark : android.R.color.holo_blue_dark));
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
        btnSpeaker.setBackgroundTintList(getColorStateList(
                isSpeakerOn ? android.R.color.holo_green_dark : android.R.color.holo_blue_dark));
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
        return ActivityCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
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