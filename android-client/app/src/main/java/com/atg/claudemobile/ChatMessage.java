package com.atg.claudemobile;

final class ChatMessage {
    final String role;
    String text;
    final String model;
    final String kind;
    final String title;
    boolean expanded;

    ChatMessage(String role, String text, String model) {
        this(role, text, model, "message", "");
    }

    ChatMessage(String role, String text, String model, String kind, String title) {
        this.role = role == null ? "assistant" : role;
        this.text = text == null ? "" : text;
        this.model = model == null ? "" : model;
        this.kind = kind == null ? "message" : kind;
        this.title = title == null ? "" : title;
        this.expanded = false;
    }

    boolean isProcessCard() {
        return "thinking".equals(kind) || "tool".equals(kind) || "tool_result".equals(kind);
    }
}
