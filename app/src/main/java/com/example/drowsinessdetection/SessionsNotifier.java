// SessionsNotifier: lightweight registry for components to subscribe and be notified when session data changes
package com.example.drowsinessdetection;

import java.util.concurrent.CopyOnWriteArrayList;

public class SessionsNotifier {
    public interface Listener {
        void onSessionsUpdated();
    }

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    public static void register(Listener l) {
        if (l == null) return;
        listeners.addIfAbsent(l);
    }

    public static void unregister(Listener l) {
        if (l == null) return;
        listeners.remove(l);
    }

    public static void notifyUpdated() {
        for (Listener l : listeners) {
            try {
                l.onSessionsUpdated();
            } catch (Exception ignored) {}
        }
    }
}
