// BluetoothConnectionHolder: manages a global BLE socket, reader thread and dispatches text lines to listeners
package com.example.drowsinessdetection;

import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class BluetoothConnectionHolder {
    public interface MessageListener {
        void onLine(String line);
    }

    private static BluetoothConnectionHolder instance;
    private BluetoothSocket socket;
    private InputStream inputStream;
    private OutputStream outputStream;
    private final List<MessageListener> listeners = new ArrayList<>();
    private Thread readerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private BluetoothConnectionHolder() {}

    public static synchronized BluetoothConnectionHolder getInstance() {
        if (instance == null) instance = new BluetoothConnectionHolder();
        return instance;
    }

    public synchronized void setConnection(BluetoothSocket sock, InputStream in, OutputStream out) {
        clearConnection();
        this.socket = sock;
        this.inputStream = in;
        this.outputStream = out;
        startReader();
    }

    public synchronized void clearConnection() {
        stopReader();
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
        }
        socket = null;
        inputStream = null;
        outputStream = null;
    }

    public synchronized boolean isConnected() {
        return socket != null && socket.isConnected();
    }

    public synchronized InputStream getInputStream() { return inputStream; }
    public synchronized OutputStream getOutputStream() { return outputStream; }

    public synchronized void registerListener(MessageListener l) {
        if (l == null) return;
        listeners.add(l);
    }

    public synchronized void unregisterListener(MessageListener l) {
        if (l == null) return;
        Iterator<MessageListener> it = listeners.iterator();
        while (it.hasNext()) {
            MessageListener ml = it.next();
            if (ml == l) it.remove();
        }
    }

    private void startReader() {
        if (running.get()) return;
        if (inputStream == null) return;
        running.set(true);

        final InputStream in = inputStream;
        readerThread = new Thread(() -> {
            try {
                while (running.get()) {
                    String line = readLine(in);
                    if (line == null) break;
                    if (line.isEmpty()) continue;
                    final String l = line;
                    mainHandler.post(() -> dispatchLine(l));
                }
            } catch (IOException e) {
                Log.w("BluetoothHolder", "Reader stopped: " + e.getMessage());
            } finally {
                running.set(false);
            }
        }, "BT-Reader");
        readerThread.start();
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64);
        while (running.get()) {
            int b = in.read();
            if (b == -1) {
                if (bos.size() == 0) return null;
                break;
            }
            if (b == '\n') break;
            if (b != '\r') bos.write(b);
        }
        return bos.toString(StandardCharsets.UTF_8.name());
    }

    private void stopReader() {
        running.set(false);
        if (readerThread != null) {
            try { readerThread.interrupt(); } catch (Exception ignored) {}
            readerThread = null;
        }
    }

    private void dispatchLine(String line) {
        synchronized (this) {
            for (MessageListener l : listeners) {
                try { l.onLine(line); } catch (Exception e) { Log.w("BluetoothHolder", "Listener error", e); }
            }
        }
    }
}
