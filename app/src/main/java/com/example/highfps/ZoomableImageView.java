package com.example.highfps;

import android.content.Context;
import android.graphics.Matrix;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/**
 * ImageView with pinch-to-zoom and smooth one/two-finger panning.
 *
 * Used for the 4 result stages (Original / Enhanced / Threshold / Final Vectors) in
 * AnalysisActivity so the user can pinch in/out and drag around a frame to inspect
 * streaks and vectors closely. No external dependency required.
 *
 * Usage: just declare <com.example.highfps.ZoomableImageView .../> in layout XML in place
 * of <ImageView>, keep scaleType="matrix" (set automatically), and call setImageBitmap/
 * setImageDrawable as normal - zoom/pan state resets cleanly on every new image so a fresh
 * frame always starts fitted to the view.
 */
public class ZoomableImageView extends ImageView {

    private static final float MIN_SCALE = 1f;
    private static final float MAX_SCALE = 8f;

    private final Matrix matrix = new Matrix();
    private final float[] matrixValues = new float[9];

    private float baseScale = 1f;
    private float currentScale = 1f;

    private float lastTouchX, lastTouchY;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private boolean isPanning = false;

    private ScaleGestureDetector scaleGestureDetector;
    private GestureDetector doubleTapDetector;

    private int lastDrawableWidth = -1;
    private int lastDrawableHeight = -1;

    public ZoomableImageView(Context context) {
        super(context);
        init(context);
    }

    public ZoomableImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ZoomableImageView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context);
    }

    private void init(Context context) {
        setScaleType(ScaleType.MATRIX);

        scaleGestureDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float newScale = currentScale * detector.getScaleFactor();
                newScale = Math.max(baseScale * MIN_SCALE, Math.min(newScale, baseScale * MAX_SCALE));
                float scaleFactor = newScale / currentScale;
                currentScale = newScale;

                matrix.postScale(scaleFactor, scaleFactor, detector.getFocusX(), detector.getFocusY());
                constrainMatrix();
                setImageMatrix(matrix);
                return true;
            }
        });

        // Double-tap to quickly reset zoom, a natural companion to pinch-zoom.
        doubleTapDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                resetZoom();
                return true;
            }
        });
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        fitImageToView();
    }

    @Override
    public void setImageBitmap(android.graphics.Bitmap bm) {
        super.setImageBitmap(bm);
        fitImageToView();
    }

    @Override
    public void setImageDrawable(android.graphics.drawable.Drawable drawable) {
        super.setImageDrawable(drawable);
        fitImageToView();
    }

    /** Fits the current drawable to the view bounds (like fitCenter) and resets zoom/pan. */
    private void fitImageToView() {
        android.graphics.drawable.Drawable d = getDrawable();
        int viewWidth = getWidth();
        int viewHeight = getHeight();
        if (d == null || viewWidth == 0 || viewHeight == 0) return;

        int drawableWidth = d.getIntrinsicWidth();
        int drawableHeight = d.getIntrinsicHeight();
        if (drawableWidth <= 0 || drawableHeight <= 0) return;

        // Only re-fit when the view size or the underlying image actually changed, so we
        // don't stomp on an in-progress pinch/pan gesture.
        if (drawableWidth == lastDrawableWidth && drawableHeight == lastDrawableHeight
                && Math.abs(currentScale - baseScale) > 0.001f) {
            return;
        }
        lastDrawableWidth = drawableWidth;
        lastDrawableHeight = drawableHeight;

        float scaleX = (float) viewWidth / drawableWidth;
        float scaleY = (float) viewHeight / drawableHeight;
        baseScale = Math.min(scaleX, scaleY);
        currentScale = baseScale;

        float dx = (viewWidth - drawableWidth * baseScale) / 2f;
        float dy = (viewHeight - drawableHeight * baseScale) / 2f;

        matrix.reset();
        matrix.postScale(baseScale, baseScale);
        matrix.postTranslate(dx, dy);
        setImageMatrix(matrix);
    }

    /** Resets to the fitted, non-zoomed view - triggered by double-tap. */
    private void resetZoom() {
        lastDrawableWidth = -1;
        lastDrawableHeight = -1;
        fitImageToView();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleGestureDetector.onTouchEvent(event);
        doubleTapDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                activePointerId = event.getPointerId(0);
                isPanning = true;
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                // A second finger has just gone down: re-anchor panning to the primary pointer
                // so the image doesn't jump when the pinch gesture begins.
                int pointerIndex = event.getActionIndex();
                lastTouchX = event.getX(pointerIndex == 0 ? 1 : 0);
                lastTouchY = event.getY(pointerIndex == 0 ? 1 : 0);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (isPanning && !scaleGestureDetector.isInProgress()) {
                    int pointerIndex = event.findPointerIndex(activePointerId);
                    if (pointerIndex < 0) break;
                    float x = event.getX(pointerIndex);
                    float y = event.getY(pointerIndex);
                    float dx = x - lastTouchX;
                    float dy = y - lastTouchY;
                    matrix.postTranslate(dx, dy);
                    constrainMatrix();
                    setImageMatrix(matrix);
                    lastTouchX = x;
                    lastTouchY = y;
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int pointerIndex = event.getActionIndex();
                int pointerId = event.getPointerId(pointerIndex);
                if (pointerId == activePointerId) {
                    int newIndex = pointerIndex == 0 ? 1 : 0;
                    if (newIndex < event.getPointerCount()) {
                        lastTouchX = event.getX(newIndex);
                        lastTouchY = event.getY(newIndex);
                        activePointerId = event.getPointerId(newIndex);
                    }
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                isPanning = false;
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                break;
            }
        }
        return true;
    }

    /** Keeps the image from being panned/zoomed entirely off-screen. */
    private void constrainMatrix() {
        android.graphics.drawable.Drawable d = getDrawable();
        if (d == null) return;

        int viewWidth = getWidth();
        int viewHeight = getHeight();
        int drawableWidth = d.getIntrinsicWidth();
        int drawableHeight = d.getIntrinsicHeight();
        if (viewWidth == 0 || viewHeight == 0 || drawableWidth <= 0 || drawableHeight <= 0) return;

        matrix.getValues(matrixValues);
        float curScaleX = matrixValues[Matrix.MSCALE_X];
        float transX = matrixValues[Matrix.MTRANS_X];
        float transY = matrixValues[Matrix.MTRANS_Y];

        // Clamp scale itself defensively (belt-and-braces alongside onScale's clamp).
        float minAllowedScale = baseScale * MIN_SCALE;
        float maxAllowedScale = baseScale * MAX_SCALE;
        if (curScaleX < minAllowedScale || curScaleX > maxAllowedScale) {
            float clamped = Math.max(minAllowedScale, Math.min(curScaleX, maxAllowedScale));
            float factor = clamped / curScaleX;
            matrix.postScale(factor, factor, viewWidth / 2f, viewHeight / 2f);
            matrix.getValues(matrixValues);
            curScaleX = matrixValues[Matrix.MSCALE_X];
            transX = matrixValues[Matrix.MTRANS_X];
            transY = matrixValues[Matrix.MTRANS_Y];
        }

        float scaledWidth = drawableWidth * curScaleX;
        float scaledHeight = drawableHeight * curScaleX;

        float minTransX, maxTransX;
        if (scaledWidth <= viewWidth) {
            minTransX = maxTransX = (viewWidth - scaledWidth) / 2f;
        } else {
            minTransX = viewWidth - scaledWidth;
            maxTransX = 0f;
        }

        float minTransY, maxTransY;
        if (scaledHeight <= viewHeight) {
            minTransY = maxTransY = (viewHeight - scaledHeight) / 2f;
        } else {
            minTransY = viewHeight - scaledHeight;
            maxTransY = 0f;
        }

        float fixedTransX = Math.max(minTransX, Math.min(transX, maxTransX));
        float fixedTransY = Math.max(minTransY, Math.min(transY, maxTransY));

        if (fixedTransX != transX || fixedTransY != transY) {
            matrix.postTranslate(fixedTransX - transX, fixedTransY - transY);
        }
    }
}
