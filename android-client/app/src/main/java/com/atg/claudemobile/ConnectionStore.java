package com.atg.claudemobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

final class ConnectionStore {
    private static final String PREFS = "claude_mobile";
    private static final String KEY_BASE = "base_url";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_SESSION = "session_id";
    private static final String DEFAULT_BASE = "https://5w9x2l1v-8787.asse.devtunnels.ms";

    private final SharedPreferences prefs;

    ConnectionStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    String baseUrl() { return prefs.getString(KEY_BASE, DEFAULT_BASE); }
    String token() { return prefs.getString(KEY_TOKEN, ""); }
    String sessionId() { return prefs.getString(KEY_SESSION, null); }
    boolean isPaired() { return token() != null && token().length() >= 24; }

    void setSessionId(String id) { prefs.edit().putString(KEY_SESSION, id).apply(); }
    void clearSessionId() { prefs.edit().remove(KEY_SESSION).apply(); }

    void save(String baseUrl, String token) {
        prefs.edit().putString(KEY_BASE, trim(baseUrl)).putString(KEY_TOKEN, token).apply();
    }

    void clear() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_SESSION).apply();
    }

    boolean acceptPairing(Uri uri) {
        if (uri == null || !"claudecode".equalsIgnoreCase(uri.getScheme()) || !"connect".equalsIgnoreCase(uri.getHost())) return false;
        String base = uri.getQueryParameter("url");
        String secret = uri.getQueryParameter("token");
        if (base == null || secret == null || !base.startsWith("https://") || secret.length() < 24) return false;
        save(base, secret);
        return true;
    }

    private static String trim(String value) {
        String out = value == null ? "" : value.trim();
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }
}
