// MainActivity: app home screen showing connection status, score and quick metrics
package com.example.drowsinessdetection;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Calendar;
import java.util.Set;
import java.util.UUID;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private InputStream inputStream;
    private OutputStream outputStream;
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 42;
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private TextView connectionText;
    private ImageView connectionIcon;
    private ImageButton startDriveButton;
    private TextView numScore;

    // UI fields for the new metrics (added)
    private TextView totalHoursYearValue, hoursWeekValue, alertsYearValue, numScoreTv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        connectionText = findViewById(R.id.connectionText);
        connectionIcon = findViewById(R.id.connectionIcon);
        startDriveButton = findViewById(R.id.btn3); // reuse stats button to start drive
        numScore = findViewById(R.id.numScore);

        // Taskbar buttons
        ImageButton btn1 = findViewById(R.id.btn1); // home
        ImageButton btn2 = findViewById(R.id.btn2); // list
        ImageButton btn3 = findViewById(R.id.btn3); // drive
        ImageButton btn4 = findViewById(R.id.btn4); // info

        btn1.setOnClickListener(v -> { /* already home */ });

        btn2.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SessionListActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
        });

        btn3.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, BluetoothTestActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
        });

        btn4.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, InfoActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
        });

        // connection banner tap to connect
        findViewById(R.id.statusConnection).setOnClickListener(v -> connectIfNeeded());
        // start drive button tap
        findViewById(R.id.startDriveBtn).setOnClickListener(v -> launchDrive());
        findViewById(R.id.startDriveText).setOnClickListener(v -> launchDrive());

        updateConnectionUi(BluetoothConnectionHolder.getInstance().isConnected());
        updateWeeklyScore();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateWeeklyScore();
    }

    private void launchDrive() {
        if (!BluetoothConnectionHolder.getInstance().isConnected()) {
            Toast.makeText(this, "Connect to Pi first", Toast.LENGTH_SHORT).show();
            connectIfNeeded();
            return;
        }
        Intent intent = new Intent(MainActivity.this, DriveActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(intent);
    }

    private void connectIfNeeded() {
        if (BluetoothConnectionHolder.getInstance().isConnected()) {
            Toast.makeText(this, "Already connected", Toast.LENGTH_SHORT).show();
            updateConnectionUi(true);
            return;
        }
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_LONG).show();
            return;
        }
        if (!checkBluetoothPermissions()) return;
        scanAndConnect();
    }

    private boolean checkBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            String[] permissions = { Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT };
            for (String permission : permissions) {
                if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, permissions, REQUEST_BLUETOOTH_PERMISSIONS);
                    return false;
                }
            }
        } else {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_BLUETOOTH_PERMISSIONS);
                return false;
            }
        }
        return true;
    }

    private void scanAndConnect() {
        if (!bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Enable Bluetooth", Toast.LENGTH_SHORT).show();
            return;
        }
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        BluetoothDevice raspberryPi = null;
        for (BluetoothDevice device : pairedDevices) {
            String name = device.getName();
            if (name != null && (name.contains("raspberrypi") || name.contains("RaspberryPi") || name.contains("RPi-BLE"))) {
                raspberryPi = device;
                break;
            }
        }
        if (raspberryPi == null) {
            Toast.makeText(this, "Pi not paired", Toast.LENGTH_LONG).show();
            return;
        }
        connectToDevice(raspberryPi);
    }

    @SuppressLint("MissingPermission")
    private void connectToDevice(BluetoothDevice device) {
        new Thread(() -> {
            try {
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                bluetoothAdapter.cancelDiscovery();
                if (bluetoothSocket == null) throw new IOException("Socket null");
                bluetoothSocket.connect();
                inputStream = bluetoothSocket.getInputStream();
                outputStream = bluetoothSocket.getOutputStream();
                BluetoothConnectionHolder.getInstance().setConnection(bluetoothSocket, inputStream, outputStream);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Connected to Pi", Toast.LENGTH_SHORT).show();
                    updateConnectionUi(true);
                });
            } catch (IOException primaryError) {
                try {
                    bluetoothSocket = (BluetoothSocket) device.getClass().getMethod("createRfcommSocket", int.class).invoke(device, 1);
                    bluetoothAdapter.cancelDiscovery();
                    bluetoothSocket.connect();
                    inputStream = bluetoothSocket.getInputStream();
                    outputStream = bluetoothSocket.getOutputStream();
                    BluetoothConnectionHolder.getInstance().setConnection(bluetoothSocket, inputStream, outputStream);
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Connected via fallback", Toast.LENGTH_SHORT).show();
                        updateConnectionUi(true);
                    });
                } catch (Exception fallback) {
                    runOnUiThread(() -> Toast.makeText(this, "Connect failed: " + primaryError.getMessage(), Toast.LENGTH_LONG).show());
                }
            }
        }).start();
    }

    private void updateConnectionUi(boolean connected) {
        connectionIcon.setImageResource(R.drawable.no_signal_icon);
        if (connected) {
            connectionIcon.setColorFilter(Color.GREEN);
        } else {
            connectionIcon.setColorFilter(Color.RED);
        }
    }

    private void updateWeeklyScore() {
        // lazy bind views (in case onCreate wasn't updated)
        if (numScoreTv == null) {
            numScoreTv = findViewById(R.id.numScore);
        }
        if (totalHoursYearValue == null) totalHoursYearValue = findViewById(R.id.totalHoursYearValue);
        if (hoursWeekValue == null) hoursWeekValue = findViewById(R.id.hoursWeekValue);
        if (alertsYearValue == null) alertsYearValue = findViewById(R.id.alertsYearValue);

        double weekAvg = computeCurrentWeekAverageScore();
        if (weekAvg >= 0) {
            numScoreTv.setText(String.format(Locale.US, "%.0f", weekAvg));
        } else {
            numScoreTv.setText(getString(R.string.num_default));
        }

        // compute hours this year and this week and alerts this year
        Calendar now = Calendar.getInstance();
        int currentYear = now.get(Calendar.YEAR);
        long totalSecondsYear = computeTotalSecondsForYear(currentYear);
        long totalSecondsWeek = computeTotalSecondsForWeek(now.get(Calendar.WEEK_OF_YEAR), currentYear);

        double hoursYear = totalSecondsYear / 3600.0;
        double hoursWeek = totalSecondsWeek / 3600.0;

        totalHoursYearValue.setText(String.format(Locale.US, "%.1fh", hoursYear));
        hoursWeekValue.setText(String.format(Locale.US, "%.1fh", hoursWeek));

        int alertsYear = computeAlertsForYear(currentYear);
        alertsYearValue.setText(String.format(Locale.US, "%d", alertsYear));
    }

    // compute total seconds of session usage for the given calendar year
    private long computeTotalSecondsForYear(int year) {
        File docs = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        File root = docs != null ? new File(docs, "DrowsySessions") : null;
        if (root == null || !root.exists()) return 0L;

        long sumSeconds = 0L;
        File[] children = root.listFiles();
        if (children == null) return 0L;

        for (File f : children) {
            if (!f.isDirectory() || !f.getName().startsWith("session_")) continue;
            File meta = new File(f, "session_meta.txt");
            if (!meta.exists()) continue;
            try {
                String s = readFileCompat(meta);
                long start = 0L, end = 0L;
                for (String p : s.split("\\n")) {
                    if (p.startsWith("start=")) start = safeLong(p.substring(6));
                    if (p.startsWith("end=")) end = safeLong(p.substring(4));
                }
                if (start <= 0) continue;
                if (end <= start) end = System.currentTimeMillis();
                Calendar cal = Calendar.getInstance();
                cal.setTimeInMillis(start);
                int sy = cal.get(Calendar.YEAR);
                if (sy != year) continue;
                long durationSec = (end - start) / 1000L;
                if (durationSec > 0) sumSeconds += durationSec;
            } catch (Exception ignored) { }
        }
        return sumSeconds;
    }

    // compute total seconds for a specific week number and year
    private long computeTotalSecondsForWeek(int weekOfYear, int year) {
        File docs = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        File root = docs != null ? new File(docs, "DrowsySessions") : null;
        if (root == null || !root.exists()) return 0L;

        long sumSeconds = 0L;
        File[] children = root.listFiles();
        if (children == null) return 0L;

        for (File f : children) {
            if (!f.isDirectory() || !f.getName().startsWith("session_")) continue;
            File meta = new File(f, "session_meta.txt");
            if (!meta.exists()) continue;
            try {
                String s = readFileCompat(meta);
                long start = 0L, end = 0L;
                for (String p : s.split("\\n")) {
                    if (p.startsWith("start=")) start = safeLong(p.substring(6));
                    if (p.startsWith("end=")) end = safeLong(p.substring(4));
                }
                if (start <= 0) continue;
                if (end <= start) end = System.currentTimeMillis();
                Calendar cal = Calendar.getInstance();
                cal.setTimeInMillis(start);
                int sy = cal.get(Calendar.YEAR);
                int sw = cal.get(Calendar.WEEK_OF_YEAR);
                if (sy != year || sw != weekOfYear) continue;
                long durationSec = (end - start) / 1000L;
                if (durationSec > 0) sumSeconds += durationSec;
            } catch (Exception ignored) { }
        }
        return sumSeconds;
    }

    // compute alerts (prolonged drowsy + prolonged yawn) for the given year
    private int computeAlertsForYear(int year) {
        File docs = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        File root = docs != null ? new File(docs, "DrowsySessions") : null;
        if (root == null || !root.exists()) return 0;

        int sumAlerts = 0;
        File[] children = root.listFiles();
        if (children == null) return 0;

        for (File f : children) {
            if (!f.isDirectory() || !f.getName().startsWith("session_")) continue;
            File meta = new File(f, "session_meta.txt");
            if (!meta.exists()) continue;
            try {
                String s = readFileCompat(meta);
                long start = 0L;
                int pd = 0, py = 0;
                for (String p : s.split("\\n")) {
                    if (p.startsWith("start=")) start = safeLong(p.substring(6));
                    if (p.startsWith("prolongedDrowsy=")) pd = safeInt(p.substring("prolongedDrowsy=".length()));
                    if (p.startsWith("prolongedYawn=")) py = safeInt(p.substring("prolongedYawn=".length()));
                }
                if (start <= 0) continue;
                Calendar cal = Calendar.getInstance();
                cal.setTimeInMillis(start);
                int sy = cal.get(Calendar.YEAR);
                if (sy != year) continue;
                sumAlerts += (pd + py);
            } catch (Exception ignored) { }
        }
        return sumAlerts;
    }

    private double computeCurrentWeekAverageScore() {
        File docs = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        File root = docs != null ? new File(docs, "DrowsySessions") : null;
        if (root == null || !root.exists()) return -1;

        Calendar now = Calendar.getInstance();
        int currentWeek = now.get(Calendar.WEEK_OF_YEAR);
        int currentYear = now.get(Calendar.YEAR);

        double sum = 0;
        int count = 0;

        File[] children = root.listFiles();
        if (children == null) return -1;

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
                Calendar cal = Calendar.getInstance();
                cal.setTimeInMillis(start);
                int week = cal.get(Calendar.WEEK_OF_YEAR);
                int year = cal.get(Calendar.YEAR);
                if (week != currentWeek || year != currentYear) continue;

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
                if (score >= 0) {
                    sum += score;
                    count++;
                }
            } catch (Exception ignored) { }
        }

        if (count == 0) return -1;
        return sum / count;
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

    @Override
    protected void onStart() {
        super.onStart();
        updateConnectionUi(BluetoothConnectionHolder.getInstance().isConnected());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            boolean granted = true;
            for (int r : grantResults) if (r != PackageManager.PERMISSION_GRANTED) granted = false;
            if (granted) scanAndConnect();
        }
    }
}

