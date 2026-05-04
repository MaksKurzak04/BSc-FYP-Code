// InfoActivity: helper page for clearing sessions and generating sample session data
package com.example.drowsinessdetection;

import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Locale;

public class InfoActivity extends AppCompatActivity {
    private TextView statusTextView;
    private File baseDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_info);

        // Taskbar navigation
        ImageButton btn1 = findViewById(R.id.btn1);
        ImageButton btn2 = findViewById(R.id.btn2);
        ImageButton btn3 = findViewById(R.id.btn3);
        ImageButton btn4 = findViewById(R.id.btn4);

        btn1.setOnClickListener(v -> {
            Intent intent = new Intent(InfoActivity.this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
        });

        btn2.setOnClickListener(v -> {
            Intent intent = new Intent(InfoActivity.this, SessionListActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
        });

        btn3.setOnClickListener(v -> {
            Intent intent = new Intent(InfoActivity.this, BluetoothTestActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
        });

        btn4.setOnClickListener(v -> {
            // stay on info
        });

        statusTextView = findViewById(R.id.statusTextView);
        Button clearSessionsButton = findViewById(R.id.clearSessionsButton);
        Button generateSampleButton = findViewById(R.id.generateSampleButton);

        File docsDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        baseDir = docsDir != null ? new File(docsDir, "DrowsySessions") : null;

        String pathText = baseDir != null ? baseDir.getAbsolutePath() : "Storage unavailable";
        statusTextView.setText("Session folder:\n" + pathText);

        clearSessionsButton.setOnClickListener(v -> clearAllSessions());
        generateSampleButton.setOnClickListener(v -> {
            generateSampleSessions();
        });
    }

    private void clearAllSessions() {
        if (baseDir == null) {
            setStatus("Storage not available.");
            return;
        }

        if (!baseDir.exists()) {
            boolean ok = baseDir.mkdirs();
            if (!ok) {
                setStatus("Failed to create sessions folder: " + baseDir.getAbsolutePath());
                return;
            }
        }

        File[] children = baseDir.listFiles();
        if (children == null || children.length == 0) {
            setStatus("No sessions to clear.");
            return;
        }

        int deleted = 0;
        int failed = 0;

        for (File child : children) {
            if (deleteRecursively(child)) {
                deleted++;
            } else {
                failed++;
            }
        }

        // notify in-process listeners
        com.example.drowsinessdetection.SessionsNotifier.notifyUpdated();

        setStatus("Cleared sessions. Deleted: " + deleted + ", Failed: " + failed);
    }

    private void generateSampleSessions() {
        if (baseDir == null) {
            setStatus("Storage not available.");
            return;
        }
        if (!baseDir.exists()) baseDir.mkdirs();

        Random rnd = new Random();
        List<String> created = new ArrayList<>();
        long now = System.currentTimeMillis();
        long startDay = now - (13L * 24L * 60L * 60L * 1000L);
        int sessionIndex = 1;
        for (int d = 0; d < 14; d++) {
            long dayMs = startDay + d * 24L * 60L * 60L * 1000L;
            int sessionsToday = 1 + rnd.nextInt(3); // 1-3
            for (int s = 0; s < sessionsToday; s++) {
                long randomOffset = (6 + rnd.nextInt(17)) * 3600L * 1000L; // 6-22 hours
                randomOffset += rnd.nextInt(60) * 60L * 1000L;
                randomOffset += rnd.nextInt(60) * 1000L;
                long startMs = dayMs + randomOffset;
                int durationSec = 60 + rnd.nextInt(60 * 60); // 1m to 1h
                long endMs = startMs + durationSec * 1000L;

                int prolongedDrowsy = rnd.nextInt(7); // 0-6
                int prolongedYawn = rnd.nextInt(5); // 0-4
                int total = Math.max(1, (durationSec / 5) + rnd.nextInt(31) - 10);
                int awake = Math.max(0, total - (prolongedDrowsy + prolongedYawn) - rnd.nextInt(6));
                int drowsy = Math.max(0, prolongedDrowsy + rnd.nextInt(4));
                int yawning = Math.max(0, prolongedYawn + rnd.nextInt(3));

                double score = 100.0;
                // total guaranteed >=1 above
                score = 100.0 * (1.0 - ((double)(prolongedDrowsy + prolongedYawn) / (double)total));
                if (score < 0) score = 0.0;
                if (score > 100) score = 100.0;
                String sessionName = String.format(Locale.US, "session_%03d_%tY%<tm%<td_%<tH%<tM%<tS", sessionIndex, new java.util.Date(startMs));
                // per-session subdirectory (named like other sessions)
                File sessionDir = new File(baseDir, sessionName);
                if (!sessionDir.exists()) {
                    boolean ok = sessionDir.mkdirs();
                    if (!ok) {
                        // failed to create directory: continue
                    }
                }

                // write meta file
                File meta = new File(sessionDir, "session_meta.txt");
                StringBuilder metaSb = new StringBuilder();
                metaSb.append("start=").append(startMs).append('\n');
                metaSb.append("end=").append(endMs).append('\n');
                metaSb.append("prolongedDrowsy=").append(prolongedDrowsy).append('\n');
                metaSb.append("prolongedYawn=").append(prolongedYawn).append('\n');
                metaSb.append("awake=").append(awake).append('\n');
                metaSb.append("drowsy=").append(drowsy).append('\n');
                metaSb.append("yawning=").append(yawning).append('\n');
                metaSb.append("total=").append(total).append('\n');
                metaSb.append("score=").append(String.format(Locale.US, "%.2f", score)).append('\n');
                try (FileWriter fw = new FileWriter(meta, false)) {
                    fw.write(metaSb.toString());
                } catch (Exception e) {
                    e.printStackTrace();
                }

                // write a short session_log
                File log = new File(sessionDir, "session_log.txt");
                int events = Math.max(3, total / 20);
                StringBuilder logSb = new StringBuilder();
                for (int i = 0; i < events; i++) {
                    String label = new String[]{"Awake","Drowsy","Yawning"}[rnd.nextInt(3)];
                    double conf = 0.4 + rnd.nextDouble() * 0.59;
                    int cnt = i + 1;
                    long stepSec = Math.max(1, durationSec / Math.max(1, events));
                    long tsMs = startMs + ((long)i * stepSec) * 1000L;
                    java.util.Date dt = new java.util.Date(tsMs);
                    String ts = String.format(Locale.US, "%tT", dt);
                    logSb.append(String.format(Locale.US, "DETECT|%s|%.2f|%d|%s\n", label, conf, cnt, ts));
                }
                try (FileWriter fw = new FileWriter(log, false)) {
                    fw.write(logSb.toString());
                } catch (Exception e) {
                    e.printStackTrace();
                }

                created.add(sessionName);
                sessionIndex++;
            }
        }

        // notify other activities that sessions changed (in-process)
        com.example.drowsinessdetection.SessionsNotifier.notifyUpdated();
        // also send a system broadcast for any components listening via Intent
        try {
            Intent b = new Intent("com.example.drowsinessdetection.SESSIONS_UPDATED");
            sendBroadcast(b);
        } catch (Exception ignored) {}

        setStatus("Created " + created.size() + " sample sessions.");
    }

    private void setStatus(String message) {
        if (statusTextView != null) statusTextView.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) return true;

        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) return false;
                }
            }
        }
        return file.delete();
    }

}
