// SessionListActivity: lists saved drive sessions with brief metadata per session
package com.example.drowsinessdetection;

import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

public class SessionListActivity extends AppCompatActivity {

    private ListView sessionsListView;
    private TextView emptyView;
    private com.example.drowsinessdetection.SessionsNotifier.Listener sessionsListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_session_list);

        sessionsListView = findViewById(R.id.sessionsListView);
        emptyView = findViewById(R.id.emptyView);
        // prepare in-process listener (registered in onStart)
        sessionsListener = new com.example.drowsinessdetection.SessionsNotifier.Listener() {
            @Override
            public void onSessionsUpdated() {
                runOnUiThread(() -> loadSessions());
            }
        };
        // Setup taskbar navigation
        ImageButton btn1 = findViewById(R.id.btn1);
        ImageButton btn2 = findViewById(R.id.btn2);
        ImageButton btn3 = findViewById(R.id.btn3);
        ImageButton btn4 = findViewById(R.id.btn4);

        btn1.setOnClickListener(v -> {
            Intent intent = new Intent(SessionListActivity.this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
        });

        btn2.setOnClickListener(v -> {
            // already here
        });

        btn3.setOnClickListener(v -> {
            Intent intent = new Intent(SessionListActivity.this, BluetoothTestActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
        });

        btn4.setOnClickListener(v -> {
            Intent intent = new Intent(SessionListActivity.this, InfoActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
        });

        loadSessions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSessions();
    }

    @Override
    protected void onStart() {
        super.onStart();
        com.example.drowsinessdetection.SessionsNotifier.register(sessionsListener);
    }

    @Override
    protected void onStop() {
        super.onStop();
        com.example.drowsinessdetection.SessionsNotifier.unregister(sessionsListener);
    }

    private void loadSessions() {
        File sessionRoot = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "DrowsySessions");
        ArrayList<android.util.Pair<String,String>> rows = new ArrayList<>();

        if (sessionRoot.exists() && sessionRoot.isDirectory()) {
            File[] children = sessionRoot.listFiles();
            if (children != null) {
                for (File f : children) {
                    if (f.isDirectory() && f.getName().startsWith("session_")) {
                        String[] parts = f.getName().split("_");
                        int idx = 0;
                        String line1 = f.getName();
                        String line2 = "(no metadata)";

                        if (parts.length >= 2) {
                            try { idx = Integer.parseInt(parts[1]); } catch (Exception ignored) {}
                        }

                        File meta = new File(f, "session_meta.txt");
                        double scoreVal = -1;
                        if (meta.exists()) {
                            try {
                                String s = readFileCompat(meta);
                                long start = 0, end = 0; int pd=0, py=0, a=0, total=0;
                                for (String p : s.split("\\n")) {
                                    if (p.startsWith("start=")) start = Long.parseLong(p.substring(6));
                                    if (p.startsWith("end=")) end = Long.parseLong(p.substring(4));
                                    if (p.startsWith("prolongedDrowsy=")) pd = Integer.parseInt(p.substring("prolongedDrowsy=".length()));
                                    if (p.startsWith("prolongedYawn=")) py = Integer.parseInt(p.substring("prolongedYawn=".length()));
                                    if (p.startsWith("awake=")) a = Integer.parseInt(p.substring(6));
                                    if (p.startsWith("total=")) total = Integer.parseInt(p.substring(6));
                                    if (p.startsWith("score=")) scoreVal = Double.parseDouble(p.substring(6));
                                }
                                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd/MM/yy HH:mm", Locale.US);
                                String startText = start > 0 ? sdf.format(new java.util.Date(start)) : "?";
                                long duration = (end > start && end > 0) ? (end - start) / 1000 : 0;
                                line1 = String.format(Locale.US, "%d", idx);
                                String scoreText = scoreVal >= 0 ? String.format(Locale.US, " • Score: %.0f%%", scoreVal) : "";
                                line2 = startText + " — " + duration + "s • PD:" + pd + " PY:" + py + " A:" + a + " T:" + total + scoreText;
                            } catch (Exception ignored) {
                                line1 = String.format(Locale.US, "%d", idx);
                                line2 = "(meta unreadable)";
                            }
                        } else {
                            line1 = String.format(Locale.US, "%d", idx);
                            line2 = "(no metadata)";
                        }

                        rows.add(new android.util.Pair<>(line1, line2));
                    }
                }
            }
        }

        if (rows.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            sessionsListView.setVisibility(View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
            sessionsListView.setVisibility(View.VISIBLE);

            ArrayList<java.util.Map<String,String>> data = new ArrayList<>();
            for (android.util.Pair<String,String> p : rows) {
                java.util.Map<String,String> map = new java.util.HashMap<>();
                map.put("line1", p.first);
                map.put("line2", p.second);
                data.add(map);
            }

            android.widget.SimpleAdapter adapter = new android.widget.SimpleAdapter(
                    this,
                    data,
                    android.R.layout.simple_list_item_2,
                    new String[]{"line1","line2"},
                    new int[]{android.R.id.text1, android.R.id.text2}
            );

            sessionsListView.setAdapter(adapter);
        }
    }

    private String readFileCompat(File file) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        FileInputStream fis = new FileInputStream(file);
        byte[] buf = new byte[4096];
        int read;
        while ((read = fis.read(buf)) != -1) {
            bos.write(buf, 0, read);
        }
        fis.close();
        return bos.toString();
    }
}
