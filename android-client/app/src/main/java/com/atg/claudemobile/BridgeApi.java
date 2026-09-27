package com.atg.claudemobile;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class BridgeApi {
    private final String baseUrl;
    private final String token;

    BridgeApi(String baseUrl, String token) {
        this.baseUrl = trimSlash(baseUrl);
        this.token = token;
    }

    JSONObject get(String path) throws Exception {
        return request("GET", path, null);
    }

    JSONObject post(String path, JSONObject body) throws Exception {
        return request("POST", path, body == null ? new JSONObject() : body);
    }

    JSONObject patch(String path, JSONObject body) throws Exception {
        return request("PATCH", path, body == null ? new JSONObject() : body);
    }

    JSONObject delete(String path) throws Exception {
        return request("DELETE", path, null);
    }

    private JSONObject request(String method, String path, JSONObject body) throws Exception {
        URL url = new URL(baseUrl + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(12_000);
        conn.setReadTimeout(35_000);
        conn.setUseCaches(false);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("X-Tunnel-Skip-AntiPhishing-Page", "1");
        conn.setRequestProperty("User-Agent", "ClaudeCodeRemote-Android/0.1");

        if (body != null) {
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
            }
        }

        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String text = readAll(stream);
        conn.disconnect();

        JSONObject parsed;
        try {
            parsed = text == null || text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
        } catch (Exception ex) {
            throw new IllegalStateException("Bridge 返回非 JSON 响应 (HTTP " + code + ")");
        }
        if (code < 200 || code >= 300) {
            String error = parsed.optString("error", "HTTP " + code);
            throw new IllegalStateException(error);
        }
        return parsed;
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    private static String trimSlash(String value) {
        String out = value == null ? "" : value.trim();
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }
}
