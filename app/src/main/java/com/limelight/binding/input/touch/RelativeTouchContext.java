package com.limelight.binding.input.touch;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.preferences.PreferenceConfiguration;

public class RelativeTouchContext implements TouchContext {
    private int lastTouchX = 0;
    private int lastTouchY = 0;
    private int originalTouchX = 0;
    private int originalTouchY = 0;
    private long originalTouchTime = 0;
    private long lastSingleTapUpTime = 0;
    private int lastSingleTapUpX = 0;
    private int lastSingleTapUpY = 0;
    private boolean leftTapReleasePending = false;
    private boolean cancelled;
    private boolean confirmedMove;
    private boolean confirmedDrag;
    private boolean confirmedScroll;
    private double distanceMoved;
    private double xFactor, yFactor;
    private double residualX = 0;
    private double residualY = 0;
    private double residualScrollX = 0;
    private double residualScrollY = 0;
    private int pointerCount;
    private int maxPointerCountInGesture;

    private final NvConnection conn;
    private final int actionIndex;
    private final int referenceWidth;
    private final int referenceHeight;
    private final View targetView;
    private final PreferenceConfiguration prefConfig;
    private final Handler handler;

    private final int tapMovementThreshold;
    private final int tapDistanceThreshold;
    private final int doubleTapDragDistanceThreshold;

    private final Runnable dragTimerRunnable = new Runnable() {
        @Override
        public void run() {
            // Check if someone already set move
            if (confirmedMove) {
                return;
            }

            // The drag should only be processed for the primary finger
            if (actionIndex != maxPointerCountInGesture - 1) {
                return;
            }

            // We haven't been cancelled before the timer expired so begin dragging
            confirmedDrag = true;
            conn.sendMouseButtonDown(getMouseButtonIndex());
        }
    };

    // Indexed by MouseButtonPacket.BUTTON_XXX - 1
    private final Runnable[] buttonUpRunnables = new Runnable[] {
            new Runnable() {
                @Override
                public void run() {
                    leftTapReleasePending = false;
                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                }
            },
            new Runnable() {
                @Override
                public void run() {
                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE);
                }
            },
            new Runnable() {
                @Override
                public void run() {
                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                }
            },
            new Runnable() {
                @Override
                public void run() {
                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X1);
                }
            },
            new Runnable() {
                @Override
                public void run() {
                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X2);
                }
            }
    };

    private static final int TAP_TIME_THRESHOLD = 250;
    private static final int DRAG_TIME_THRESHOLD = 650;
    private static final int DOUBLE_TAP_DRAG_TIME_THRESHOLD = 320;

    private static final int SCROLL_SPEED_FACTOR = 5;

    public RelativeTouchContext(NvConnection conn, int actionIndex,
                                int referenceWidth, int referenceHeight,
                                View view, PreferenceConfiguration prefConfig)
    {
        this.conn = conn;
        this.actionIndex = actionIndex;
        this.referenceWidth = referenceWidth;
        this.referenceHeight = referenceHeight;
        this.targetView = view;
        this.prefConfig = prefConfig;
        this.handler = new Handler(Looper.getMainLooper());

        float density = view.getResources().getDisplayMetrics().density;
        this.tapMovementThreshold = Math.max(12, Math.round(8 * density));
        this.tapDistanceThreshold = Math.max(16, Math.round(10 * density));
        this.doubleTapDragDistanceThreshold = Math.max(24, Math.round(24 * density));
    }

    @Override
    public int getActionIndex()
    {
        return actionIndex;
    }

    private boolean isWithinTapBounds(int touchX, int touchY)
    {
        int xDelta = Math.abs(touchX - originalTouchX);
        int yDelta = Math.abs(touchY - originalTouchY);
        return xDelta <= tapMovementThreshold &&
                yDelta <= tapMovementThreshold;
    }

    private boolean isTap(long eventTime)
    {
        if (confirmedDrag || confirmedMove || confirmedScroll) {
            return false;
        }

        // If this input wasn't the last finger down, do not report
        // a tap. This ensures we don't report duplicate taps for each
        // finger on a multi-finger tap gesture
        if (actionIndex + 1 != maxPointerCountInGesture) {
            return false;
        }

        long timeDelta = eventTime - originalTouchTime;
        return isWithinTapBounds(lastTouchX, lastTouchY) && timeDelta <= TAP_TIME_THRESHOLD;
    }

    private byte getMouseButtonIndex()
    {
        if (actionIndex == 1) {
            return MouseButtonPacket.BUTTON_RIGHT;
        }
        else {
            return MouseButtonPacket.BUTTON_LEFT;
        }
    }

    @Override
    public boolean touchDownEvent(int eventX, int eventY, long eventTime, boolean isNewFinger)
    {
        int width = Math.max(1, targetView.getWidth());
        int height = Math.max(1, targetView.getHeight());

        // Get the view dimensions to scale inputs on this touch
        xFactor = referenceWidth / (double)width;
        yFactor = referenceHeight / (double)height;

        originalTouchX = lastTouchX = eventX;
        originalTouchY = lastTouchY = eventY;
        residualX = residualY = 0;
        residualScrollX = residualScrollY = 0;

        if (isNewFinger) {
            maxPointerCountInGesture = pointerCount;
            originalTouchTime = eventTime;
            cancelled = confirmedDrag = confirmedMove = confirmedScroll = false;
            distanceMoved = 0;

            if (actionIndex == 0) {
                boolean isDoubleTapDrag = prefConfig.doubleTapDrag &&
                        pointerCount == 1 &&
                        lastSingleTapUpTime != 0 &&
                        (eventTime - lastSingleTapUpTime) <= DOUBLE_TAP_DRAG_TIME_THRESHOLD &&
                        Math.hypot(eventX - lastSingleTapUpX, eventY - lastSingleTapUpY) <= doubleTapDragDistanceThreshold;

                if (isDoubleTapDrag) {
                    lastSingleTapUpTime = 0;
                    if (leftTapReleasePending) {
                        handler.removeCallbacks(buttonUpRunnables[MouseButtonPacket.BUTTON_LEFT - 1]);
                        leftTapReleasePending = false;
                        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                    }
                    confirmedDrag = true;
                    conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                }
                else {
                    // Start the timer for engaging a drag
                    startDragTimer();
                }
            }
        }

        return true;
    }

    @Override
    public void touchUpEvent(int eventX, int eventY, long eventTime)
    {
        if (cancelled) {
            return;
        }

        // Cancel the drag timer
        cancelDragTimer();

        byte buttonIndex = getMouseButtonIndex();

        if (confirmedDrag) {
            // Raise the button after a drag
            conn.sendMouseButtonUp(buttonIndex);
        }
        else if (isTap(eventTime))
        {
            if (buttonIndex == MouseButtonPacket.BUTTON_LEFT && maxPointerCountInGesture == 1) {
                lastSingleTapUpTime = eventTime;
                lastSingleTapUpX = eventX;
                lastSingleTapUpY = eventY;
                leftTapReleasePending = true;
            }

            // Lower the mouse button
            conn.sendMouseButtonDown(buttonIndex);

            // Release the mouse button in 100ms to allow for apps that use polling
            // to detect mouse button presses.
            Runnable buttonUpRunnable = buttonUpRunnables[buttonIndex - 1];
            handler.removeCallbacks(buttonUpRunnable);
            handler.postDelayed(buttonUpRunnable, 100);
        }
    }

    private void startDragTimer() {
        cancelDragTimer();
        handler.postDelayed(dragTimerRunnable, DRAG_TIME_THRESHOLD);
    }

    private void cancelDragTimer() {
        handler.removeCallbacks(dragTimerRunnable);
    }

    private void checkForConfirmedMove(int eventX, int eventY) {
        // If we've already confirmed something, get out now
        if (confirmedMove || confirmedDrag) {
            return;
        }

        // If it leaves the tap bounds before the drag time expires, it's a move.
        if (!isWithinTapBounds(eventX, eventY)) {
            confirmedMove = true;
            cancelDragTimer();
            return;
        }

        // Check if we've exceeded the maximum distance moved
        distanceMoved += Math.sqrt(Math.pow(eventX - lastTouchX, 2) + Math.pow(eventY - lastTouchY, 2));
        if (distanceMoved >= tapDistanceThreshold) {
            confirmedMove = true;
            cancelDragTimer();
            return;
        }
    }

    private void checkForConfirmedScroll() {
        // Enter scrolling mode if we've already left the tap zone
        // and we have 2 fingers on screen. Leave scroll mode if
        // we no longer have 2 fingers on screen
        confirmedScroll = (actionIndex == 0 && pointerCount == 2 && confirmedMove);
    }

    @Override
    public boolean touchMoveEvent(int eventX, int eventY, long eventTime)
    {
        if (cancelled) {
            return true;
        }

        if (eventX != lastTouchX || eventY != lastTouchY)
        {
            checkForConfirmedMove(eventX, eventY);
            checkForConfirmedScroll();

            // We only send moves and drags for the primary touch point
            if (actionIndex == 0) {
                if (pointerCount == 2) {
                    if (confirmedScroll) {
                        double scrollScale = prefConfig.touchpadScrollSpeed / 100.0;
                        double scrollSign = prefConfig.invertTouchScroll ? -1.0 : 1.0;

                        double rawScrollY = (eventY - lastTouchY) * yFactor * SCROLL_SPEED_FACTOR * scrollScale * scrollSign + residualScrollY;
                        double rawScrollX = -(eventX - lastTouchX) * xFactor * SCROLL_SPEED_FACTOR * scrollScale * scrollSign + residualScrollX;

                        if (Math.abs(rawScrollY) >= Math.abs(rawScrollX)) {
                            int scrollY = (int) Math.round(rawScrollY);
                            residualScrollY = rawScrollY - scrollY;
                            residualScrollX = 0;
                            if (scrollY != 0) {
                                conn.sendMouseHighResScroll((short) scrollY);
                            }
                        }
                        else {
                            int scrollX = (int) Math.round(rawScrollX);
                            residualScrollX = rawScrollX - scrollX;
                            residualScrollY = 0;
                            if (scrollX != 0) {
                                conn.sendMouseHighResHScroll((short) scrollX);
                            }
                        }
                    }
                } else {
                    double sensitivityScale = prefConfig.touchpadSensitivity / 100.0;
                    double rawDeltaX = (eventX - lastTouchX) * xFactor * sensitivityScale + residualX;
                    double rawDeltaY = (eventY - lastTouchY) * yFactor * sensitivityScale + residualY;

                    int deltaX = (int) Math.round(rawDeltaX);
                    int deltaY = (int) Math.round(rawDeltaY);
                    residualX = rawDeltaX - deltaX;
                    residualY = rawDeltaY - deltaY;

                    if (deltaX != 0 || deltaY != 0) {
                        if (prefConfig.absoluteMouseMode) {
                            conn.sendMouseMoveAsMousePosition(
                                    (short) deltaX,
                                    (short) deltaY,
                                    (short) targetView.getWidth(),
                                    (short) targetView.getHeight());
                        }
                        else {
                            conn.sendMouseMove((short) deltaX, (short) deltaY);
                        }
                    }
                }
            }

            lastTouchX = eventX;
            lastTouchY = eventY;
        }

        return true;
    }

    @Override
    public void cancelTouch() {
        cancelled = true;

        // Cancel the drag timer
        cancelDragTimer();

        // If it was a confirmed drag, we'll need to raise the button now
        if (confirmedDrag) {
            conn.sendMouseButtonUp(getMouseButtonIndex());
            confirmedDrag = false;
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setPointerCount(int pointerCount) {
        if (this.pointerCount != pointerCount) {
            residualX = residualY = 0;
            residualScrollX = residualScrollY = 0;
        }
        this.pointerCount = pointerCount;

        if (pointerCount > maxPointerCountInGesture) {
            maxPointerCountInGesture = pointerCount;
        }
    }
}
