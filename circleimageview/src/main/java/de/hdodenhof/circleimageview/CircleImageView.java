/*
 * Copyright 2014 - 2020 Henning Dodenhof
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.hdodenhof.circleimageview;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import androidx.annotation.ColorInt;
import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

@SuppressWarnings("UnusedDeclaration")
public class CircleImageView extends ImageView {
    private static final ScaleType SCALE_TYPE = ScaleType.CENTER_CROP;
    private static final Bitmap.Config BITMAP_CONFIG = Bitmap.Config.ARGB_8888;
    private static final int COLORDRAWABLE_DIMENSION = 2;
    private static final int DEFAULT_BORDER_WIDTH = 0;
    private static final int DEFAULT_BORDER_COLOR = Color.BLACK;
    private static final int DEFAULT_CIRCLE_BACKGROUND_COLOR = Color.TRANSPARENT;
    private static final int DEFAULT_IMAGE_ALPHA = 255;
    private static final boolean DEFAULT_BORDER_OVERLAY = false;

    private final RectF mDrawableRect = new RectF();
    private final RectF mBorderRect = new RectF();

    private final Matrix mShaderMatrix = new Matrix();
    private final Paint mBitmapPaint = new Paint();
    private final Paint mBorderPaint = new Paint();
    private final Paint mCircleBackgroundPaint = new Paint();

    private int mBorderColor = DEFAULT_BORDER_COLOR;
    private int mBorderWidth = DEFAULT_BORDER_WIDTH;
    private int mCircleBackgroundColor = DEFAULT_CIRCLE_BACKGROUND_COLOR;
    private int mImageAlpha = DEFAULT_IMAGE_ALPHA;

    private Bitmap mBitmap;
    private Canvas mBitmapCanvas;

    private float mDrawableRadius;
    private float mBorderRadius;

    private ColorFilter mColorFilter;

    private boolean mInitialized;
    private boolean mRebuildShader;
    private boolean mDrawableDirty;

    private boolean mBorderOverlay;
    private boolean mDisableCircularTransformation;

    public CircleImageView(Context context) {
        super(context);

        init();
    }

    public CircleImageView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CircleImageView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.CircleImageView, defStyle, 0);
        mBorderWidth = a.getDimensionPixelSize(R.styleable.CircleImageView_civ_border_width, DEFAULT_BORDER_WIDTH);
        mBorderColor = a.getColor(R.styleable.CircleImageView_civ_border_color, DEFAULT_BORDER_COLOR);
        mBorderOverlay = a.getBoolean(R.styleable.CircleImageView_civ_border_overlay, DEFAULT_BORDER_OVERLAY);
        mCircleBackgroundColor = a.getColor(R.styleable.CircleImageView_civ_circle_background_color, DEFAULT_CIRCLE_BACKGROUND_COLOR);
        a.recycle();
        init();
    }

    private void init() {
        mInitialized = true;
        super.setScaleType(SCALE_TYPE);
        mBitmapPaint.setAntiAlias(true);
        mBitmapPaint.setDither(true);
        mBitmapPaint.setFilterBitmap(true);
        mBitmapPaint.setAlpha(mImageAlpha);
        mBitmapPaint.setColorFilter(mColorFilter);
        mBorderPaint.setStyle(Paint.Style.STROKE);
        mBorderPaint.setAntiAlias(true);
        mBorderPaint.setColor(mBorderColor);
        mBorderPaint.setStrokeWidth(mBorderWidth);
        mCircleBackgroundPaint.setStyle(Paint.Style.FILL);
        mCircleBackgroundPaint.setAntiAlias(true);
        mCircleBackgroundPaint.setColor(mCircleBackgroundColor);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // 设置CircleImageView控件轮廓.
            setOutlineProvider(new OutlineProvider());
        }
    }

    @Override
    public void setScaleType(ScaleType scaleType) {
        if (scaleType != SCALE_TYPE) {
            throw new IllegalArgumentException(String.format("ScaleType %s not supported.", scaleType));
        }
    }

    @Override
    public void setAdjustViewBounds(boolean adjustViewBounds) {
        if (adjustViewBounds) {
            throw new IllegalArgumentException("adjustViewBounds not supported.");
        }
    }

    @SuppressLint("CanvasSize")
    @Override
    protected void onDraw(Canvas canvas) {
        // mDisableCircularTransformation 就会默认原图, 不会转为圆形图片了.
        if (mDisableCircularTransformation) {
            super.onDraw(canvas);
            return;
        }
        // 绘制纯色背景
        if (mCircleBackgroundColor != Color.TRANSPARENT) {
            canvas.drawCircle(mDrawableRect.centerX(), mDrawableRect.centerY(), mDrawableRadius, mCircleBackgroundPaint);
        }
        if (mBitmap != null) {
            if (mDrawableDirty && mBitmapCanvas != null) {
                // 如果Drawable失效了, mBitmapCanvas还存在, 那么重新设置Drawable.
                mDrawableDirty = false;
                Drawable drawable = getDrawable();
                drawable.setBounds(0, 0, mBitmapCanvas.getWidth(), mBitmapCanvas.getHeight());
                drawable.draw(mBitmapCanvas);
            }
            if (mRebuildShader) {
                // 如果着色器重新更新了, 那么就将更新过的着色器矩阵重新设置.
                mRebuildShader = false;
                BitmapShader bitmapShader = new BitmapShader(mBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                bitmapShader.setLocalMatrix(mShaderMatrix);
                mBitmapPaint.setShader(bitmapShader);
            }
            // 绘制圆形Bitmap
            canvas.drawCircle(mDrawableRect.centerX(), mDrawableRect.centerY(), mDrawableRadius, mBitmapPaint);
        }

        if (mBorderWidth > 0) {
            // 绘制外圆边框
            canvas.drawCircle(mBorderRect.centerX(), mBorderRect.centerY(), mBorderRadius, mBorderPaint);
        }
    }

    @Override
    public void invalidateDrawable(@NonNull Drawable dr) {
        mDrawableDirty = true;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateDimensions();
        invalidate();
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        super.setPadding(left, top, right, bottom);
        updateDimensions();
        invalidate();
    }

    @Override
    public void setPaddingRelative(int start, int top, int end, int bottom) {
        super.setPaddingRelative(start, top, end, bottom);
        updateDimensions();
        invalidate();
    }

    public int getBorderColor() {
        return mBorderColor;
    }

    public void setBorderColor(@ColorInt int borderColor) {
        if (borderColor == mBorderColor) {
            return;
        }

        mBorderColor = borderColor;
        mBorderPaint.setColor(borderColor);
        invalidate();
    }

    public int getCircleBackgroundColor() {
        return mCircleBackgroundColor;
    }

    /**
     * 设置纯色背景, 这个当Bitmap不存在时候才会生效.
     * @param circleBackgroundColor
     */
    public void setCircleBackgroundColor(@ColorInt int circleBackgroundColor) {
        if (circleBackgroundColor == mCircleBackgroundColor) {
            return;
        }

        mCircleBackgroundColor = circleBackgroundColor;
        mCircleBackgroundPaint.setColor(circleBackgroundColor);
        invalidate();
    }

    /**
     * @deprecated Use {@link #setCircleBackgroundColor(int)} instead
     */
    @Deprecated
    public void setCircleBackgroundColorResource(@ColorRes int circleBackgroundRes) {
        setCircleBackgroundColor(getContext().getResources().getColor(circleBackgroundRes));
    }

    public int getBorderWidth() {
        return mBorderWidth;
    }

    /**
     * 设置外圆边框宽度
     * @param borderWidth
     */
    public void setBorderWidth(int borderWidth) {
        if (borderWidth == mBorderWidth) {
            return;
        }
        mBorderWidth = borderWidth;
        mBorderPaint.setStrokeWidth(borderWidth);
        updateDimensions();
        invalidate();
    }

    public boolean isBorderOverlay() {
        return mBorderOverlay;
    }

    /**
     * 是否覆盖边框
     * @param borderOverlay
     */
    public void setBorderOverlay(boolean borderOverlay) {
        if (borderOverlay == mBorderOverlay) {
            return;
        }
        mBorderOverlay = borderOverlay;
        updateDimensions();
        invalidate();
    }

    public boolean isDisableCircularTransformation() {
        return mDisableCircularTransformation;
    }

    /**
     * 设置是否为转换为圆形图片
     * @param disableCircularTransformation true 不转换,为原图. false 转换为圆形图片.
     */
    public void setDisableCircularTransformation(boolean disableCircularTransformation) {
        if (disableCircularTransformation == mDisableCircularTransformation) {
            return;
        }
        mDisableCircularTransformation = disableCircularTransformation;
        if (disableCircularTransformation) {
            mBitmap = null;
            mBitmapCanvas = null;
            mBitmapPaint.setShader(null);
        } else {
            initializeBitmap();
        }
        invalidate();
    }

    @Override
    public void setImageBitmap(Bitmap bm) {
        super.setImageBitmap(bm);
        initializeBitmap();
        invalidate();
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        initializeBitmap();
        invalidate();
    }

    @Override
    public void setImageResource(@DrawableRes int resId) {
        super.setImageResource(resId);
        initializeBitmap();
        invalidate();
    }

    @Override
    public void setImageURI(Uri uri) {
        super.setImageURI(uri);
        initializeBitmap();
        invalidate();
    }

    @Override
    public void setImageAlpha(int alpha) {
        alpha &= 0xFF;
        if (alpha == mImageAlpha) {
            return;
        }
        mImageAlpha = alpha;
        // This might be called during ImageView construction before
        // member initialization has finished on API level >= 16.
        if (mInitialized) {
            mBitmapPaint.setAlpha(alpha);
            invalidate();
        }
    }

    @Override
    public int getImageAlpha() {
        return mImageAlpha;
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        if (cf == mColorFilter) {
            return;
        }
        mColorFilter = cf;
        // This might be called during ImageView construction before
        // member initialization has finished on API level <= 19.
        if (mInitialized) {
            mBitmapPaint.setColorFilter(cf);
            invalidate();
        }
    }

    @Override
    public ColorFilter getColorFilter() {
        return mColorFilter;
    }

    /**
     * 通过Drawable资源获取Bitmap对象
     * @param drawable
     * @return
     */
    private Bitmap getBitmapFromDrawable(Drawable drawable) {
        if (drawable == null) {
            return null;
        }
        if (drawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) drawable).getBitmap();
        }
        try {
            Bitmap bitmap;

            if (drawable instanceof ColorDrawable) {
                bitmap = Bitmap.createBitmap(COLORDRAWABLE_DIMENSION, COLORDRAWABLE_DIMENSION, BITMAP_CONFIG);
            } else {
                bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), BITMAP_CONFIG);
            }

            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);
            return bitmap;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 初始化Bitmap,该方法只涉及更新Bitmap对象以及计算Bitmap缩放比例及其尺寸位置,
     * 对CircleImageView控件本身的尺寸以及画笔,外环边框宽等属性不做改变.
     */
    private void initializeBitmap() {
        // 获取Bitmap
        mBitmap = getBitmapFromDrawable(getDrawable());
        // Bitmap不为空且Bitmap可以被绘制.
        if (mBitmap != null && mBitmap.isMutable()) {
            mBitmapCanvas = new Canvas(mBitmap);
        } else {
            mBitmapCanvas = null;
        }
        if (!mInitialized) {
            return;
        }
        if (mBitmap != null) {
            // 更新着色器矩阵
            updateShaderMatrix();
        } else {
            mBitmapPaint.setShader(null);
        }
    }

    /**
     * 该方法主要作用是计算CircleImageView控件的半径,外边框宽度,外边框半径等.
     */
    private void updateDimensions() {
        // 计算得到的外环矩形设置给mBorderRect对象
        mBorderRect.set(calculateBounds());
        // 计算外环半径, 其中考虑到了边框宽度.
        mBorderRadius = Math.min((mBorderRect.height() - mBorderWidth) / 2.0f, (mBorderRect.width() - mBorderWidth) / 2.0f);
        // 将外环矩形设置给CircleImageView 显示区域矩形
        mDrawableRect.set(mBorderRect);
        // 如果不覆盖边框,且边框宽度大于0
        if (!mBorderOverlay && mBorderWidth > 0) {
            // CircleImageView 显示区域矩形将缩小,空出空间给外边框使用.
            mDrawableRect.inset(mBorderWidth - 1.0f, mBorderWidth - 1.0f);
        }
        // 获取CircleImageView控件的半径距离.
        mDrawableRadius = Math.min(mDrawableRect.height() / 2.0f, mDrawableRect.width() / 2.0f);
        updateShaderMatrix();
    }
    /**
     * 计算外环矩形
     *
     * @return
     */
    private RectF calculateBounds() {
        // 计算出该控件的实际可用宽高
        int availableWidth  = getWidth() - getPaddingLeft() - getPaddingRight();
        int availableHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        // 从宽高中取出最小值作为边长
        int sideLength = Math.min(availableWidth, availableHeight);
        // 计算外环矩形左上角坐标
        float left = getPaddingLeft() + (availableWidth - sideLength) / 2f;
        float top = getPaddingTop() + (availableHeight - sideLength) / 2f;
        // 计算外环矩形右下角坐标
        return new RectF(left, top, left + sideLength, top + sideLength);
    }
    /**
     * 将Bitmap缩小或者放大之后的中心位置移动到CircleImageView圆心位置.
     * 画图有助于理解.
     * 短边缩放至与当前控件直径一样.
     */
    private void updateShaderMatrix() {
        if (mBitmap == null) {
            return;
        }
        float scale;
        float dx = 0;
        float dy = 0;
        mShaderMatrix.set(null);
        int bitmapHeight = mBitmap.getHeight();
        int bitmapWidth = mBitmap.getWidth();
        // 假如Bitmap宽>高
        if (bitmapWidth * mDrawableRect.height() > mDrawableRect.width() * bitmapHeight) {
            // 得到Bitmap短边与直径的比例,整个Bitmap缩放按照该比例来缩放.
            // 缩放后,Bitmap长边与圆相切.
            scale = mDrawableRect.height() / (float) bitmapHeight;
            // 缩放后Bitmap长边减去圆直径除以2得到的就是缩放后Bitmap中心点与圆心点的水平距离.
            // 画图很好理解.
            dx = (mDrawableRect.width() - bitmapWidth * scale) * 0.5f;
        } else {
            scale = mDrawableRect.width() / (float) bitmapWidth;
            dy = (mDrawableRect.height() - bitmapHeight * scale) * 0.5f;
        }
        // 按照比例缩放Bitmap
        mShaderMatrix.setScale(scale, scale);
        // 水平移动或者垂直移动Bitmap,使之与圆心坐标重合.
        mShaderMatrix.postTranslate((int) (dx + 0.5f) + mDrawableRect.left, (int) (dy + 0.5f) + mDrawableRect.top);
        // 为着色器添加矩阵配置.
        mRebuildShader = true;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mDisableCircularTransformation) {
            return super.onTouchEvent(event);
        }
        return inTouchableArea(event.getX(), event.getY()) && super.onTouchEvent(event);
    }

    /**
     * 计算当前触摸点是否在CircleImageView范围内, 如果在其中则代表当前触摸事件被CircleImageView消费了.
     * @param x
     * @param y
     * @return
     */
    private boolean inTouchableArea(float x, float y) {
        if (mBorderRect.isEmpty()) {
            return true;
        }
        // Math.pow(a,2):计算a值的平方
        // x - mBorderRect.centerX(): 当前点x坐标距离圆心距离.
        // Math.pow(x - mBorderRect.centerX(), 2) + Math.pow(y - mBorderRect.centerY(), 2) :两直边的平方和小于或者等于半径平方和.勾股定理.
        // 就是算当前的点在不在CircleImageView范围内.
        return Math.pow(x - mBorderRect.centerX(), 2) + Math.pow(y - mBorderRect.centerY(), 2) <= Math.pow(mBorderRadius, 2);
    }
    
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private class OutlineProvider extends ViewOutlineProvider {
        @Override
        public void getOutline(View view, Outline outline) {
            if (mDisableCircularTransformation) {
                // 如果禁止图片转换为圆形,那么视图的轮廓就是默认轮廓.
                ViewOutlineProvider.BACKGROUND.getOutline(view, outline);
            } else {
                // 如果允许转换为圆形图片,
                Rect bounds = new Rect();
                // 取圆形控件外边框的rect
                mBorderRect.roundOut(bounds);
                // 将轮廓设置为圆形控件外边框的轮廓.
                outline.setRoundRect(bounds, bounds.width() / 2.0f);
            }
        }
    }

}
