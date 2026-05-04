// DriveActivity: live drive screen that responds to BLE DETECT messages and logs prolonged events
package com.example.drowsinessdetection;

import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Drive screen: shows live counters, changes background color on drowsy, and logs DETECT lines to session text files.
 */
public class DriveActivity extends AppCompatActivity {

    private View backgroundView;
    private TextView prolongedDrowsyText;
    private TextView prolongedYawnText;
    private TextView awakeText;
    private TextView totalText;
    private Button endDriveButton;

    private File sessionRootDir;
    private File currentSessionDir;
    private File currentLogFile;
    private long currentSessionStartMillis;
    private boolean sessionActive = false;

    // Detection counters
    private int prolongedDrowsyCount = 0;
    private int prolongedYawnCount = 0;
    private int awakeCount = 0;
    private int totalDetections = 0;

    // Sliding windows of detection timestamps (ms)
    private final ArrayDeque<Long> drowsyTimestamps = new ArrayDeque<>();
    private final ArrayDeque<Long> yawnTimestamps = new ArrayDeque<>();

    private static final int DROWSY_THRESHOLD = 6; // detections within window
    private static final int YAWN_THRESHOLD = 8;   // detections within window
    private static final long WINDOW_MS = 2000L;   // 2 seconds

    private long alertHoldUntilMs = 0L;
    private MediaPlayer mediaPlayer;

    private BluetoothConnectionHolder.MessageListener holderListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_drive);

        backgroundView = findViewById(R.id.driveRoot);
        prolongedDrowsyText = findViewById(R.id.prolongedDrowsyText);
        prolongedYawnText = findViewById(R.id.prolongedYawnText);
        awakeText = findViewById(R.id.awakeCountText);
        totalText = findViewById(R.id.totalCountText);
        endDriveButton = findViewById(R.id.endDriveButton);

        File docs = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        sessionRootDir = docs != null ? new File(docs, "DrowsySessions") : null;
        if (sessionRootDir != null && !sessionRootDir.exists()) sessionRootDir.mkdirs();

        endDriveButton.setOnClickListener(v -> finishSessionAndClose());

        mediaPlayer = MediaPlayer.create(this, R.raw.beeping);
        if (mediaPlayer != null) {
            mediaPlayer.setLooping(true);
        }

        startSession();
    }

    private void startSession() {
        if (sessionRootDir == null) {
            Toast.makeText(this, "Storage unavailable", Toast.LENGTH_LONG).show();
            return;
        }
        // Reset counters and state for a fresh session
        prolongedDrowsyCount = 0;
        prolongedYawnCount = 0;
        awakeCount = 0;
        totalDetections = 0;
        drowsyTimestamps.clear();
        yawnTimestamps.clear();
        alertHoldUntilMs = 0L;

        int sessionIndex = determineNextSessionIndex();
        String sessionName = String.format(Locale.US, "session_%03d_%tY%<tm%<td_%<tH%<tM%<tS", sessionIndex, System.currentTimeMillis());
        currentSessionDir = new File(sessionRootDir, sessionName);
        if (!currentSessionDir.exists() && !currentSessionDir.mkdirs()) {
            Toast.makeText(this, "Failed to create session folder", Toast.LENGTH_LONG).show();
            return;
        }
        currentLogFile = new File(currentSessionDir, "session_log.txt");
        currentSessionStartMillis = System.currentTimeMillis();
        sessionActive = true;
        updateCounters();
        setGreen();
    }

    private double computeScore() {
        double numer = prolongedDrowsyCount + prolongedYawnCount;
        double denom = totalDetections;
        if (denom <= 0) return 100.0;
        double score = 100.0 * (1.0 - (numer / denom));
        if (score < 0) score = 0; if (score > 100) score = 100;
        return score;
    }

    private void finishSessionAndClose() {
        if (sessionActive) {
            long end = System.currentTimeMillis();
            double score = computeScore();
            File meta = new File(currentSessionDir, "session_meta.txt");
            String content = "start=" + currentSessionStartMillis + "\n" +
                    "end=" + end + "\n" +
                    "prolongedDrowsy=" + prolongedDrowsyCount + "\n" +
                    "prolongedYawn=" + prolongedYawnCount + "\n" +
                    "awake=" + awakeCount + "\n" +
                    "total=" + totalDetections + "\n" +
                    "score=" + String.format(Locale.US, "%.2f", score) + "\n";
            try (FileWriter fw = new FileWriter(meta, false)) { fw.write(content); } catch (IOException ignored) {}
        }
        sessionActive = false;
        finish();
    }

    private int determineNextSessionIndex() {
        if (sessionRootDir == null) return 1;
        File[] children = sessionRootDir.listFiles();
        int max = 0;
        if (children != null) {
            for (File f : children) {
                if (f.isDirectory() && f.getName().startsWith("session_")) {
                    try {
                        String[] parts = f.getName().split("_");
                        if (parts.length >= 2) {
                            int idx = Integer.parseInt(parts[1]);
                            if (idx > max) max = idx;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        return max + 1;
    }

    private void appendToSessionLog(String text) {
        if (!sessionActive || currentLogFile == null) return;
        try (FileWriter fw = new FileWriter(currentLogFile, true)) { fw.write(text); } catch (IOException ignored) {}
    }

    private void onDetect(String label, float confidence, int count, String timestamp) {
        long now = System.currentTimeMillis();
        boolean thresholdHit = false;
        switch (label) {
            case "Drowsy":
                drowsyTimestamps.addLast(now);
                purgeOld(drowsyTimestamps, now);
                if (drowsyTimestamps.size() >= DROWSY_THRESHOLD) {
                    prolongedDrowsyCount++;
                    drowsyTimestamps.clear();
                    thresholdHit = true;
                    appendToSessionLog(String.format(Locale.US, "%s\tDrowsyProlonged\t%.2f\t%d\n", timestamp, confidence, count));
                }
                break;
            case "Yawning":
                yawnTimestamps.addLast(now);
                purgeOld(yawnTimestamps, now);
                if (yawnTimestamps.size() >= YAWN_THRESHOLD) {
                    prolongedYawnCount++;
                    yawnTimestamps.clear();
                    thresholdHit = true;
                    appendToSessionLog(String.format(Locale.US, "%s\tYawningProlonged\t%.2f\t%d\n", timestamp, confidence, count));
                }
                break;
            case "Awake":
                awakeCount = Math.max(awakeCount, count);
                purgeOld(drowsyTimestamps, now);
                purgeOld(yawnTimestamps, now);
                break;
            default:
                purgeOld(drowsyTimestamps, now);
                purgeOld(yawnTimestamps, now);
                break;
        }
        totalDetections++;

        if (thresholdHit) {
            alertHoldUntilMs = now + WINDOW_MS;
        }
        boolean alertActive = thresholdHit || now < alertHoldUntilMs;

        if (alertActive) {
            setRed();
            startBeep();
        } else {
            setGreen();
            stopBeep();
        }
        updateCounters();
    }

    private void purgeOld(ArrayDeque<Long> deque, long now) {
        while (!deque.isEmpty() && now - deque.peekFirst() > WINDOW_MS) {
            deque.pollFirst();
        }
    }

    private void startBeep() {
        if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
            mediaPlayer.start();
        }
    }

    private void stopBeep() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            mediaPlayer.seekTo(0);
        }
    }

    private void updateCounters() {
        prolongedDrowsyText.setText("Prolonged Drowsy: " + prolongedDrowsyCount);
        prolongedYawnText.setText("Prolonged Yawn: " + prolongedYawnCount);
        awakeText.setText("Awake: " + awakeCount);
        totalText.setText("Total: " + totalDetections);
    }

    private void setGreen() { backgroundView.setBackgroundColor(0xFF4CAF50); }
    private void setRed() { backgroundView.setBackgroundColor(0xFFF44336); }

    @Override
    protected void onStart() {
        super.onStart();
        BluetoothConnectionHolder holder = BluetoothConnectionHolder.getInstance();
        if (holderListener == null) {
            holderListener = line -> {
                if (line.startsWith("DETECT|")) {
                    String[] parts = line.split("\\\\|");
                    if (parts.length >= 5) {
                        String label = parts[1];
                        float conf = safeFloat(parts[2]);
                        int cnt = safeInt(parts[3]);
                        String ts = parts[4];
                        runOnUiThread(() -> onDetect(label, conf, cnt, ts));
                    }
                }
            };
        }
        if (holder.isConnected()) {
            holder.registerListener(holderListener);
        } else {
            Toast.makeText(this, "Not connected to Pi", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (holderListener != null) {
            BluetoothConnectionHolder.getInstance().unregisterListener(holderListener);
        }
        stopBeep();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    @Override
    public void onBackPressed() {
        finishSessionAndClose();
        super.onBackPressed();
    }

    private float safeFloat(String s) { try { return Float.parseFloat(s); } catch (Exception e) { return 0f; } }
    private int safeInt(String s) { try { return Integer.parseInt(s); } catch (Exception e) { return 0; } }
}
