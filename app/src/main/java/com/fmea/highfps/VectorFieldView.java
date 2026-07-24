package com.fmea.highfps;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class VectorFieldView extends View {
    private List<VectorData> vectors = new ArrayList<>();
    private Paint vectorPaint;
    private Paint textPaint;

    private float maxMagnitude = 1.0f;
    private float minMagnitude = 0.0f;

    public VectorFieldView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        vectorPaint = new Paint();
        vectorPaint.setAntiAlias(true);
        vectorPaint.setStrokeWidth(4f);
        vectorPaint.setStyle(Paint.Style.STROKE);

        textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(32f);
        textPaint.setAntiAlias(true);
    }

    public void setVectors(List<VectorData> vectorList) {
        this.vectors = vectorList;
        if (vectorList != null && !vectorList.isEmpty()) {
            maxMagnitude = 0.001f;
            minMagnitude = Float.MAX_VALUE;
            for (VectorData v : vectorList) {
                if (v.magnitude > maxMagnitude) maxMagnitude = v.magnitude;
                if (v.magnitude < minMagnitude) minMagnitude = v.magnitude;
            }
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (vectors == null || vectors.isEmpty()) {
            canvas.drawText("No vector fields to display.", 50, getHeight() / 2, textPaint);
            return;
        }

        // Draw background reference grid mapping flow boundaries
        drawGrid(canvas);

        float arrowScaleFactor = 4.0f; // Multiplier to make velocity vectors visually apparent

        for (VectorData v : vectors) {
            float startX = v.x;
            float startY = v.y;
            float endX = startX + (v.u * arrowScaleFactor);
            float endY = startY + (v.v * arrowScaleFactor);

            int color = getJetColor(v.magnitude);
            vectorPaint.setColor(color);

            // Draw primary velocity vector line
            canvas.drawLine(startX, startY, endX, endY, vectorPaint);

            // Calculate arrowheads for the quiver mapping
            drawArrowHead(canvas, startX, startY, endX, endY, vectorPaint);
        }
    }

    private void drawGrid(Canvas canvas) {
        Paint gridPaint = new Paint();
        gridPaint.setColor(Color.argb(40, 255, 255, 255));
        gridPaint.setStrokeWidth(2f);
        int step = 100;
        for (int i = 0; i < getWidth(); i += step) {
            canvas.drawLine(i, 0, i, getHeight(), gridPaint);
        }
        for (int j = 0; j < getHeight(); j += step) {
            canvas.drawLine(0, j, getWidth(), j, gridPaint);
        }
    }

    private void drawArrowHead(Canvas canvas, float x1, float y1, float x2, float y2, Paint paint) {
        float arrowHeadLength = 12f;
        float angle = (float) Math.atan2(y2 - y1, x2 - x1);

        float x3 = (float) (x2 - arrowHeadLength * Math.cos(angle - Math.PI / 6));
        float y3 = (float) (y2 - arrowHeadLength * Math.sin(angle - Math.PI / 6));
        float x4 = (float) (x2 - arrowHeadLength * Math.cos(angle + Math.PI / 6));
        float y4 = (float) (y2 - arrowHeadLength * Math.sin(angle + Math.PI / 6));

        canvas.drawLine(x2, y2, x3, y3, paint);
        canvas.drawLine(x2, y2, x4, y4, paint);
    }

    private int getJetColor(float magnitude) {
        if (maxMagnitude == minMagnitude) return Color.BLUE;
        float normalized = (magnitude - minMagnitude) / (maxMagnitude - minMagnitude);

        // Clamp bounds
        normalized = Math.max(0f, Math.min(1f, normalized));

        int r = (int) (Math.max(0, 255 * (2 * normalized - 1)));
        int g = (int) (Math.max(0, 255 * (1 - 2 * Math.abs(normalized - 0.5f))));
        int b = (int) (Math.max(0, 255 * (1 - 2 * normalized)));

        return Color.rgb(r, g, b);
    }
}