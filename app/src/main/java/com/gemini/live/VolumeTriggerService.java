package com.gemini.live;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

public class VolumeTriggerService extends AccessibilityService {
    public static VolumeTriggerService instance = null;
    private long lastVolumeDownTime = 0;
    private static final long DOUBLE_PRESS_INTERVAL = 450;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    private void showToast(String msg) {
        mainHandler.post(() -> {
            try {
                Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {}
        });
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            autoApproveDialogs();
        }
    }

    private void autoApproveDialogs() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        findAndClickApprovalButton(root);
    }

    private boolean findAndClickApprovalButton(AccessibilityNodeInfo node) {
        if (node == null) return false;
        CharSequence text = node.getText();
        if (text != null) {
            String t = text.toString().toLowerCase().trim();
            if (t.equals("start now") || t.equals("allow") || t.equals("while using the app")) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        }
        String id = node.getViewIdResourceName();
        if (id != null && (id.endsWith("button1") || id.endsWith("permission_allow_button"))) {
            CharSequence btnText = node.getText();
            if (btnText == null || !btnText.toString().toLowerCase().contains("cancel")) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (findAndClickApprovalButton(node.getChild(i))) return true;
        }
        return false;
    }

    @Override
    public void onInterrupt() {}

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && action == KeyEvent.ACTION_DOWN) {
            long now = SystemClock.uptimeMillis();
            if (now - lastVolumeDownTime < DOUBLE_PRESS_INTERVAL) {
                lastVolumeDownTime = 0;
                launchAssistantWithHaptics();
                return true;
            } else {
                lastVolumeDownTime = now;
            }
        }
        return super.onKeyEvent(event);
    }

    private void launchAssistantWithHaptics() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(new long[]{0, 50, 70, 50}, -1));
                } else {
                    v.vibrate(new long[]{0, 50, 70, 50}, -1);
                }
            }
        } catch (Exception ignored) {}

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                      | Intent.FLAG_ACTIVITY_SINGLE_TOP 
                      | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(intent);
    }

    public boolean tapCoordinates(int x, int y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;

        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        if (wm != null) wm.getDefaultDisplay().getRealMetrics(dm);
        else dm = getResources().getDisplayMetrics();

        int targetX = x;
        int targetY = y;

        if (x > dm.widthPixels || y > dm.heightPixels) {
            targetX = Math.round((x / 1000.0f) * dm.widthPixels);
            targetY = Math.round((y / 1000.0f) * dm.heightPixels);
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            Rect snapped = findNearbyElementBounds(root, targetX, targetY, dm.widthPixels, 25);
            if (snapped != null) {
                targetX = snapped.centerX();
                targetY = snapped.centerY();
            }
        }

        targetX = Math.max(5, Math.min(targetX, dm.widthPixels - 5));
        targetY = Math.max(5, Math.min(targetY, dm.heightPixels - 5));

        Path clickPath = new Path();
        clickPath.moveTo(targetX, targetY);
        clickPath.lineTo(targetX, targetY + 2);

        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(clickPath, 0, 120);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);

        final int finalX = targetX;
        final int finalY = targetY;

        return dispatchGesture(builder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                showToast("✓ Tapped (" + finalX + ", " + finalY + ")");
            }
        }, null);
    }

    public boolean longPress(int x, int y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;

        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        if (wm != null) wm.getDefaultDisplay().getRealMetrics(dm);
        else dm = getResources().getDisplayMetrics();

        int targetX = (x > dm.widthPixels) ? Math.round((x / 1000.0f) * dm.widthPixels) : x;
        int targetY = (y > dm.heightPixels) ? Math.round((y / 1000.0f) * dm.heightPixels) : y;

        Path path = new Path();
        path.moveTo(targetX, targetY);
        path.lineTo(targetX, targetY + 1);

        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 700);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);

        final int fX = targetX;
        final int fY = targetY;

        return dispatchGesture(builder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                showToast("✓ Long-pressed (" + fX + ", " + fY + ")");
            }
        }, null);
    }

    private Rect findNearbyElementBounds(AccessibilityNodeInfo node, int x, int y, int screenWidth, int radius) {
        if (node == null) return null;
        if (node.isClickable()) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            boolean isSmallButton = r.width() > 5 && r.width() < (screenWidth * 0.45f) && r.height() < 120;
            if (isSmallButton) {
                if (r.contains(x, y)) return r;
                int dx = x - r.centerX();
                int dy = y - r.centerY();
                if (Math.sqrt(dx * dx + dy * dy) <= radius) return r;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            Rect found = findNearbyElementBounds(node.getChild(i), x, y, screenWidth, radius);
            if (found != null) return found;
        }
        return null;
    }

    public boolean swipe(int startX, int startY, int endX, int endY, int durationMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        Path path = new Path();
        path.moveTo(startX, startY);
        path.lineTo(endX, endY);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, Math.max(100, durationMs));
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);
        return dispatchGesture(builder.build(), null, null);
    }

    public boolean scroll(String direction) {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        if (wm != null) wm.getDefaultDisplay().getRealMetrics(dm);
        else dm = getResources().getDisplayMetrics();

        int midX = dm.widthPixels / 2;
        int h = dm.heightPixels;
        if ("up".equalsIgnoreCase(direction)) {
            return swipe(midX, (int)(h * 0.3), midX, (int)(h * 0.8), 300);
        } else {
            return swipe(midX, (int)(h * 0.8), midX, (int)(h * 0.3), 300);
        }
    }

    public String readScreenText() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "No active window detected.";

        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        if (wm != null) wm.getDefaultDisplay().getRealMetrics(dm);
        else dm = getResources().getDisplayMetrics();

        StringBuilder catalog = new StringBuilder();
        catalog.append("=== INTERACTIVE ELEMENTS (COORDINATES 0-1000) ===\n");
        List<String> elements = new ArrayList<>();
        extractInteractiveElements(root, elements, dm, 0);
        if (elements.isEmpty()) {
            catalog.append("No labeled buttons detected.\n");
        } else {
            for (String el : elements) catalog.append(el).append("\n");
        }

        catalog.append("\n=== SCREEN TEXT CONTENT ===\n");
        StringBuilder textSb = new StringBuilder();
        extractTextRecursive(root, textSb, 0);
        catalog.append(textSb.toString().trim());

        return catalog.toString();
    }

    private void extractInteractiveElements(AccessibilityNodeInfo node, List<String> list, DisplayMetrics dm, int depth) {
        if (node == null || depth > 25 || list.size() >= 25) return;
        if (node.isClickable() || node.isEditable()) {
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            String label = text != null ? text.toString().trim() : (desc != null ? desc.toString().trim() : "");
            if (!label.isEmpty()) {
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                if (r.width() > 10 && r.height() > 10) {
                    int normX = Math.round((r.centerX() / (float) dm.widthPixels) * 1000);
                    int normY = Math.round((r.centerY() / (float) dm.heightPixels) * 1000);
                    String type = node.isEditable() ? "Input" : "Button";
                    list.add("[" + type + "] \"" + label + "\" at coordinates (" + normX + ", " + normY + ")");
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            extractInteractiveElements(node.getChild(i), list, dm, depth + 1);
        }
    }

    private void extractTextRecursive(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null || depth > 25) return;
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            sb.append(text).append("\n");
        } else {
            CharSequence desc = node.getContentDescription();
            if (desc != null && desc.length() > 0) {
                sb.append(desc).append("\n");
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            extractTextRecursive(node.getChild(i), sb, depth + 1);
        }
    }

    public boolean tapElement(String label) {
        String target = label.toLowerCase().trim();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;

        List<AccessibilityNodeInfo> matches = new ArrayList<>();
        collectMatches(root, target, matches, 0);
        if (matches.isEmpty()) return false;

        AccessibilityNodeInfo bestNode = null;
        int minArea = Integer.MAX_VALUE;
        Rect r = new Rect();
        for (AccessibilityNodeInfo n : matches) {
            n.getBoundsInScreen(r);
            int area = r.width() * r.height();
            if (area > 0 && area < minArea) {
                minArea = area;
                bestNode = n;
            }
        }

        if (bestNode != null) {
            bestNode.getBoundsInScreen(r);
            return tapCoordinates(r.centerX(), r.centerY());
        }
        return false;
    }

    private void collectMatches(AccessibilityNodeInfo node, String target, List<AccessibilityNodeInfo> list, int depth) {
        if (node == null || depth > 25) return;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        boolean match = (text != null && text.toString().toLowerCase().contains(target)) ||
                        (desc != null && desc.toString().toLowerCase().contains(target));
        if (match) list.add(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            collectMatches(node.getChild(i), target, list, depth + 1);
        }
    }

    public boolean typeText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;

        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused == null) focused = findFocusedNode(root, 0);

        if (focused != null) {
            return pasteDirectly(focused, text);
        }

        AccessibilityNodeInfo editable = findFirstEditable(root, 0);
        if (editable != null) {
            Rect rect = new Rect();
            editable.getBoundsInScreen(rect);
            if (rect.width() > 0 && rect.height() > 0) {
                tapCoordinates(rect.centerX(), rect.centerY());
                SystemClock.sleep(200);
            }
            root = getRootInActiveWindow();
            if (root != null) {
                AccessibilityNodeInfo nowFocused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (nowFocused != null) return pasteDirectly(nowFocused, text);
            }
            return pasteDirectly(editable, text);
        }
        return false;
    }

    private AccessibilityNodeInfo findFocusedNode(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 25) return null;
        if (node.isFocused()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo res = findFocusedNode(node.getChild(i), depth + 1);
            if (res != null) return res;
        }
        return null;
    }

    private boolean pasteDirectly(AccessibilityNodeInfo node, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                ClipData clip = ClipData.newPlainText("jarvis_input", text);
                cm.setPrimaryClip(clip);
            }
        } catch (Exception ignored) {}

        boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE);
        if (!ok) {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        }

        if (ok) showToast("✓ Inserted text");
        return ok;
    }

    private AccessibilityNodeInfo findFirstEditable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 25) return null;
        if (node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findFirstEditable(node.getChild(i), depth + 1);
            if (found != null) return found;
        }
        return null;
    }
}
