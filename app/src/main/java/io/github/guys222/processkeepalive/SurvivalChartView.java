package io.github.guys222.processkeepalive;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 24 小时存活率曲线（纯自定义 Canvas 绘制，无第三方图表库）。
 * 画渐变面积 + 折线 + 末端圆点；数值在 [0,1]，x 轴为 24 个采样点。
 */
public class SurvivalChartView extends View {

    private float[] mValues;
    private int mLineColor = 0xff3ddc84;
    private final float mMin = 0f;
    private final float mMax = 1f;

    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mLinePath = new Path();
    private final Path mAreaPath = new Path();

    public SurvivalChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mFillPaint.setStyle(Paint.Style.FILL);
        mLinePaint.setStyle(Paint.Style.STROKE);
        mLinePaint.setStrokeJoin(Paint.Join.ROUND);
        mLinePaint.setStrokeCap(Paint.Cap.ROUND);
        mDotPaint.setStyle(Paint.Style.FILL);
        mDotPaint.setColor(0xffffffff);
        mRingPaint.setStyle(Paint.Style.STROKE);
    }

    /** 设置数据。values 为 24 个 [0,1] 存活率；lineColor 为折线/面积颜色。 */
    public void setData(float[] values, int lineColor) {
        mValues = values;
        mLineColor = lineColor;
        mLinePaint.setColor(lineColor);
        mLinePaint.setStrokeWidth(2.4f * getDensity());
        invalidate();
    }

    private float getDensity() {
        return getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int hMode = MeasureSpec.getMode(heightMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        if (hMode == MeasureSpec.UNSPECIFIED || hMode == MeasureSpec.AT_MOST) {
            h = Math.max(h, (int) (120 * getDensity()));
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float pad = 6f * getDensity();
        int n = (mValues != null && mValues.length > 1) ? mValues.length : 0;

        if (n == 0) {
            // 无数据：画一条居中基线
            mLinePaint.setColor(mLineColor);
            float y = h / 2f;
            canvas.drawLine(pad, y, w - pad, y, mLinePaint);
            return;
        }

        float usableW = w - 2 * pad;
        float usableH = h - 2 * pad;
        float[] pts = new float[n * 2];
        for (int i = 0; i < n; i++) {
            float v = Math.max(mMin, Math.min(mMax, mValues[i]));
            pts[i * 2] = pad + (i / (float) (n - 1)) * usableW;
            pts[i * 2 + 1] = pad + (1f - (v - mMin) / (mMax - mMin)) * usableH;
        }

        // 面积路径
        mAreaPath.reset();
        mAreaPath.moveTo(pts[0], pts[1]);
        for (int i = 1; i < n; i++) mAreaPath.lineTo(pts[i * 2], pts[i * 2 + 1]);
        mAreaPath.lineTo(pts[(n - 1) * 2], h - pad);
        mAreaPath.lineTo(pts[0], h - pad);
        mAreaPath.close();
        mFillPaint.setShader(new LinearGradient(0, pad, 0, h - pad,
                (mLineColor & 0x00ffffff) | 0x59000000, // 顶部约 35% 不透明
                (mLineColor & 0x00ffffff) | 0x00000000, // 底部透明
                Shader.TileMode.CLAMP));
        canvas.drawPath(mAreaPath, mFillPaint);

        // 折线
        mLinePath.reset();
        mLinePath.moveTo(pts[0], pts[1]);
        for (int i = 1; i < n; i++) mLinePath.lineTo(pts[i * 2], pts[i * 2 + 1]);
        mLinePaint.setColor(mLineColor);
        canvas.drawPath(mLinePath, mLinePaint);

        // 末端圆点
        float ex = pts[(n - 1) * 2];
        float ey = pts[(n - 1) * 2 + 1];
        mDotPaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(ex, ey, 3.5f * getDensity(), mDotPaint);
        // 圆点描边用 lineColor
        mRingPaint.setStrokeWidth(1.5f * getDensity());
        mRingPaint.setColor(mLineColor);
        canvas.drawCircle(ex, ey, 3.5f * getDensity(), mRingPaint);
    }
}
