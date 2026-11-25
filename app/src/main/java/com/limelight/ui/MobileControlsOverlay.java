package com.limelight.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.limelight.R;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.nvstream.input.MouseButtonPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * Floating mobile touch toolbar, extra PC keys bar, and mouse helper bar
 * for Android devices used without a physical mouse or keyboard.
 */
public class MobileControlsOverlay {

    public interface Callbacks {
        void onToggleKeyboard();
        boolean onToggleTouchMode();
        boolean isTrackpadMode();
        boolean onToggleVirtualController();
        boolean isVirtualControllerVisible();
        boolean onToggleHoverMode();
        boolean isHoverMode();
        boolean onTogglePerfOverlay();
        boolean isPerfOverlayVisible();
        void onDisconnect();

        void onMouseClick(byte mouseButton);
        void onMouseButtonHold(byte mouseButton, boolean down);

        void onStickyModifierChanged(byte modifierMask, boolean down);
        void onSendKeyTap(int androidKeyCode);
        void onSendKeyCombo(int[] androidKeyCodes);
    }

    private enum ModifierState {
        OFF,
        LATCHED,
        LOCKED
    }

    private static class StickyModifier {
        final byte mask;
        final String label;
        ModifierState state = ModifierState.OFF;
        Button button;

        StickyModifier(byte mask, String label) {
            this.mask = mask;
            this.label = label;
        }
    }

    private static final String PREF_NAME = "MobileControlsPrefs";
    private static final String PREF_HANDLE_X_RATIO = "handle_x_ratio";
    private static final String PREF_HANDLE_Y_RATIO = "handle_y_ratio";

    private static final float HANDLE_IDLE_ALPHA = 0.35f;
    private static final float HANDLE_ACTIVE_ALPHA = 1.0f;
    private static final long HANDLE_DIM_DELAY_MS = 3000;

    private final Activity activity;
    private final Callbacks callbacks;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final View controlsRoot;
    private final ImageButton handleButton;
    private final View quickPanelBackdrop;
    private final LinearLayout extraKeysBar;
    private final HorizontalScrollView extraFnScroll;
    private final LinearLayout extraFnRow;
    private final LinearLayout extraMainRow;
    private final LinearLayout mouseBar;
    private final LinearLayout shortcutsRow;

    private final Button btnKeyboard;
    private final Button btnExtraKeys;
    private final Button btnTouchMode;
    private final Button btnMouseBar;
    private final Button btnOsc;
    private final Button btnHoverMode;
    private final Button btnPerf;
    private final Button btnDisconnect;

    private Button mouseDragLockBtn;
    private Button mouseHoverBtn;
    private Button fnToggleBtn;

    private final List<StickyModifier> stickyModifiers = new ArrayList<>();
    private boolean fnRowVisible = false;
    private boolean dragLockActive = false;
    private boolean enabled = true;
    private boolean hiddenForPip = false;
    private int currentImeBottomInset = 0;

    private float handleXRatio = 0.88f;
    private float handleYRatio = 0.05f;

    private final Runnable dimHandleRunnable = new Runnable() {
        @Override
        public void run() {
            if (quickPanelBackdrop.getVisibility() != View.VISIBLE) {
                handleButton.animate().alpha(HANDLE_IDLE_ALPHA).setDuration(250).start();
            }
        }
    };

    private final ViewTreeObserver.OnGlobalLayoutListener globalLayoutListener =
            new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    updateExtraKeysBarBottomMargin();
                }
            };

    private final View.OnLayoutChangeListener rootLayoutChangeListener =
            new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                           int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    if ((right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)) {
                        applyHandlePositionFromRatio();
                    }
                }
            };

    public MobileControlsOverlay(Activity activity, View rootContainer, Callbacks callbacks) {
        this.activity = activity;
        this.callbacks = callbacks;

        this.controlsRoot = rootContainer.findViewById(R.id.mobileControlsRoot);
        this.handleButton = rootContainer.findViewById(R.id.mobileHandleButton);
        this.quickPanelBackdrop = rootContainer.findViewById(R.id.mobileQuickPanelBackdrop);
        this.extraKeysBar = rootContainer.findViewById(R.id.mobileExtraKeysBar);
        this.extraFnScroll = rootContainer.findViewById(R.id.mobileExtraFnScroll);
        this.extraFnRow = rootContainer.findViewById(R.id.mobileExtraFnRow);
        this.extraMainRow = rootContainer.findViewById(R.id.mobileExtraMainRow);
        this.mouseBar = rootContainer.findViewById(R.id.mobileMouseBar);
        this.shortcutsRow = rootContainer.findViewById(R.id.mobileShortcutsRow);

        this.btnKeyboard = rootContainer.findViewById(R.id.mobileBtnKeyboard);
        this.btnExtraKeys = rootContainer.findViewById(R.id.mobileBtnExtraKeys);
        this.btnTouchMode = rootContainer.findViewById(R.id.mobileBtnTouchMode);
        this.btnMouseBar = rootContainer.findViewById(R.id.mobileBtnMouseBar);
        this.btnOsc = rootContainer.findViewById(R.id.mobileBtnOsc);
        this.btnHoverMode = rootContainer.findViewById(R.id.mobileBtnHoverMode);
        this.btnPerf = rootContainer.findViewById(R.id.mobileBtnPerf);
        this.btnDisconnect = rootContainer.findViewById(R.id.mobileBtnDisconnect);

        SharedPreferences prefs = activity.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        handleXRatio = prefs.getFloat(PREF_HANDLE_X_RATIO, 0.88f);
        handleYRatio = prefs.getFloat(PREF_HANDLE_Y_RATIO, 0.05f);

        setupHandleTouch();
        setupQuickPanel(rootContainer);
        buildExtraKeysBar();
        buildMouseBar();
        buildShortcutsRow();
        setupImeInsetsTracking();

        controlsRoot.addOnLayoutChangeListener(rootLayoutChangeListener);
        controlsRoot.post(new Runnable() {
            @Override
            public void run() {
                applyHandlePositionFromRatio();
                scheduleHandleDim();
            }
        });
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        updateVisibility();
    }

    public void setHiddenForPip(boolean hidden) {
        this.hiddenForPip = hidden;
        if (hidden) {
            closeQuickPanel();
        }
        updateVisibility();
    }

    private void updateVisibility() {
        controlsRoot.setVisibility((enabled && !hiddenForPip) ? View.VISIBLE : View.GONE);
    }

    public boolean isQuickPanelOpen() {
        return quickPanelBackdrop.getVisibility() == View.VISIBLE;
    }

    public boolean isExtraKeysVisible() {
        return extraKeysBar.getVisibility() == View.VISIBLE;
    }

    public void showExtraKeysBar(boolean show) {
        extraKeysBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            updateExtraKeysBarBottomMargin();
        }
        refreshPanelButtonStates();
    }

    public void closeQuickPanel() {
        if (quickPanelBackdrop.getVisibility() == View.VISIBLE) {
            quickPanelBackdrop.setVisibility(View.GONE);
            scheduleHandleDim();
        }
    }

    public void toggleQuickPanel() {
        if (quickPanelBackdrop.getVisibility() == View.VISIBLE) {
            closeQuickPanel();
        }
        else {
            refreshPanelButtonStates();
            quickPanelBackdrop.setVisibility(View.VISIBLE);
            wakeHandle();
        }
    }

    public byte getActiveModifierFlags() {
        byte flags = 0;
        for (StickyModifier mod : stickyModifiers) {
            if (mod.state != ModifierState.OFF) {
                flags |= mod.mask;
            }
        }
        return flags;
    }

    public void consumeLatchedModifiers() {
        for (StickyModifier mod : stickyModifiers) {
            if (mod.state == ModifierState.LATCHED) {
                mod.state = ModifierState.OFF;
                callbacks.onStickyModifierChanged(mod.mask, false);
                updateModifierButtonVisual(mod);
            }
        }
    }

    public void releaseAllActiveInputs() {
        for (StickyModifier mod : stickyModifiers) {
            if (mod.state != ModifierState.OFF) {
                mod.state = ModifierState.OFF;
                callbacks.onStickyModifierChanged(mod.mask, false);
                updateModifierButtonVisual(mod);
            }
        }
        if (dragLockActive) {
            dragLockActive = false;
            callbacks.onMouseButtonHold(MouseButtonPacket.BUTTON_LEFT, false);
            updateMouseBarStates();
        }
    }

    public void destroy() {
        handler.removeCallbacksAndMessages(null);
        controlsRoot.getViewTreeObserver().removeOnGlobalLayoutListener(globalLayoutListener);
        controlsRoot.removeOnLayoutChangeListener(rootLayoutChangeListener);
        releaseAllActiveInputs();
    }

    private int dp(int dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, activity.getResources().getDisplayMetrics()));
    }

    private void wakeHandle() {
        handler.removeCallbacks(dimHandleRunnable);
        handleButton.animate().cancel();
        handleButton.setAlpha(HANDLE_ACTIVE_ALPHA);
    }

    private void scheduleHandleDim() {
        handler.removeCallbacks(dimHandleRunnable);
        handler.postDelayed(dimHandleRunnable, HANDLE_DIM_DELAY_MS);
    }

    private void applyHandlePositionFromRatio() {
        int parentW = controlsRoot.getWidth();
        int parentH = controlsRoot.getHeight();
        int btnW = handleButton.getWidth();
        int btnH = handleButton.getHeight();
        if (parentW <= 0 || parentH <= 0 || btnW <= 0 || btnH <= 0) {
            return;
        }

        int maxX = Math.max(0, parentW - btnW);
        int maxY = Math.max(0, parentH - btnH);
        float targetX = Math.max(0, Math.min(maxX, handleXRatio * maxX));
        float targetY = Math.max(0, Math.min(maxY, handleYRatio * maxY));
        handleButton.setX(targetX);
        handleButton.setY(targetY);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupHandleTouch() {
        final int touchSlop = dp(8);
        handleButton.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX, downRawY;
            private float startX, startY;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        wakeHandle();
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startX = v.getX();
                        startY = v.getY();
                        dragging = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                            dragging = true;
                        }
                        if (dragging) {
                            int maxX = Math.max(0, controlsRoot.getWidth() - v.getWidth());
                            int maxY = Math.max(0, controlsRoot.getHeight() - v.getHeight());
                            float newX = Math.max(0, Math.min(maxX, startX + dx));
                            float newY = Math.max(0, Math.min(maxY, startY + dy));
                            v.setX(newX);
                            v.setY(newY);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (dragging) {
                            int maxX = Math.max(1, controlsRoot.getWidth() - v.getWidth());
                            int maxY = Math.max(1, controlsRoot.getHeight() - v.getHeight());
                            handleXRatio = v.getX() / maxX;
                            handleYRatio = v.getY() / maxY;
                            activity.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                                    .edit()
                                    .putFloat(PREF_HANDLE_X_RATIO, handleXRatio)
                                    .putFloat(PREF_HANDLE_Y_RATIO, handleYRatio)
                                    .apply();
                            scheduleHandleDim();
                        }
                        else {
                            toggleQuickPanel();
                        }
                        return true;

                    case MotionEvent.ACTION_CANCEL:
                        scheduleHandleDim();
                        return true;
                }
                return false;
            }
        });
    }

    private void setupQuickPanel(View rootContainer) {
        Button closeBtn = rootContainer.findViewById(R.id.mobilePanelCloseBtn);
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeQuickPanel();
            }
        });

        quickPanelBackdrop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeQuickPanel();
            }
        });

        btnKeyboard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeQuickPanel();
                if (!isExtraKeysVisible()) {
                    showExtraKeysBar(true);
                }
                callbacks.onToggleKeyboard();
            }
        });

        btnExtraKeys.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showExtraKeysBar(!isExtraKeysVisible());
            }
        });

        btnTouchMode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean trackpad = callbacks.onToggleTouchMode();
                Toast.makeText(activity,
                        trackpad ? R.string.toast_switched_to_trackpad : R.string.toast_switched_to_direct_touch,
                        Toast.LENGTH_SHORT).show();
                refreshPanelButtonStates();
                updateMouseBarStates();
            }
        });

        btnMouseBar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean show = mouseBar.getVisibility() != View.VISIBLE;
                mouseBar.setVisibility(show ? View.VISIBLE : View.GONE);
                refreshPanelButtonStates();
                updateMouseBarStates();
            }
        });

        btnOsc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                callbacks.onToggleVirtualController();
                refreshPanelButtonStates();
            }
        });

        btnHoverMode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean hover = callbacks.onToggleHoverMode();
                Toast.makeText(activity,
                        hover ? R.string.toast_hover_mode_on : R.string.toast_hover_mode_off,
                        Toast.LENGTH_SHORT).show();
                refreshPanelButtonStates();
                updateMouseBarStates();
            }
        });

        btnPerf.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                callbacks.onTogglePerfOverlay();
                refreshPanelButtonStates();
            }
        });

        btnDisconnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeQuickPanel();
                callbacks.onDisconnect();
            }
        });

        refreshPanelButtonStates();
    }

    private void refreshPanelButtonStates() {
        setToggleButtonStyle(btnExtraKeys, isExtraKeysVisible());
        btnTouchMode.setText(callbacks.isTrackpadMode()
                ? R.string.mobile_btn_mode_trackpad
                : R.string.mobile_btn_mode_direct);
        setToggleButtonStyle(btnTouchMode, !callbacks.isTrackpadMode());
        setToggleButtonStyle(btnMouseBar, mouseBar.getVisibility() == View.VISIBLE);
        setToggleButtonStyle(btnOsc, callbacks.isVirtualControllerVisible());
        setToggleButtonStyle(btnHoverMode, callbacks.isHoverMode());
        setToggleButtonStyle(btnPerf, callbacks.isPerfOverlayVisible());
    }

    private void setToggleButtonStyle(Button button, boolean active) {
        button.setBackgroundResource(active ? R.drawable.bg_mobile_key_active : R.drawable.bg_mobile_key);
    }

    private Button createBarButton(String label, int minWidthDp, int heightDp) {
        Button btn = new Button(activity);
        btn.setText(label);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        btn.setAllCaps(false);
        btn.setFocusable(false);
        btn.setFocusableInTouchMode(false);
        btn.setMinWidth(0);
        btn.setMinHeight(0);
        btn.setMinimumWidth(0);
        btn.setMinimumHeight(0);
        btn.setPadding(dp(8), dp(2), dp(8), dp(2));
        btn.setBackgroundResource(R.drawable.bg_mobile_key);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(heightDp));
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        btn.setMinimumWidth(dp(minWidthDp));
        btn.setLayoutParams(lp);
        return btn;
    }

    private void addKeyButton(LinearLayout row, String label, final int androidKeyCode) {
        Button btn = createBarButton(label, 38, 32);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                callbacks.onSendKeyTap(androidKeyCode);
            }
        });
        row.addView(btn);
    }

    private void addModifierButton(LinearLayout row, final StickyModifier mod) {
        final Button btn = createBarButton(mod.label, 42, 32);
        mod.button = btn;
        stickyModifiers.add(mod);

        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mod.state == ModifierState.OFF) {
                    mod.state = ModifierState.LATCHED;
                    callbacks.onStickyModifierChanged(mod.mask, true);
                }
                else if (mod.state == ModifierState.LATCHED) {
                    mod.state = ModifierState.LOCKED;
                }
                else {
                    mod.state = ModifierState.OFF;
                    callbacks.onStickyModifierChanged(mod.mask, false);
                }
                updateModifierButtonVisual(mod);
            }
        });

        btn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (mod.state == ModifierState.LOCKED) {
                    mod.state = ModifierState.OFF;
                    callbacks.onStickyModifierChanged(mod.mask, false);
                }
                else {
                    if (mod.state == ModifierState.OFF) {
                        callbacks.onStickyModifierChanged(mod.mask, true);
                    }
                    mod.state = ModifierState.LOCKED;
                }
                updateModifierButtonVisual(mod);
                return true;
            }
        });

        row.addView(btn);
    }

    private void updateModifierButtonVisual(StickyModifier mod) {
        if (mod.button == null) {
            return;
        }
        if (mod.state == ModifierState.LOCKED) {
            mod.button.setBackgroundResource(R.drawable.bg_mobile_key_locked);
        }
        else if (mod.state == ModifierState.LATCHED) {
            mod.button.setBackgroundResource(R.drawable.bg_mobile_key_active);
        }
        else {
            mod.button.setBackgroundResource(R.drawable.bg_mobile_key);
        }
    }

    private void buildExtraKeysBar() {
        Button closeExtraBtn = createBarButton("✕", 30, 32);
        closeExtraBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showExtraKeysBar(false);
            }
        });
        extraMainRow.addView(closeExtraBtn);

        addKeyButton(extraMainRow, "Esc", KeyEvent.KEYCODE_ESCAPE);
        addKeyButton(extraMainRow, "Tab", KeyEvent.KEYCODE_TAB);

        addModifierButton(extraMainRow, new StickyModifier(KeyboardPacket.MODIFIER_CTRL, "Ctrl"));
        addModifierButton(extraMainRow, new StickyModifier(KeyboardPacket.MODIFIER_ALT, "Alt"));
        addModifierButton(extraMainRow, new StickyModifier(KeyboardPacket.MODIFIER_SHIFT, "Shift"));
        addModifierButton(extraMainRow, new StickyModifier(KeyboardPacket.MODIFIER_META, "Win"));

        fnToggleBtn = createBarButton("Fn", 36, 32);
        fnToggleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fnRowVisible = !fnRowVisible;
                extraFnScroll.setVisibility(fnRowVisible ? View.VISIBLE : View.GONE);
                setToggleButtonStyle(fnToggleBtn, fnRowVisible);
            }
        });
        extraMainRow.addView(fnToggleBtn);

        addKeyButton(extraMainRow, "Del", KeyEvent.KEYCODE_FORWARD_DEL);
        addKeyButton(extraMainRow, "⌫", KeyEvent.KEYCODE_DEL);
        addKeyButton(extraMainRow, "↵", KeyEvent.KEYCODE_ENTER);
        addKeyButton(extraMainRow, "←", KeyEvent.KEYCODE_DPAD_LEFT);
        addKeyButton(extraMainRow, "↑", KeyEvent.KEYCODE_DPAD_UP);
        addKeyButton(extraMainRow, "↓", KeyEvent.KEYCODE_DPAD_DOWN);
        addKeyButton(extraMainRow, "→", KeyEvent.KEYCODE_DPAD_RIGHT);
        addKeyButton(extraMainRow, "Home", KeyEvent.KEYCODE_MOVE_HOME);
        addKeyButton(extraMainRow, "End", KeyEvent.KEYCODE_MOVE_END);
        addKeyButton(extraMainRow, "PgUp", KeyEvent.KEYCODE_PAGE_UP);
        addKeyButton(extraMainRow, "PgDn", KeyEvent.KEYCODE_PAGE_DOWN);

        int[] fKeys = new int[] {
                KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_F3, KeyEvent.KEYCODE_F4,
                KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F6, KeyEvent.KEYCODE_F7, KeyEvent.KEYCODE_F8,
                KeyEvent.KEYCODE_F9, KeyEvent.KEYCODE_F10, KeyEvent.KEYCODE_F11, KeyEvent.KEYCODE_F12
        };
        for (int i = 0; i < fKeys.length; i++) {
            addKeyButton(extraFnRow, "F" + (i + 1), fKeys[i]);
        }
        addKeyButton(extraFnRow, "Ins", KeyEvent.KEYCODE_INSERT);
        addKeyButton(extraFnRow, "PrtSc", KeyEvent.KEYCODE_SCREENSHOT);
        addKeyButton(extraFnRow, "Pause", KeyEvent.KEYCODE_BREAK);
    }

    private void buildMouseBar() {
        Button leftBtn = createBarButton(activity.getString(R.string.mobile_mouse_left), 46, 36);
        leftBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (dragLockActive) {
                    dragLockActive = false;
                    callbacks.onMouseButtonHold(MouseButtonPacket.BUTTON_LEFT, false);
                    updateMouseBarStates();
                }
                else {
                    callbacks.onMouseClick(MouseButtonPacket.BUTTON_LEFT);
                }
            }
        });
        mouseBar.addView(leftBtn);

        Button middleBtn = createBarButton(activity.getString(R.string.mobile_mouse_middle), 46, 36);
        middleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                callbacks.onMouseClick(MouseButtonPacket.BUTTON_MIDDLE);
            }
        });
        mouseBar.addView(middleBtn);

        Button rightBtn = createBarButton(activity.getString(R.string.mobile_mouse_right), 46, 36);
        rightBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                callbacks.onMouseClick(MouseButtonPacket.BUTTON_RIGHT);
            }
        });
        mouseBar.addView(rightBtn);

        mouseDragLockBtn = createBarButton(activity.getString(R.string.mobile_mouse_drag_lock), 46, 36);
        mouseDragLockBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dragLockActive = !dragLockActive;
                callbacks.onMouseButtonHold(MouseButtonPacket.BUTTON_LEFT, dragLockActive);
                Toast.makeText(activity,
                        dragLockActive ? R.string.toast_drag_lock_on : R.string.toast_drag_lock_off,
                        Toast.LENGTH_SHORT).show();
                updateMouseBarStates();
            }
        });
        mouseBar.addView(mouseDragLockBtn);

        mouseHoverBtn = createBarButton(activity.getString(R.string.mobile_mouse_hover), 46, 36);
        mouseHoverBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                callbacks.onToggleHoverMode();
                updateMouseBarStates();
                refreshPanelButtonStates();
            }
        });
        mouseBar.addView(mouseHoverBtn);

        updateMouseBarStates();
    }

    private void updateMouseBarStates() {
        if (mouseDragLockBtn != null) {
            setToggleButtonStyle(mouseDragLockBtn, dragLockActive);
        }
        if (mouseHoverBtn != null) {
            mouseHoverBtn.setVisibility(callbacks.isTrackpadMode() ? View.GONE : View.VISIBLE);
            setToggleButtonStyle(mouseHoverBtn, callbacks.isHoverMode());
        }
    }

    private void addShortcutChip(String label, final int[] keyCodes) {
        Button btn = createBarButton(label, 48, 32);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                callbacks.onSendKeyCombo(keyCodes);
                closeQuickPanel();
            }
        });
        shortcutsRow.addView(btn);
    }

    private void buildShortcutsRow() {
        addShortcutChip("Esc", new int[] { KeyEvent.KEYCODE_ESCAPE });
        addShortcutChip("Alt+Tab", new int[] { KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_TAB });
        addShortcutChip("Win+D", new int[] { KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_D });
        addShortcutChip("Ctrl+C", new int[] { KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_C });
        addShortcutChip("Ctrl+V", new int[] { KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_V });
        addShortcutChip("Ctrl+X", new int[] { KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_X });
        addShortcutChip("Ctrl+Z", new int[] { KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_Z });
        addShortcutChip("Ctrl+A", new int[] { KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_A });
        addShortcutChip("Alt+F4", new int[] { KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_F4 });
        addShortcutChip("Ctrl+Alt+Del", new int[] {
                KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_FORWARD_DEL
        });
    }

    private void setupImeInsetsTracking() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            controlsRoot.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    currentImeBottomInset = insets.getInsets(WindowInsets.Type.ime()).bottom;
                    updateExtraKeysBarBottomMargin();
                    return insets;
                }
            });
        }
        controlsRoot.getViewTreeObserver().addOnGlobalLayoutListener(globalLayoutListener);
    }

    private void updateExtraKeysBarBottomMargin() {
        if (extraKeysBar.getVisibility() != View.VISIBLE) {
            return;
        }
        int bottomOffset = currentImeBottomInset;
        if (bottomOffset <= 0) {
            int rootHeight = controlsRoot.getHeight();
            if (rootHeight > 0) {
                Rect visibleFrame = new Rect();
                controlsRoot.getWindowVisibleDisplayFrame(visibleFrame);
                int diff = rootHeight - visibleFrame.bottom;
                if (diff > rootHeight * 0.15f) {
                    bottomOffset = diff;
                }
            }
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) extraKeysBar.getLayoutParams();
        int desiredMargin = bottomOffset + dp(4);
        if (lp.bottomMargin != desiredMargin) {
            lp.bottomMargin = desiredMargin;
            extraKeysBar.setLayoutParams(lp);
        }
    }
}
