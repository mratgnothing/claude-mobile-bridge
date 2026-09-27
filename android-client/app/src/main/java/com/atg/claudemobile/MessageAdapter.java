package com.atg.claudemobile;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import io.noties.markwon.Markwon;
import io.noties.markwon.ext.latex.JLatexMathPlugin;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;

final class MessageAdapter extends BaseAdapter {
    private final Context context;
    private final List<ChatMessage> messages;
    private final float density;
    private final Markwon markwon;

    MessageAdapter(Context context, List<ChatMessage> messages) {
        this.context = context;
        this.messages = messages;
        this.density = context.getResources().getDisplayMetrics().density;
        float markdownTextPx = 15f * context.getResources().getDisplayMetrics().scaledDensity;
        this.markwon = Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(JLatexMathPlugin.create(markdownTextPx))
            .build();
    }

    @Override public int getCount() { return messages.size(); }
    @Override public Object getItem(int position) { return messages.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        ChatMessage m = messages.get(position);
        try {
            return buildMessageView(m);
        } catch (Throwable error) {
            LinearLayout fallback = new LinearLayout(context);
            fallback.setOrientation(LinearLayout.VERTICAL);
            fallback.setPadding(dp(10), dp(8), dp(10), dp(8));
            TextView warning = new TextView(context);
            warning.setText("这条消息的富文本渲染失败，已回退为纯文本。");
            warning.setTextSize(11);
            warning.setTextColor(Color.rgb(220, 170, 120));
            fallback.addView(warning);
            TextView plain = markdownView(14);
            plain.setMovementMethod(null);
            plain.setText(m.text == null ? "" : m.text);
            plain.setPadding(0, dp(5), 0, 0);
            fallback.addView(plain, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return fallback;
        }
    }

    private View buildMessageView(ChatMessage m) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity("user".equals(m.role) ? Gravity.END : Gravity.START);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));

        if (m.isProcessCard()) {
            row.addView(processCard(m), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return row;
        }

        View bubble;
        if ("user".equals(m.role)) {
            TextView user = markdownView(15);
            user.setText(m.text);
            user.setPadding(dp(13), dp(10), dp(13), dp(10));
            user.setBackground(round(Color.rgb(40, 45, 55), 15));
            user.setMaxWidth(dp(330));
            bubble = user;
        } else {
            bubble = markdownContent(m.text == null ? "" : m.text, 15);
            bubble.setPadding(dp(13), dp(8), dp(13), dp(8));
        }

        int bubbleWidth = "user".equals(m.role)
            ? ViewGroup.LayoutParams.WRAP_CONTENT
            : ViewGroup.LayoutParams.MATCH_PARENT;
        row.addView(bubble, new LinearLayout.LayoutParams(bubbleWidth, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (m.model != null && !m.model.trim().isEmpty() && "assistant".equals(m.role)) {
            TextView model = new TextView(context);
            model.setText(m.model);
            model.setTextColor(Color.rgb(119, 128, 143));
            model.setTextSize(10);
            model.setPadding(dp(13), dp(2), 0, 0);
            row.addView(model);
        }
        return row;
    }

    private View processCard(ChatMessage m) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(8), dp(12), dp(9));
        int background;
        int border;
        int headColor;
        if ("thinking".equals(m.kind)) {
            background = Color.rgb(28, 32, 39);
            border = Color.rgb(67, 75, 88);
            headColor = Color.rgb(176, 187, 204);
        } else if ("tool_result".equals(m.kind)) {
            background = Color.rgb(24, 35, 32);
            border = Color.rgb(55, 87, 76);
            headColor = Color.rgb(146, 202, 181);
        } else {
            background = Color.rgb(31, 31, 38);
            border = Color.rgb(82, 72, 91);
            headColor = Color.rgb(197, 174, 210);
        }
        GradientDrawable bg = round(background, 11);
        bg.setStroke(dp(1), border);
        card.setBackground(bg);

        TextView head = new TextView(context);
        String arrow = m.expanded ? "▾ " : "▸ ";
        String fallback = "thinking".equals(m.kind) ? "思考" : ("tool_result".equals(m.kind) ? "执行结果" : "执行");
        head.setText(arrow + (m.title.trim().isEmpty() ? fallback : m.title));
        head.setTextColor(headColor);
        head.setTextSize(12);
        head.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        head.setPadding(0, 0, 0, dp(5));
        card.addView(head);

        TextView body = markdownView("thinking".equals(m.kind) ? 13 : 12);
        body.setTextColor(Color.rgb(210, 215, 224));
        body.setLineSpacing(0, 1.05f);
        markwon.setMarkdown(body, m.text == null ? "" : m.text);
        if (!m.expanded) {
            body.setMaxLines("tool_result".equals(m.kind) ? 8 : 6);
            body.setEllipsize(TextUtils.TruncateAt.END);
        }
        card.addView(body, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.setOnClickListener(v -> {
            m.expanded = !m.expanded;
            notifyDataSetChanged();
        });
        return card;
    }

    /**
     * Markwon already handles ordinary Markdown.  Tables get a dedicated native
     * renderer as well because TextView-based table spans are easy to lose on
     * some Android builds and then the raw pipe syntax is shown to the user.
     */
    private View markdownContent(String markdown, int sizeSp) {
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        StringBuilder prose = new StringBuilder();

        int i = 0;
        while (i < lines.length) {
            if (i + 1 < lines.length && looksLikeTableRow(lines[i]) && isTableSeparator(lines[i + 1])) {
                flushMarkdown(content, prose, sizeSp);
                List<List<String>> rows = new ArrayList<>();
                List<String> header = splitTableRow(lines[i]);
                rows.add(header);
                int columns = header.size();
                i += 2;
                while (i < lines.length && looksLikeTableRow(lines[i])) {
                    List<String> cells = splitTableRow(lines[i]);
                    if (cells.size() != columns) break;
                    rows.add(cells);
                    i++;
                }
                content.addView(tableView(rows, sizeSp), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                continue;
            }

            if (prose.length() > 0) prose.append('\n');
            prose.append(lines[i]);
            i++;
        }
        flushMarkdown(content, prose, sizeSp);

        if (content.getChildCount() == 1) {
            View only = content.getChildAt(0);
            content.removeViewAt(0);
            return only;
        }
        return content;
    }

    private void flushMarkdown(LinearLayout content, StringBuilder prose, int sizeSp) {
        if (prose.length() == 0) return;
        TextView text = markdownView(sizeSp);
        markwon.setMarkdown(text, prose.toString());
        content.addView(text, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        prose.setLength(0);
    }

    private View tableView(List<List<String>> rows, int sizeSp) {
        HorizontalScrollView scroller = new HorizontalScrollView(context);
        scroller.setHorizontalScrollBarEnabled(true);
        scroller.setFillViewport(false);
        scroller.setPadding(0, dp(7), 0, dp(8));

        TableLayout table = new TableLayout(context);
        table.setShrinkAllColumns(false);
        table.setStretchAllColumns(false);

        for (int r = 0; r < rows.size(); r++) {
            TableRow row = new TableRow(context);
            List<String> cells = rows.get(r);
            for (String value : cells) {
                TextView cell = markdownView(Math.max(12, sizeSp - 1));
                cell.setMinWidth(dp(104));
                cell.setMaxWidth(dp(260));
                cell.setPadding(dp(10), dp(8), dp(10), dp(8));
                cell.setTextColor(r == 0 ? Color.rgb(245, 247, 250) : Color.rgb(224, 228, 235));
                if (r == 0) cell.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(r == 0 ? Color.rgb(43, 48, 57) : Color.rgb(27, 31, 38));
                bg.setStroke(dp(1), Color.rgb(72, 80, 92));
                cell.setBackground(bg);
                markwon.setMarkdown(cell, value.trim());
                row.addView(cell, new TableRow.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            table.addView(row, new TableLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        scroller.addView(table, new HorizontalScrollView.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroller;
    }

    private boolean looksLikeTableRow(String line) {
        if (line == null || line.trim().isEmpty() || line.indexOf('|') < 0) return false;
        return splitTableRow(line).size() >= 2;
    }

    private boolean isTableSeparator(String line) {
        if (!looksLikeTableRow(line)) return false;
        List<String> cells = splitTableRow(line);
        if (cells.size() < 2) return false;
        for (String cell : cells) {
            if (!cell.trim().matches(":?-{3,}:?")) return false;
        }
        return true;
    }

    private List<String> splitTableRow(String raw) {
        String line = raw == null ? "" : raw.trim();
        if (line.startsWith("|")) line = line.substring(1);
        if (line.endsWith("|") && !line.endsWith("\\|")) line = line.substring(0, line.length() - 1);

        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '|' && !escaped) {
                cells.add(cell.toString());
                cell.setLength(0);
                continue;
            }
            if (c == '\\' && !escaped) {
                escaped = true;
                cell.append(c);
                continue;
            }
            escaped = false;
            cell.append(c);
        }
        cells.add(cell.toString());
        return cells;
    }

    private TextView markdownView(int sizeSp) {
        TextView v = new TextView(context);
        v.setTextColor(Color.rgb(236, 239, 244));
        v.setTextSize(sizeSp);
        v.setTextIsSelectable(true);
        v.setLineSpacing(0, 1.08f);
        v.setLinksClickable(true);
        v.setMovementMethod(LinkMovementMethod.getInstance());
        return v;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(int v) { return Math.round(v * density); }
}
