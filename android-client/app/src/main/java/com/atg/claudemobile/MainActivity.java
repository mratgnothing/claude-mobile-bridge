package com.atg.claudemobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<ChatMessage> messages = new ArrayList<>();
    private final List<String> providers = new ArrayList<>();
    private final List<String> modelProfileIds = new ArrayList<>();

    private ConnectionStore store;
    private BridgeApi api;
    private JSONArray profiles = new JSONArray();
    private JSONArray efforts = new JSONArray();
    private JSONArray sessions = new JSONArray();
    private JSONObject selectedSession;
    private JSONObject selectedConfig;
    private JSONObject selectedContext;
    private String selectedId;
    private String activeTaskId;
    private String focusWhenLoadedId;
    private int liveIndex = -1;
    private int sessionLoadGeneration = 0;
    private boolean suppressControls;

    private TextView title;
    private TextView meta;
    private TextView status;
    private TextView readOnly;
    private TextView liveTool;
    private Spinner apiSpinner;
    private Spinner modelSpinner;
    private Spinner effortSpinner;
    private ListView messageList;
    private MessageAdapter messageAdapter;
    private TextView messageState;
    private EditText input;
    private Button send;
    private Button stop;

    private final Runnable periodicRefresh = new Runnable() {
        @Override public void run() {
            if (api != null && selectedId != null && activeTaskId == null) {
                refreshSessions();
                reloadMessages();
            }
            ui.postDelayed(this, 4500);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            store = new ConnectionStore(this);
            setupWindow();
            buildUi();
            boolean paired = acceptPairing(getIntent());
            if (paired || store.isPaired()) connect();
            else showConnectionDialog(true);
        } catch (Throwable t) {
            showStartupFailure(t);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (acceptPairing(intent)) connect();
    }

    @Override protected void onResume() {
        super.onResume();
        ui.removeCallbacks(periodicRefresh);
        ui.postDelayed(periodicRefresh, 4500);
    }

    @Override protected void onPause() {
        ui.removeCallbacks(periodicRefresh);
        super.onPause();
    }

    @Override protected void onDestroy() {
        ui.removeCallbacks(periodicRefresh);
        io.shutdownNow();
        super.onDestroy();
    }

    private void setupWindow() {
        getWindow().setStatusBarColor(Color.rgb(16,17,20));
        getWindow().setNavigationBarColor(Color.rgb(16,17,20));
    }

    private void applySystemInsets(View root) {
        final int left0 = root.getPaddingLeft();
        final int top0 = root.getPaddingTop();
        final int right0 = root.getPaddingRight();
        final int bottom0 = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = safe.left;
                top = safe.top;
                right = safe.right;
                bottom = safe.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(left0 + left, top0 + top, right0 + right, bottom0 + bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    private void showStartupFailure(Throwable t) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(20));
        root.setBackgroundColor(Color.rgb(16,17,20));
        TextView head = text("Claude Code Remote 启动失败", 20, Color.WHITE, true);
        TextView body = text(android.util.Log.getStackTraceString(t), 12, Color.rgb(230,190,190), false);
        body.setTextIsSelectable(true);
        body.setPadding(0, dp(16), 0, 0);
        root.addView(head);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        applySystemInsets(root);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16,17,20));
        root.setPadding(dp(8), dp(4), dp(8), dp(6));
        setContentView(root);
        applySystemInsets(root);

        LinearLayout top = row(Gravity.CENTER_VERTICAL);
        Button menu = iconButton("☰");
        menu.setOnClickListener(v -> showSessions());
        top.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(46)));

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        title = text("Claude Code", 18, Color.WHITE, true);
        meta = text("等待连接", 11, Color.rgb(148,156,171), false);
        title.setSingleLine(true); meta.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heading.addView(title); heading.addView(meta);
        top.addView(heading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        status = text("○", 18, Color.rgb(145,152,165), true);
        status.setPadding(dp(7),0,dp(7),0);
        status.setOnClickListener(v -> connect());
        top.addView(status);
        Button settings = iconButton("⚙");
        settings.setOnClickListener(v -> showConnectionDialog(false));
        top.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(46)));
        root.addView(top);

        LinearLayout controls = row(Gravity.CENTER_VERTICAL);
        apiSpinner = spinner(); modelSpinner = spinner(); effortSpinner = spinner();
        controls.addView(apiSpinner, new LinearLayout.LayoutParams(0, dp(48), 1.05f));
        controls.addView(modelSpinner, new LinearLayout.LayoutParams(0, dp(48), 1.25f));
        controls.addView(effortSpinner, new LinearLayout.LayoutParams(0, dp(48), .8f));
        root.addView(controls);

        readOnly = text("", 12, Color.rgb(238,200,158), false);
        readOnly.setPadding(dp(12),dp(8),dp(12),dp(8));
        readOnly.setBackground(round(Color.rgb(49,38,27),10));
        readOnly.setVisibility(View.GONE);
        readOnly.setOnClickListener(v -> forkForMobile());
        root.addView(readOnly);

        FrameLayout messagePane = new FrameLayout(this);
        messageList = new ListView(this);
        messageList.setDivider(null);
        messageList.setDividerHeight(0);
        messageList.setBackgroundColor(Color.TRANSPARENT);
        messageAdapter = new MessageAdapter(this, messages);
        messageList.setAdapter(messageAdapter);
        messagePane.addView(messageList, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        messageState = text("正在连接…", 13, Color.rgb(145, 153, 167), false);
        messageState.setGravity(Gravity.CENTER);
        messageState.setPadding(dp(24), dp(24), dp(24), dp(24));
        FrameLayout.LayoutParams stateLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        messagePane.addView(messageState, stateLp);
        root.addView(messagePane, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1));

        liveTool = text("", 11, Color.rgb(159,170,187), false);
        liveTool.setVisibility(View.GONE);
        liveTool.setPadding(dp(8),dp(2),dp(8),dp(2));
        root.addView(liveTool);

        LinearLayout composer = row(Gravity.BOTTOM);
        input = new EditText(this);
        input.setHint("给 Claude Code 发消息…");
        input.setHintTextColor(Color.rgb(120,128,142));
        input.setTextColor(Color.WHITE);
        input.setTextSize(15);
        input.setMaxLines(6);
        input.setPadding(dp(13),dp(10),dp(13),dp(10));
        input.setBackground(round(Color.rgb(32,36,43),14));
        composer.addView(input, new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
        stop = actionButton("■", Color.rgb(75,39,44));
        stop.setVisibility(View.GONE);
        stop.setContentDescription("中止或取消当前任务");
        stop.setOnClickListener(v -> stopCurrentWork());
        composer.addView(stop, new LinearLayout.LayoutParams(dp(48),dp(48)));
        send = actionButton("↑", Color.rgb(215,155,98));
        send.setTextColor(Color.rgb(35,25,17));
        send.setOnClickListener(v -> sendMessage());
        composer.addView(send, new LinearLayout.LayoutParams(dp(48),dp(48)));
        root.addView(composer);

        wireControls();
        setInteractive(false);
    }

    private void wireControls() {
        apiSpinner.setOnItemSelectedListener(new SimpleSelected() {
            @Override public void selected(int position) {
                if (suppressControls || selectedId == null || position >= providers.size()) return;
                populateModels(providers.get(position), selectedConfig == null ? null : selectedConfig.optString("profile", null));
            }
        });
        modelSpinner.setOnItemSelectedListener(new SimpleSelected() {
            @Override public void selected(int position) {
                if (suppressControls || selectedId == null || position >= modelProfileIds.size()) return;
                JSONObject p = profile(modelProfileIds.get(position));
                if (p == null) return;
                JSONObject patch = new JSONObject();
                try { patch.put("profile", p.optString("id")); patch.put("model", p.optString("model")); } catch (Exception ignored) {}
                patchSession(patch);
            }
        });
        effortSpinner.setOnItemSelectedListener(new SimpleSelected() {
            @Override public void selected(int position) {
                if (suppressControls || selectedId == null || position >= efforts.length()) return;
                JSONObject patch = new JSONObject();
                try { patch.put("effort", efforts.optString(position,"high")); } catch (Exception ignored) {}
                patchSession(patch);
            }
        });
    }

    private boolean acceptPairing(Intent intent) {
        if (intent == null || !store.acceptPairing(intent.getData())) return false;
        Toast.makeText(this,"Claude Bridge 配对成功",Toast.LENGTH_SHORT).show();
        return true;
    }

    private void connect() {
        if (!store.isPaired()) { showConnectionDialog(true); return; }
        api = new BridgeApi(store.baseUrl(), store.token());
        markStatus(false,"连接中");
        io.execute(() -> {
            try {
                JSONObject boot = api.get("/api/bootstrap");
                runOnUiThread(() -> applyBootstrap(boot));
            } catch (Exception e) { runOnUiThread(() -> fail(e)); }
        });
    }

    private void applyBootstrap(JSONObject boot) {
        profiles = array(boot,"profiles"); efforts = array(boot,"efforts"); sessions = array(boot,"sessions");
        markStatus(true,"已连接");
        String wanted = store.sessionId();
        if (wanted == null || findSession(wanted) == null) wanted = sessions.length() > 0 ? sessions.optJSONObject(0).optString("id",null) : null;
        if (wanted != null) selectSession(wanted);
        else {
            meta.setText("已连接 · 新建一个对话");
            showMessageState("还没有 Claude Code 对话。\n点左上角菜单新建一个对话。", true);
            setInteractive(false);
        }
    }

    private void selectSession(String id) {
        if (id == null || id.trim().isEmpty() || api == null) return;
        final int generation = ++sessionLoadGeneration;
        selectedId = id;
        store.setSessionId(id);
        markStatus(true,"加载中");

        // Clear the previous conversation immediately so a successful tap can
        // never look like it was ignored while the new history is in flight.
        messages.clear();
        liveIndex = -1;
        messageAdapter.notifyDataSetChanged();
        showMessageState("正在加载这段对话…", true);
        title.setText("Claude Code");
        meta.setText("加载中…");
        setInteractive(false);

        io.execute(() -> {
            try {
                JSONObject detail = api.get("/api/session/"+id);
                JSONObject history = api.get("/api/session/"+id+"/messages?limit=180");
                runOnUiThread(() -> {
                    if (generation != sessionLoadGeneration || !id.equals(selectedId)) return;
                    selectedSession = detail.optJSONObject("session");
                    selectedConfig = detail.optJSONObject("config");
                    selectedContext = detail.optJSONObject("context");
                    if (selectedSession == null) {
                        showMessageState("这段对话已不存在或无法读取。", true);
                        meta.setText("无法读取该对话");
                        setInteractive(false);
                        return;
                    }
                    renderMessages(array(history,"messages"), true);
                    renderHeader();
                    populateControls();
                    updateReadonly();
                    markStatus(true,"已连接");
                    if (id.equals(focusWhenLoadedId)) {
                        focusWhenLoadedId = null;
                        focusComposer();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (generation != sessionLoadGeneration || !id.equals(selectedId)) return;
                    showMessageState("加载对话失败：\n" + safeError(e), true);
                    meta.setText("加载失败");
                    markStatus(true,"已连接");
                    Toast.makeText(this, safeError(e), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void renderHeader() {
        if (selectedSession == null) return;
        title.setText(cleanTitle(selectedSession.optString("title","Claude Code")));
        String cwd = selectedSession.optString("cwd", selectedConfig == null ? "" : selectedConfig.optString("cwd",""));
        int md = selectedContext == null ? 0 : array(selectedContext,"claudeMd").length();
        int mem = selectedContext == null ? 0 : array(selectedContext,"memory").length();
        meta.setText(shortCwd(cwd)+" · CLAUDE.md "+md+" · Memory "+mem);
    }

    private void populateControls() {
        suppressControls = true;
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (int i=0;i<profiles.length();i++) { JSONObject p=profiles.optJSONObject(i); if(p!=null) unique.add(p.optString("apiName","Default")); }
        providers.clear(); providers.addAll(unique); setSpinner(apiSpinner,providers);
        String profileId = selectedConfig == null ? null : selectedConfig.optString("profile",null);
        JSONObject current = profile(profileId);
        String provider = current == null ? (providers.isEmpty()?null:providers.get(0)) : current.optString("apiName","Default");
        if (provider != null && providers.contains(provider)) apiSpinner.setSelection(providers.indexOf(provider),false);
        populateModels(provider,profileId);
        List<String> es = new ArrayList<>(); for(int i=0;i<efforts.length();i++) es.add(efforts.optString(i)); setSpinner(effortSpinner,es);
        String effort = selectedConfig == null ? "high" : selectedConfig.optString("effort","high");
        if(es.contains(effort)) effortSpinner.setSelection(es.indexOf(effort),false);
        suppressControls = false;
    }

    private void populateModels(String provider,String chosen) {
        boolean old=suppressControls; suppressControls=true;
        List<String> labels=new ArrayList<>(); modelProfileIds.clear();
        for(int i=0;i<profiles.length();i++) { JSONObject p=profiles.optJSONObject(i); if(p==null||!p.optString("apiName","Default").equals(provider)) continue; modelProfileIds.add(p.optString("id")); labels.add(p.optString("modelName",p.optString("model"))); }
        setSpinner(modelSpinner,labels); int idx=modelProfileIds.indexOf(chosen); if(idx<0)idx=0; if(!labels.isEmpty())modelSpinner.setSelection(idx,false); suppressControls=old;
    }

    private void patchSession(JSONObject patch) {
        if(api==null||selectedId==null)return; String id=selectedId;
        io.execute(() -> { try { JSONObject r=api.patch("/api/session/"+id,patch); runOnUiThread(() -> { if(id.equals(selectedId)){ selectedConfig=r.optJSONObject("config"); renderHeader(); }}); } catch(Exception e){runOnUiThread(() -> fail(e));} });
    }

    private void showSessions() {
        LinearLayout box = dialogBox();

        Button newChat = new Button(this);
        newChat.setText("＋  新建 Claude Code 对话");
        newChat.setTextSize(14);
        newChat.setAllCaps(false);
        newChat.setTextColor(Color.rgb(33, 36, 42));
        newChat.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        newChat.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable newBg = round(Color.rgb(245, 239, 229), 10);
        newBg.setStroke(dp(1), Color.rgb(218, 202, 181));
        newChat.setBackground(newBg);
        box.addView(newChat, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        TextView hint = text("点按卡片切换对话；长按卡片可删除。", 11, Color.rgb(105, 110, 120), false);
        hint.setPadding(dp(2), dp(9), dp(2), dp(5));
        box.addView(hint);

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(false);
        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(cards, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int maxHeight = Math.min(dp(540), (int) (getResources().getDisplayMetrics().heightPixels * 0.62f));
        box.addView(scroller, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, maxHeight));

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Claude Code 对话")
            .setView(box)
            .setNegativeButton("关闭", null)
            .create();

        if (sessions.length() == 0) {
            TextView empty = text("暂无历史对话", 13, Color.rgb(115, 121, 132), false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(34), dp(12), dp(34));
            cards.addView(empty, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            for (int i = 0; i < sessions.length(); i++) {
                JSONObject session = sessions.optJSONObject(i);
                if (session == null) continue;
                View card = sessionCard(session);
                card.setClickable(true);
                card.setFocusable(true);
                card.setOnClickListener(v -> {
                    String id = session.optString("id", null);
                    if (id == null || id.isEmpty()) return;
                    dialog.dismiss();
                    // Post the selection after the dialog closes.  This avoids a
                    // device-specific AlertDialog/ListView focus race seen on the
                    // phone where the request succeeded but the main pane did not repaint.
                    ui.post(() -> selectSession(id));
                });
                card.setOnLongClickListener(v -> {
                    confirmDeleteSession(dialog, session);
                    return true;
                });
                cards.addView(card);
            }
        }

        newChat.setOnClickListener(v -> {
            dialog.dismiss();
            // Opening a second AlertDialog in the same click frame is unreliable
            // on some Android vendor builds, so wait for dismissal to settle.
            ui.postDelayed(this::showNewChat, 120);
        });
        dialog.show();
    }

    private View sessionCard(JSONObject s) {
        boolean selected = s.optString("id", "").equals(selectedId);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(11), dp(14), dp(11));
        GradientDrawable bg = round(
            selected ? Color.rgb(232, 239, 248) : Color.rgb(249, 249, 250), 10);
        bg.setStroke(dp(1), selected ? Color.rgb(127, 154, 188) : Color.rgb(224, 226, 230));
        card.setBackground(bg);

        TextView line1 = text(cleanTitle(s.optString("title", "新对话")), 14, Color.rgb(28, 31, 36), true);
        line1.setMaxLines(2);
        line1.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(line1);

        String cwd = shortCwd(s.optString("cwd", ""));
        String model = s.optString("model", "");
        String updated = shortUpdatedAt(s.optString("updatedAt", ""));
        StringBuilder metaText = new StringBuilder();
        if (!cwd.isEmpty()) metaText.append(cwd);
        if (!model.isEmpty()) {
            if (metaText.length() > 0) metaText.append(" · ");
            metaText.append(model);
        }
        if (!updated.isEmpty()) {
            if (metaText.length() > 0) metaText.append(" · ");
            metaText.append(updated);
        }
        TextView line2 = text(metaText.toString(), 11, Color.rgb(95, 102, 113), false);
        line2.setSingleLine(true);
        line2.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        line2.setPadding(0, dp(4), 0, 0);
        card.addView(line2);

        String state;
        if (s.optBoolean("active")) {
            String a = s.optString("activeStatus", "");
            state = "idle".equalsIgnoreCase(a) ? "桌面空闲" : "桌面运行中";
        } else if (s.optBoolean("bridgeOwned")) {
            state = "手机会话";
        } else {
            state = "历史会话";
        }
        if (selected) state = "当前 · " + state;
        TextView line3 = text(state, 11,
            selected ? Color.rgb(59, 95, 139) : Color.rgb(124, 130, 139), selected);
        line3.setPadding(0, dp(4), 0, 0);
        card.addView(line3);

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(2), dp(4), dp(2), dp(4));
        outer.addView(card, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return outer;
    }

    private void confirmDeleteSession(AlertDialog parent, JSONObject session) {
        String id = session.optString("id", "");
        if (id.isEmpty()) return;
        if (session.optBoolean("active", false)) {
            Toast.makeText(this, "这个桌面 Claude Code 对话仍在运行，先结束桌面会话后才能删除。", Toast.LENGTH_LONG).show();
            return;
        }
        String name = cleanTitle(session.optString("title", "新对话"));
        String cwd = shortCwd(session.optString("cwd", ""));
        String detail = "将删除“" + name + "”" + (cwd.isEmpty() ? "" : "\n" + cwd)
            + "\n\n该操作会从本机 Claude Code 历史中移除这段对话，无法在应用内恢复。";
        new AlertDialog.Builder(this)
            .setTitle("删除这段对话？")
            .setMessage(detail)
            .setNegativeButton("取消", null)
            .setPositiveButton("删除", (d, w) -> {
                parent.dismiss();
                deleteSession(id);
            })
            .show();
    }

    private void deleteSession(String id) {
        if (api == null || id == null || id.isEmpty()) return;
        io.execute(() -> {
            try {
                api.delete("/api/session/" + id);
                JSONObject refreshed = api.get("/api/sessions?limit=100");
                JSONArray next = array(refreshed, "sessions");
                runOnUiThread(() -> {
                    sessions = next;
                    if (id.equals(selectedId)) {
                        activeTaskId = null;
                        liveIndex = -1;
                        JSONObject first = sessions.length() > 0 ? sessions.optJSONObject(0) : null;
                        String nextId = first == null ? null : first.optString("id", null);
                        if (nextId != null && !nextId.isEmpty()) selectSession(nextId);
                        else clearSelectedSession();
                    }
                    Toast.makeText(this, "对话已删除", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> fail(e));
            }
        });
    }

    private void clearSelectedSession() {
        selectedId = null;
        selectedSession = null;
        selectedConfig = null;
        selectedContext = null;
        store.clearSessionId();
        messages.clear();
        messageAdapter.notifyDataSetChanged();
        title.setText("Claude Code");
        meta.setText("已连接 · 新建一个对话");
        readOnly.setVisibility(View.GONE);
        liveTool.setVisibility(View.GONE);
        showMessageState("还没有选中的对话。\n点左上角菜单选择或新建。", true);
        setInteractive(false);
    }

    private void showNewChat() {
        if (api == null) {
            Toast.makeText(this, "尚未连接到 Claude Bridge", Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout box = dialogBox();
        EditText cwd = field("工作目录");
        cwd.setText(selectedConfig == null ? "C:\\" : selectedConfig.optString("cwd", "C:\\"));
        EditText name = field("标题（可选）");
        TextView feedback = text("", 11, Color.rgb(164, 84, 84), false);
        feedback.setPadding(0, dp(7), 0, 0);
        feedback.setVisibility(View.GONE);
        box.addView(label("工作目录"));
        box.addView(cwd);
        box.addView(label("标题"));
        box.addView(name);
        box.addView(feedback);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("新建 Claude Code 对话")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("创建", null)
            .create();
        dialog.setOnShowListener(ignored -> {
            Button create = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            create.setOnClickListener(v -> {
                String dir = cwd.getText().toString().trim();
                if (dir.isEmpty()) dir = "C:\\";
                String titleText = name.getText().toString().trim();
                feedback.setVisibility(View.GONE);
                create.setEnabled(false);
                create.setText("创建中…");
                createSession(dir, titleText, dialog, create, feedback);
            });
        });
        dialog.show();
    }

    private void createSession(String cwd, String name, AlertDialog dialog, Button createButton, TextView feedback) {
        JSONObject b = new JSONObject();
        try {
            b.put("cwd", cwd);
            b.put("title", name.trim().isEmpty() ? "新对话" : name);
            if (selectedConfig != null) {
                b.put("profile", selectedConfig.optString("profile", null));
                b.put("model", selectedConfig.optString("model", null));
                b.put("effort", selectedConfig.optString("effort", "high"));
            }
        } catch (Exception ignored) {}
        io.execute(() -> {
            try {
                JSONObject r = api.post("/api/session", b);
                String id = r.optString("sessionId", null);
                if (id == null || id.isEmpty()) throw new IllegalStateException("Bridge 未返回新会话 ID");
                JSONObject refreshed = api.get("/api/sessions?limit=100");
                JSONArray next = array(refreshed, "sessions");
                runOnUiThread(() -> {
                    sessions = next;
                    dialog.dismiss();
                    focusWhenLoadedId = id;
                    selectSession(id);
                    Toast.makeText(this, "新对话已创建", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    createButton.setEnabled(true);
                    createButton.setText("创建");
                    feedback.setText("创建失败：" + safeError(e));
                    feedback.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void forkForMobile() {
        if (api == null || selectedId == null || selectedSession == null || !selectedSession.optBoolean("canTakeover", false)) return;
        String sourceId = selectedId;
        readOnly.setEnabled(false);
        readOnly.setText("正在创建手机继续会话…");
        io.execute(() -> {
            try {
                JSONObject result = api.post("/api/session/" + sourceId + "/fork", new JSONObject());
                String id = result.optString("sessionId", null);
                runOnUiThread(() -> {
                    readOnly.setEnabled(true);
                    if (id == null) {
                        Toast.makeText(this, "Bridge 未返回新会话 ID", Toast.LENGTH_LONG).show();
                        return;
                    }
                    Toast.makeText(this, "已在手机创建安全分支，桌面原会话不受影响", Toast.LENGTH_SHORT).show();
                    refreshSessions();
                    selectSession(id);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    readOnly.setEnabled(true);
                    updateReadonly();
                    fail(e);
                });
            }
        });
    }

    private void refreshSessions() {
        if(api==null)return; io.execute(()->{try{JSONObject r=api.get("/api/sessions?limit=100");JSONArray a=array(r,"sessions");runOnUiThread(()->{sessions=a;if(selectedId!=null){JSONObject latest=findSession(selectedId);if(latest!=null){selectedSession=latest;renderHeader();updateReadonly();}}});}catch(Exception ignored){}});
    }

    private void renderMessages(JSONArray arr, boolean forceBottom) {
        int firstVisible = messageList.getFirstVisiblePosition();
        View firstChild = messageList.getChildAt(0);
        int firstTop = firstChild == null ? 0 : firstChild.getTop();

        messages.clear();
        liveIndex = -1;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m == null) continue;
            String body = m.optString("text", "");
            String kind = m.optString("kind", "message");
            String titleText = m.optString("title", "");

            // Backward compatibility with older bridge responses.
            JSONArray tools = m.optJSONArray("tools");
            if (tools != null && tools.length() > 0 && "message".equals(kind)) {
                StringBuilder x = new StringBuilder();
                for (int j = 0; j < tools.length(); j++) {
                    JSONObject t = tools.optJSONObject(j);
                    if (t != null) {
                        if (x.length() > 0) x.append(" · ");
                        x.append(t.optString("name", "tool"));
                    }
                }
                if (x.length() > 0) body += (body.trim().isEmpty() ? "" : "\n\n") + "执行：" + x;
            }
            if (body.trim().isEmpty()) continue;
            messages.add(new ChatMessage(
                m.optString("role", "assistant"),
                body,
                m.optString("model", ""),
                kind,
                titleText
            ));
        }
        messageAdapter.notifyDataSetChanged();
        if (messageAdapter.getCount() == 0) {
            showMessageState("这段对话暂无可显示的消息。\n可以直接在下方输入第一条消息。", true);
        } else {
            showMessageState("", false);
        }
        if (forceBottom) {
            scrollBottom();
        } else if (firstVisible >= 0 && messageAdapter.getCount() > 0) {
            int restore = Math.min(firstVisible, messageAdapter.getCount() - 1);
            messageList.post(() -> messageList.setSelectionFromTop(restore, firstTop));
        }
    }

    private void sendMessage() {
        if (api == null || selectedId == null || activeTaskId != null) return;
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        input.setText("");
        messages.add(new ChatMessage("user", text, ""));
        messageAdapter.notifyDataSetChanged();
        showMessageState("", false);
        scrollBottom();
        input.setEnabled(false);
        send.setEnabled(false);

        JSONObject b = new JSONObject();
        try { b.put("text", text); } catch (Exception ignored) {}
        String id = selectedId;
        io.execute(() -> {
            try {
                JSONObject r = api.post("/api/session/" + id + "/message", b);
                String tid = r.optString("taskId", null);
                if (tid == null) throw new IllegalStateException("Bridge 未返回 taskId");
                runOnUiThread(() -> {
                    activeTaskId = tid;
                    updateReadonly();
                    pollTask(tid, id);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    activeTaskId = null;
                    updateReadonly();
                    fail(e);
                    reloadMessages();
                });
            }
        });
    }

    private void pollTask(String tid, String sourceId) {
        if (!tid.equals(activeTaskId)) return;
        io.execute(() -> {
            try {
                JSONObject r = api.get("/api/task/" + tid);
                JSONObject t = r.optJSONObject("task");
                runOnUiThread(() -> {
                    if (t == null || !tid.equals(activeTaskId)) return;
                    showLiveTask(t);
                    String s = t.optString("status", "running");
                    if (s.equals("queued") || s.equals("waiting_external") || s.equals("running") || s.equals("stopping")) {
                        ui.postDelayed(() -> pollTask(tid, sourceId), 650);
                        return;
                    }

                    boolean stillViewingSource = sourceId.equals(selectedId);
                    String effectiveId = t.optString("effectiveSessionId", sourceId);
                    activeTaskId = null;
                    liveTool.setVisibility(View.GONE);
                    updateReadonly();
                    if (s.equals("error")) {
                        Toast.makeText(this, t.optString("error", "执行失败"), Toast.LENGTH_LONG).show();
                        if (stillViewingSource) reloadMessages();
                    } else if (s.equals("stopped")) {
                        Toast.makeText(this, "手机端任务已停止", Toast.LENGTH_SHORT).show();
                        if (stillViewingSource) reloadMessages();
                    } else if (t.optBoolean("injectedInteractive", false)) {
                        Toast.makeText(this,
                            t.optString("prompt", "").trim().startsWith("/btw")
                                ? "已发送 Claude Code 原生 /btw"
                                : "已直接输入到桌面 Claude Code",
                            Toast.LENGTH_SHORT).show();
                    } else if (stillViewingSource && effectiveId != null && !effectiveId.isEmpty() && !effectiveId.equals(sourceId)) {
                        refreshSessions();
                        selectSession(effectiveId);
                    } else if (stillViewingSource) {
                        reloadMessages();
                    }
                    refreshSessions();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    activeTaskId = null;
                    liveTool.setVisibility(View.GONE);
                    updateReadonly();
                    fail(e);
                });
            }
        });
    }

    private void showLiveTask(JSONObject t) {
        boolean followLatest = isNearBottom();
        String state = t.optString("status", "running");
        if ("waiting_external".equals(state)) {
            liveTool.setText("该会话不是可直接注入的桌面终端：消息已排队，本轮结束后会自动续接。");
            liveTool.setVisibility(View.VISIBLE);
            return;
        }

        String partial = t.optString("partialText", "");
        if (!partial.trim().isEmpty()) {
            showMessageState("", false);
            if (liveIndex < 0) {
                liveIndex = messages.size();
                messages.add(new ChatMessage("assistant", partial, ""));
            } else if (liveIndex < messages.size()) {
                messages.get(liveIndex).text = partial;
            }
        }

        String processText = null;
        JSONArray events = t.optJSONArray("events");
        if (events != null) {
            for (int i = events.length() - 1; i >= 0; i--) {
                JSONObject e = events.optJSONObject(i);
                if (e != null && (e.optString("type").equals("tool") || e.optString("type").equals("tool_start"))) {
                    processText = "正在执行：" + e.optString("name", "tool");
                    break;
                }
            }
        }
        if (processText == null && !t.optString("partialThinking", "").trim().isEmpty()) {
            processText = "Claude 正在思考…";
        }
        if (processText != null) {
            liveTool.setText(processText);
            liveTool.setVisibility(View.VISIBLE);
        } else if ("running".equals(state)) {
            liveTool.setVisibility(View.GONE);
        }
        messageAdapter.notifyDataSetChanged();
        if (liveIndex >= 0 && followLatest) scrollBottom();
    }

    private void stopCurrentWork() {
        boolean external = selectedSession != null && selectedSession.optBoolean("active", false);
        if (!external) {
            if (activeTaskId != null) stopTask();
            return;
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this)
            .setTitle("中止当前 Claude 轮次？")
            .setMessage("交互式桌面会话会收到 Ctrl+C，只停止当前生成/执行，不关闭 Claude Code，会话和上下文都保留。")
            .setNegativeButton("返回", null)
            .setPositiveButton("中止当前轮次", (d, w) -> stopExternalSession());
        if (activeTaskId != null) {
            b.setNeutralButton("只取消手机任务", (d, w) -> stopTask());
        }
        b.show();
    }

    private void stopExternalSession() {
        if (api == null || selectedId == null) return;
        String id = selectedId;
        stop.setEnabled(false);
        liveTool.setText("正在向桌面 Claude 发送中止指令…");
        liveTool.setVisibility(View.VISIBLE);
        io.execute(() -> {
            try {
                api.post("/api/session/" + id + "/stop", new JSONObject());
                runOnUiThread(() -> {
                    stop.setEnabled(true);
                    Toast.makeText(this, "已发送中止指令；桌面会话与上下文保留", Toast.LENGTH_SHORT).show();
                    refreshSessions();
                    if (id.equals(selectedId)) selectSession(id);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    stop.setEnabled(true);
                    fail(e);
                });
            }
        });
    }

    private void stopTask() {
        if (api == null || activeTaskId == null) return;
        String tid = activeTaskId;
        stop.setEnabled(false);
        io.execute(() -> {
            try {
                api.post("/api/task/" + tid + "/stop", new JSONObject());
                runOnUiThread(() -> stop.setEnabled(true));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    stop.setEnabled(true);
                    fail(e);
                });
            }
        });
    }

    private void reloadMessages() {
        if (api == null || selectedId == null) return;
        String id = selectedId;
        io.execute(() -> {
            try {
                JSONObject r = api.get("/api/session/" + id + "/messages?limit=180");
                runOnUiThread(() -> {
                    if (!id.equals(selectedId)) return;
                    renderMessages(array(r, "messages"), isNearBottom());
                });
            } catch (Exception ignored) {}
        });
    }

    private void updateReadonly() {
        boolean hasSession = selectedId != null;
        boolean busy = activeTaskId != null;
        boolean external = selectedSession != null && selectedSession.optBoolean("active", false);
        String externalStatus = selectedSession == null ? "" : selectedSession.optString("activeStatus", "");

        readOnly.setVisibility(external ? View.VISIBLE : View.GONE);
        if (external && "idle".equalsIgnoreCase(externalStatus)) {
            readOnly.setText("桌面 Claude 已空闲。手机输入会直接送入同一个 Claude Code 终端，无需创建副本。");
            readOnly.setClickable(false);
        } else if (external) {
            readOnly.setText("桌面 Claude 正在执行。手机输入会直接注入当前终端；/btw <内容> 使用 Claude Code 原生旁问，不打断主任务；点 ■ 可 Ctrl+C 中止当前轮次。");
            readOnly.setClickable(false);
        }

        // External desktop activity no longer makes the phone read-only.
        input.setEnabled(hasSession && !busy);
        send.setEnabled(hasSession && !busy);
        apiSpinner.setEnabled(hasSession && !busy);
        modelSpinner.setEnabled(hasSession && !busy);
        effortSpinner.setEnabled(hasSession && !busy);
        stop.setVisibility((external || busy) ? View.VISIBLE : View.GONE);
        stop.setEnabled(true);
    }

    private void setInteractive(boolean yes){input.setEnabled(yes);send.setEnabled(yes);apiSpinner.setEnabled(yes);modelSpinner.setEnabled(yes);effortSpinner.setEnabled(yes);}

    private void showConnectionDialog(boolean required) {
        LinearLayout box=dialogBox();EditText base=field("https://…");base.setText(store.baseUrl());EditText secret=field("访问令牌");secret.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);secret.setText(store.token());box.addView(label("Bridge 地址"));box.addView(base);box.addView(label("访问令牌"));box.addView(secret);TextView hint=text("推荐直接点击 QQ 中的配对链接，之后应用会自动连接。",12,Color.GRAY,false);hint.setPadding(0,dp(8),0,0);box.addView(hint);
        AlertDialog.Builder b=new AlertDialog.Builder(this).setTitle(required?"首次配对":"连接设置").setView(box).setPositiveButton("保存并连接",(d,w)->{String u=base.getText().toString().trim();String s=secret.getText().toString().trim();if(!u.startsWith("https://")||s.length()<24){Toast.makeText(this,"连接信息无效",Toast.LENGTH_LONG).show();return;}store.save(u,s);connect();});if(!required)b.setNegativeButton("取消",null).setNeutralButton("清除配对",(d,w)->{store.clear();api=null;selectedId=null;markStatus(false,"未配对");setInteractive(false);});AlertDialog dlg=b.create();dlg.setCanceledOnTouchOutside(!required);dlg.show();
    }

    private void markStatus(boolean online,String desc){status.setText(online?"●":"○");status.setTextColor(online?Color.rgb(93,190,122):Color.rgb(145,152,165));status.setContentDescription(desc);}
    private void fail(Exception e){markStatus(false,"异常");String m=safeError(e);meta.setText(m.length()>100?m.substring(0,100):m);Toast.makeText(this,m,Toast.LENGTH_LONG).show();}
    private static String safeError(Throwable e){String m=e==null?null:e.getMessage();return m==null||m.trim().isEmpty()?(e==null?"未知错误":e.getClass().getSimpleName()):m;}
    private void showMessageState(String text, boolean visible){
        if(messageState==null)return;
        messageState.setText(text==null?"":text);
        messageState.setVisibility(visible?View.VISIBLE:View.GONE);
    }
    private void focusComposer(){
        input.postDelayed(() -> {
            if(!input.isEnabled())return;
            input.requestFocus();
            InputMethodManager imm=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
            if(imm!=null)imm.showSoftInput(input,InputMethodManager.SHOW_IMPLICIT);
        },120);
    }
    private JSONObject findSession(String id){if(id==null)return null;for(int i=0;i<sessions.length();i++){JSONObject s=sessions.optJSONObject(i);if(s!=null&&id.equals(s.optString("id")))return s;}return null;}
    private JSONObject profile(String id){if(id==null)return null;for(int i=0;i<profiles.length();i++){JSONObject p=profiles.optJSONObject(i);if(p!=null&&id.equals(p.optString("id")))return p;}return null;}
    private static JSONArray array(JSONObject o,String key){JSONArray a=o==null?null:o.optJSONArray(key);return a==null?new JSONArray():a;}

    private abstract class SimpleSelected implements AdapterView.OnItemSelectedListener { public abstract void selected(int position);@Override public void onItemSelected(AdapterView<?> p,View v,int pos,long id){selected(pos);}@Override public void onNothingSelected(AdapterView<?> p){} }
    private LinearLayout row(int gravity){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(gravity);return l;}
    private TextView text(String s,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private Button iconButton(String s){Button b=new Button(this);b.setText(s);b.setTextSize(20);b.setTextColor(Color.rgb(224,227,233));b.setPadding(0,0,0,0);b.setBackgroundColor(Color.TRANSPARENT);return b;}
    private Button actionButton(String s,int color){Button b=new Button(this);b.setText(s);b.setTextSize(18);b.setTextColor(Color.WHITE);b.setPadding(0,0,0,0);b.setBackground(round(color,24));return b;}
    private Spinner spinner(){Spinner s=new Spinner(this,Spinner.MODE_DROPDOWN);s.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(92,98,110)));return s;}
    private void setSpinner(Spinner s,List<String> items){ArrayAdapter<String>a=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,items){@Override public View getView(int p,View c,ViewGroup parent){TextView v=(TextView)super.getView(p,c,parent);v.setTextColor(Color.rgb(224,227,233));v.setTextSize(12);v.setSingleLine(true);return v;}};a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);s.setAdapter(a);}
    private LinearLayout dialogBox(){LinearLayout b=new LinearLayout(this);b.setOrientation(LinearLayout.VERTICAL);b.setPadding(dp(20),dp(4),dp(20),0);return b;}
    private TextView label(String s){TextView v=text(s,12,Color.DKGRAY,true);v.setPadding(0,dp(8),0,dp(4));return v;}
    private EditText field(String hint){EditText e=new EditText(this);e.setHint(hint);e.setSingleLine(true);e.setTextSize(14);return e;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}

    private boolean isNearBottom() {
        int count = messageAdapter.getCount();
        if (count == 0 || messageList.getChildCount() == 0) return true;
        int lastVisible = messageList.getLastVisiblePosition();
        if (lastVisible < count - 1) return false;
        int childIndex = lastVisible - messageList.getFirstVisiblePosition();
        View last = childIndex >= 0 && childIndex < messageList.getChildCount()
            ? messageList.getChildAt(childIndex) : null;
        if (last == null) return false;
        int viewportBottom = messageList.getHeight() - messageList.getPaddingBottom();
        return last.getBottom() <= viewportBottom + dp(72);
    }

    private void scrollBottom() {
        messageList.post(() -> {
            int count = messageAdapter.getCount();
            if (count <= 0) return;
            int target = count - 1;
            messageList.setSelection(target);
            messageList.post(() -> {
                int index = target - messageList.getFirstVisiblePosition();
                if (index < 0 || index >= messageList.getChildCount()) return;
                View last = messageList.getChildAt(index);
                int viewportBottom = messageList.getHeight() - messageList.getPaddingBottom();
                int delta = last.getBottom() - viewportBottom;
                if (delta > 0) messageList.scrollListBy(delta);
            });
        });
    }

    private static String shortUpdatedAt(String raw) {
        if (raw == null || raw.length() < 16) return "";
        try {
            return raw.substring(5, 10) + " " + raw.substring(11, 16);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String shortCwd(String cwd){if(cwd==null||cwd.trim().isEmpty())return "";String n=cwd.replace('/','\\');String[]p=n.split("\\\\");if(p.length<=2)return n;return p[p.length-2]+"\\"+p[p.length-1];}
    private static String cleanTitle(String raw){
        if(raw==null||raw.trim().isEmpty())return "Claude Code";
        String t=raw.trim();
        if(t.startsWith("<local-command-")||t.startsWith("<command-")) return t.contains("· 手机")?"Claude Code · 手机":"Claude Code";
        return t;
    }
}
