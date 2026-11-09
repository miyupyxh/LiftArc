package com.lockbar.app.xposed;

import java.util.ArrayList;
import java.util.List;

/** Test-only replacement: captures diagnostics without Android, files, or Xposed. */
final class ModuleLog {
    static final class Entry {
        final String message;
        final Throwable error;

        Entry(String message, Throwable error) {
            this.message = message;
            this.error = error;
        }
    }

    static final List<Entry> DEBUG = new ArrayList<>();
    static final List<Entry> WARNINGS = new ArrayList<>();

    private ModuleLog() { }

    static void d(String message) { DEBUG.add(new Entry(message, null)); }
    static void w(String message, Throwable error) { WARNINGS.add(new Entry(message, error)); }

    static void reset() {
        DEBUG.clear();
        WARNINGS.clear();
    }
}
