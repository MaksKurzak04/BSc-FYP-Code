// BluetoothTestActivity: shows historical drowsiness scores in a bar chart with range toggle
package com.example.drowsinessdetection;

import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.google.android.material.button.MaterialButtonToggleGroup;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public class BluetoothTestActivity extends AppCompatActivity {
    private BarChart barChart;
    private MaterialButtonToggleGroup rangeToggle;
    private TextView emptyView;
    private File sessionRootDir;
    private com.example.drowsinessdetection.SessionsNotifier.Listener sessionsListener;

    private enum Range { DAILY, WEEKLY, MONTHLY }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bluetooth_test);

        barChart = findViewById(R.id.barChart);
        rangeToggle = findViewById(R.id.rangeToggle);
        emptyView = findViewById(R.id.emptyView);

        File docs = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        sessionRootDir = docs != null ? new File(docs, "DrowsySessions") : null;

        setupBottomNavigation();
        setupChartAppearance();
        setupToggle();
        renderChart(Range.DAILY);

        sessionsListener = new com.example.drowsinessdetection.SessionsNotifier.Listener() {
            @Override
            public void onSessionsUpdated() {
                runOnUiThread(() -> renderChart(getSelectedRange()));
            }
        };
    }

    private Range getSelectedRange() {
        int checked = rangeToggle.getCheckedButtonId();
        if (checked == R.id.btnWeekly) return Range.WEEKLY;
        if (checked == R.id.btnMonthly) return Range.MONTHLY;
        return Range.DAILY;
    }

    private void setupBottomNavigation() {
        ImageButton btn1 = findViewById(R.id.btn1);
        ImageButton btn2 = findViewById(R.id.btn2);
        ImageButton btn3 = findViewById(R.id.btn3);
        ImageButton btn4 = findViewById(R.id.btn4);

        btn1.setOnClickListener(v -> {
            Intent intent = new Intent(BluetoothTestActivity.this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
        });

        btn2.setOnClickListener(v -> {
            Intent intent = new Intent(BluetoothTestActivity.this, SessionListActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
        });

        btn3.setOnClickListener(v -> { /* already here */ });

        btn4.setOnClickListener(v -> {
            Intent intent = new Intent(BluetoothTestActivity.this, InfoActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
        });
    }

    private void setupChartAppearance() {
        barChart.getDescription().setEnabled(false);
        barChart.setPinchZoom(false);
        barChart.setScaleEnabled(false);
        barChart.getLegend().setEnabled(false);

        XAxis xAxis = barChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setGranularity(1f);
        xAxis.setDrawGridLines(false);

        barChart.getAxisRight().setEnabled(false);
    }

    private void setupToggle() {
        rangeToggle.check(R.id.btnDaily);
        rangeToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.btnDaily) {
                renderChart(Range.DAILY);
            } else if (checkedId == R.id.btnWeekly) {
                renderChart(Range.WEEKLY);
            } else if (checkedId == R.id.btnMonthly) {
                renderChart(Range.MONTHLY);
            }
        });
    }

    private void renderChart(Range range) {
        List<SessionMeta> sessions = loadSessions();
        if (sessions.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            barChart.clear();
            barChart.invalidate();
            return;
        }

        emptyView.setVisibility(View.GONE);

        TreeMap<Long, Bucket> buckets = new TreeMap<>();
        for (SessionMeta s : sessions) {
            if (s.startMillis <= 0 || s.score < 0) continue;
            Bucket b = bucketFor(s.startMillis, range);
            Bucket existing = buckets.get(b.keyMillis);
            if (existing == null) {
                b.sumScore = s.score;
                b.sessionCount = 1;
                buckets.put(b.keyMillis, b);
            } else {
                existing.sumScore += s.score;
                existing.sessionCount++;
            }
        }

        List<BarEntry> entries = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        int index = 0;
        for (Map.Entry<Long, Bucket> e : buckets.entrySet()) {
            Bucket b = e.getValue();
            if (b.sessionCount == 0) continue;
            float avg = (float)(b.sumScore / b.sessionCount);
            entries.add(new BarEntry(index, avg));
            labels.add(b.label);
            index++;
        }

        BarDataSet dataSet = new BarDataSet(entries, "Avg Drowsiness Score");
        dataSet.setColor(0xFF4CAF50); // green bars
        dataSet.setValueTextSize(10f);

        BarData data = new BarData(dataSet);
        data.setBarWidth(0.7f);

        barChart.setData(data);
        barChart.getXAxis().setValueFormatter(new IndexAxisValueFormatter(labels));
        barChart.getXAxis().setLabelRotationAngle(-35f);
        barChart.getAxisLeft().setAxisMinimum(0f);
        barChart.getAxisLeft().setAxisMaximum(100f);
        barChart.invalidate();
    }

    private Bucket bucketFor(long millis, Range range) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(millis);
        cal.set(Calendar.MILLISECOND, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.HOUR_OF_DAY, 0);

        String label;
        long key;
        switch (range) {
            case WEEKLY:
                int week = cal.get(Calendar.WEEK_OF_YEAR);
                int year = cal.get(Calendar.YEAR);
                cal.set(Calendar.DAY_OF_WEEK, cal.getFirstDayOfWeek());
                key = cal.getTimeInMillis();
                label = String.format(Locale.US, "W%d '%02d", week, year % 100);
                break;
            case MONTHLY:
                cal.set(Calendar.DAY_OF_MONTH, 1);
                key = cal.getTimeInMillis();
                label = new SimpleDateFormat("MMM yy", Locale.US).format(cal.getTime());
                break;
            case DAILY:
            default:
                key = cal.getTimeInMillis();
                label = new SimpleDateFormat("MMM d", Locale.US).format(cal.getTime());
                break;
        }
        return new Bucket(key, label, 0);
    }

    private List<SessionMeta> loadSessions() {
        List<SessionMeta> list = new ArrayList<>();
        if (sessionRootDir == null || !sessionRootDir.exists()) return list;

        File[] children = sessionRootDir.listFiles();
        if (children == null) return list;

        for (File f : children) {
            if (!f.isDirectory() || !f.getName().startsWith("session_")) continue;
            File meta = new File(f, "session_meta.txt");
            if (!meta.exists()) continue;
            try {
                String s = readFileCompat(meta);
                long start = 0;
                int drowsy = 0;
                int yawn = 0;
                int pd = 0;
                int py = 0;
                int total = 0;
                int awake = 0;
                double score = -1;
                for (String p : s.split("\\n")) {
                    if (p.startsWith("start=")) start = safeLong(p.substring(6));
                    if (p.startsWith("drowsy=")) drowsy = safeInt(p.substring(7));
                    if (p.startsWith("yawning=")) yawn = safeInt(p.substring(8));
                    if (p.startsWith("prolongedDrowsy=")) pd = safeInt(p.substring("prolongedDrowsy=".length()));
                    if (p.startsWith("prolongedYawn=")) py = safeInt(p.substring("prolongedYawn=".length()));
                    if (p.startsWith("total=")) total = safeInt(p.substring(6));
                    if (p.startsWith("awake=")) awake = safeInt(p.substring(6));
                    if (p.startsWith("score=")) {
                        try { score = Double.parseDouble(p.substring(6).trim()); } catch (Exception ignored) {}
                    }
                }
                if (score < 0) {
                    int events = pd + py;
                    if (events == 0) events = drowsy + yawn;
                    if (events == 0 && total > 0) {
                        int derived = total - awake;
                        if (derived < 0) derived = total;
                        events = derived;
                    }
                    if (total > 0) {
                        double raw = 100.0 * (1.0 - ((double) events / (double) total));
                        if (raw < 0) raw = 0; if (raw > 100) raw = 100;
                        score = raw;
                    }
                }
                if (score >= 0 && start > 0) {
                    list.add(new SessionMeta(start, score));
                }
            } catch (Exception ignored) { }
        }
        return list;
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

    private int safeInt(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; } }
    private long safeLong(String s) { try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0L; } }

    private static class SessionMeta {
        final long startMillis;
        final double score; // 0-100, higher means better/less drowsy
        SessionMeta(long startMillis, double score) {
            this.startMillis = startMillis;
            this.score = score;
        }
    }

    private static class Bucket {
        final long keyMillis;
        final String label;
        double sumScore;
        int sessionCount;
        Bucket(long keyMillis, String label, int count) {
            this.keyMillis = keyMillis;
            this.label = label;
            this.sumScore = 0;
            this.sessionCount = 0;
        }
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
}
