package io.github.guys222.processkeepalive;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * 预览稿 .sw 的自绘实现：48×28dp 胶囊轨道 + 22dp 白色圆点。
 * <ul>
 *   <li>关闭：轨道纯色（.sw 的 #3a3450）</li>
 *   <li>开启（普通）：轨道 ok 绿（.sw.on）</li>
 *   <li>开启（accent）：轨道紫→粉 90° 渐变（.sw.acc.on）</li>
 * </ul>
 * 圆点位移带 220ms 减速动画，与预览稿 transition:.22s 对齐。
 */
public class SwitchCapsuleView extends View {

    /** 预览稿尺寸（dp）：48×28 轨道，22 圆点，3 内边距 */
    private static final float TRACK_W_DP = 48f;
    private static final float TRACK_H_DP = 28f;
    private static final float THUMB_DP = 22f;
    private static final float INSET_DP = 3f;

    private boolean mChecked;
    /** 开启时是否用紫粉渐变轨道（对应预览稿 .sw.acc）。 */
    private boolean mAccent;
    private boolean mAnimating;

    /** 圆点当前位置（0=左，1=右），用于动画插值。 */
    private float mPos = 0f;

    private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mTrackRect = new RectF();

    @Nullable
    private OnCheckedChangeListener mListener;

    /** 与 CompoundButton 语义一致的回调，便于平滑替换 SwitchMaterial。 */
    public interface OnCheckedChangeListener {
        void onCheckedChanged(SwitchCapsuleView view, boolean isChecked);
    }

    public SwitchCapsuleView(Context context) {
        this(context, null);
    }

    public SwitchCapsuleView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SwitchCapsuleView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        TypedArray ta = context.obtainStyledAttributes(
                attrs, R.styleable.SwitchCapsuleView, defStyleAttr, 0);
        try {
            mChecked = ta.getBoolean(R.styleable.SwitchCapsuleView_scvChecked, false);
            mAccent = ta.getBoolean(R.styleable.SwitchCapsuleView_scvAccent, false);
        } finally {
            ta.recycle();
        }
        mPos = mChecked ? 1f : 0f;

        mThumbPaint.setColor(0xffffffff);
        // 圆点投影：预览稿 box-shadow:0 1px 3px rgba(0,0,0,.3)
        mShadowPaint.setColor(0x4d000000);
        mShadowPaint.setStyle(Paint.Style.FILL);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            setElevation(0f);
        }
        setClickable(true);
        setFocusable(true);
    }

    /** 设置开启时的轨道配色。 */
    public void setAccent(boolean accent) {
        if (mAccent == accent) return;
        mAccent = accent;
        invalidate();
    }

    /** 当前是否为 accent 渐变模式。 */
    public boolean isAccent() {
        return mAccent;
    }

    public void setChecked(boolean checked) {
        setChecked(checked, false);
    }

    /**
     * 设置选中态。
     *
     * @param animate 是否播放动画；代码回填状态时应传 false
     */
    public void setChecked(boolean checked, boolean animate) {
        if (mChecked == checked && mPos == (checked ? 1f : 0f)) return;
        mChecked = checked;
        if (animate && isAttachedToWindow()) {
            animateTo(checked ? 1f : 0f);
        } else {
            mPos = checked ? 1f : 0f;
            invalidate();
        }
    }

    public boolean isChecked() {
        return mChecked;
    }

    public void setOnCheckedChangeListener(@Nullable OnCheckedChangeListener l) {
        mListener = l;
    }

    private void animateTo(float target) {
        if (mAnimating) {
            mAnimating = false;
        }
        ValueAnimator va = ValueAnimator.ofFloat(mPos, target);
        va.setDuration(220); // 预览稿 transition:.22s
        va.setInterpolator(new DecelerateInterpolator());
        va.addUpdateListener(a -> {
            mPos = (float) a.getAnimatedValue();
            invalidate();
        });
        va.start();
        mAnimating = true;
        // 动画期间 mPos 直接驱动绘制；不依赖外部持有 animator 引用
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                mAnimating = false;
            }
        });
    }

    /** 程序化切换（会触发回调）。 */
    public void toggle() {
        setChecked(!mChecked, true);
        if (mListener != null) mListener.onCheckedChanged(this, mChecked);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        // 禁用态是纯视觉约定（灰化），必须主动触发重绘，否则看起来仍可调
        invalidate();
    }

    @Override
    public boolean performClick() {
        // 双保险：View 框架对 disabled 可点击 View 本就不派发 performClick，
        // 这里再挡一层，避免任何自定义调用路径绕过框架判定。
        if (!isEnabled()) return super.performClick();
        toggle();
        return super.performClick();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = (int) (TRACK_W_DP * getDensity()) + getPaddingLeft() + getPaddingRight();
        int h = (int) (TRACK_H_DP * getDensity()) + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(
                resolveSize(w, widthMeasureSpec),
                resolveSize(h, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float d = getDensity();
        float trackW = TRACK_W_DP * d;
        float trackH = TRACK_H_DP * d;
        float radius = trackH / 2f;

        float left = getPaddingLeft() + (getWidth() - getPaddingLeft() - getPaddingRight() - trackW) / 2f;
        float top = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom() - trackH) / 2f;
        mTrackRect.set(left, top, left + trackW, top + trackH);

        // 禁用态灰化：与档位行 setAlpha(0.38f) 同一口径。必须在所有 draw 之前设置，
        // 之前 onDraw 不看 isEnabled()，导致「核心保活开启 → 常驻锁定」时
        // 开关看起来跟正常态一模一样，用户以为还能调（实际点了没反应）。
        final float f = isEnabled() ? 1f : 0.38f;
        mTrackPaint.setAlpha((int) (255 * f));
        mThumbPaint.setAlpha((int) (255 * f));
        mShadowPaint.setAlpha((int) (0x4d * f));   // 0x4d = 正常态投影自身的 alpha

        // 轨道
        if (mChecked && mAccent) {
            // 预览稿 .sw.acc.on: linear-gradient(90deg,accent,accent2)
            mTrackPaint.setShader(new LinearGradient(
                    mTrackRect.left, 0f, mTrackRect.right, 0f,
                    ContextCompat.getColor(getContext(), R.color.colorPrimary),
                    ContextCompat.getColor(getContext(), R.color.colorPrimary2),
                    Shader.TileMode.CLAMP));
        } else {
            mTrackPaint.setShader(null);
            mTrackPaint.setColor(mChecked
                    ? ContextCompat.getColor(getContext(), R.color.colorOk)
                    : ContextCompat.getColor(getContext(), R.color.switchTrack));
        }
        canvas.drawRoundRect(mTrackRect, radius, radius, mTrackPaint);

        // 圆点：left 3dp → 23dp（预览稿 .sw::after / .sw.on::after）
        float thumbR = THUMB_DP * d / 2f;
        float inset = INSET_DP * d;
        float travel = trackW - THUMB_DP * d - inset * 2f;
        float cx = mTrackRect.left + inset + thumbR + travel * mPos;
        float cy = mTrackRect.centerY();

        // 软投影（无硬件模糊，用一圈低透明度圆近似）
        canvas.drawCircle(cx, cy + d, thumbR * 0.98f, mShadowPaint);
        canvas.drawCircle(cx, cy, thumbR, mThumbPaint);
    }

    private float getDensity() {
        return getResources().getDisplayMetrics().density;
    }
}
