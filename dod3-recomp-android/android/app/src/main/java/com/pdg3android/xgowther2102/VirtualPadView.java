package com.pdg3android.xgowther2102;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import org.libsdl.app.SDLControllerManager;

/**
 * Translucent virtual gamepad overlay for Drakengard 3 with per-controller templates
 * (PS3, PS4, PS5, Xbox One/Series, Switch Pro).
 * Registers a virtual Android joystick with SDLControllerManager so cellPad
 * sees it alongside Bluetooth/USB controllers.
 */
public class VirtualPadView extends View {

    private static final int VDEV_ID = 0x50444733; // "PDG3"
    private boolean mRegistered = false;
    private boolean mVisible = true;
    private String mTemplate = "ps3";

    private final Paint mRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRingActivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillActivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSmallTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mArrowPath = new Path();
    private final RectF mPillRect = new RectF();

    private static final int KIND_FACE_BOTTOM = 1;
    private static final int KIND_FACE_RIGHT  = 2;
    private static final int KIND_FACE_LEFT   = 3;
    private static final int KIND_FACE_TOP    = 4;
    private static final int KIND_PILL        = 5;
    private static final int KIND_DPAD_UP     = 6;
    private static final int KIND_DPAD_DOWN   = 7;
    private static final int KIND_DPAD_LEFT   = 8;
    private static final int KIND_DPAD_RIGHT  = 9;

    private static class Btn {
        String label;
        final int kind;
        final int keycode;
        final int keycode2; // optional second key (e.g. L3+R3 Intoner)
        int accentColor = Color.WHITE;
        float cx, cy, r, hitHalfW, hitHalfH;
        int pointerId = -1;

        Btn(String l, int k, int kc, int kc2) {
            label = l;
            kind = k;
            keycode = kc;
            keycode2 = kc2;
        }
    }

    private final Btn[] mBtns = new Btn[] {
        new Btn("X",    KIND_FACE_BOTTOM, KeyEvent.KEYCODE_BUTTON_A, 0),
        new Btn("O",    KIND_FACE_RIGHT,  KeyEvent.KEYCODE_BUTTON_B, 0),
        new Btn("□",    KIND_FACE_LEFT,   KeyEvent.KEYCODE_BUTTON_X, 0),
        new Btn("△",    KIND_FACE_TOP,    KeyEvent.KEYCODE_BUTTON_Y, 0),
        new Btn("L1",   KIND_PILL,        KeyEvent.KEYCODE_BUTTON_L1, 0),
        new Btn("R1",   KIND_PILL,        KeyEvent.KEYCODE_BUTTON_R1, 0),
        new Btn("L2",   KIND_PILL,        KeyEvent.KEYCODE_BUTTON_L2, 0),
        new Btn("R2",   KIND_PILL,        KeyEvent.KEYCODE_BUTTON_R2, 0),
        new Btn("SEL",  KIND_PILL,        KeyEvent.KEYCODE_BUTTON_SELECT, 0),
        new Btn("STA",  KIND_PILL,        KeyEvent.KEYCODE_BUTTON_START, 0),
        new Btn("INT",  KIND_PILL,        KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR),
        new Btn("UP",   KIND_DPAD_UP,     KeyEvent.KEYCODE_DPAD_UP, 0),
        new Btn("DN",   KIND_DPAD_DOWN,   KeyEvent.KEYCODE_DPAD_DOWN, 0),
        new Btn("LT",   KIND_DPAD_LEFT,   KeyEvent.KEYCODE_DPAD_LEFT, 0),
        new Btn("RT",   KIND_DPAD_RIGHT,  KeyEvent.KEYCODE_DPAD_RIGHT, 0),
    };

    private float mToggleCx, mToggleCy, mToggleR;
    private float mLsCx, mLsCy, mLsR, mLsDx, mLsDy;
    private int mLsPtr = -1;
    private float mRsCx, mRsCy, mRsR, mRsDx, mRsDy;
    private int mRsPtr = -1;
    private float mUnit;

    public VirtualPadView(Context ctx) {
        super(ctx);
        mRingPaint.setStyle(Paint.Style.STROKE);
        mRingPaint.setStrokeWidth(3f);

        mRingActivePaint.setStyle(Paint.Style.STROKE);
        mRingActivePaint.setStrokeWidth(4f);

        mFillPaint.setStyle(Paint.Style.FILL);
        mFillActivePaint.setStyle(Paint.Style.FILL);

        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTextPaint.setFakeBoldText(true);

        mSmallTextPaint.setColor(Color.argb(225, 240, 240, 245));
        mSmallTextPaint.setTextAlign(Paint.Align.CENTER);
        mSmallTextPaint.setFakeBoldText(true);

        mGlyphPaint.setStyle(Paint.Style.STROKE);
        mGlyphPaint.setStrokeCap(Paint.Cap.ROUND);
        mGlyphPaint.setStrokeJoin(Paint.Join.ROUND);

        applyTemplateVisuals();
    }

    public void setPadConfig(boolean visible, float opacity) {
        mVisible = visible;
        setAlpha(Math.max(0.25f, Math.min(1.0f, opacity)));
        invalidate();
    }

    public void resetTouches() {
        boolean changed = false;
        if (mLsPtr != -1 || mLsDx != 0f || mLsDy != 0f) {
            mLsPtr = -1;
            mLsDx = 0f;
            mLsDy = 0f;
            if (mRegistered) {
                SDLControllerManager.onNativeJoy(VDEV_ID, 0, 0f);
                SDLControllerManager.onNativeJoy(VDEV_ID, 1, 0f);
            }
            changed = true;
        }
        if (mRsPtr != -1 || mRsDx != 0f || mRsDy != 0f) {
            mRsPtr = -1;
            mRsDx = 0f;
            mRsDy = 0f;
            if (mRegistered) {
                SDLControllerManager.onNativeJoy(VDEV_ID, 2, 0f);
                SDLControllerManager.onNativeJoy(VDEV_ID, 3, 0f);
            }
            changed = true;
        }
        for (Btn b : mBtns) {
            if (b.pointerId != -1) {
                b.pointerId = -1;
                sendBtn(b, false);
                changed = true;
            }
        }
        if (changed) invalidate();
    }

    public void setPadTemplate(String template) {
        String norm = (template == null) ? "ps3" : template.trim().toLowerCase();
        if (!norm.equals("ps3") && !norm.equals("ps4") && !norm.equals("ps5")
                && !norm.equals("xbox") && !norm.equals("switch")) {
            norm = "ps3";
        }
        mTemplate = norm;
        applyTemplateVisuals();
        if (getWidth() > 0 && getHeight() > 0) {
            applyTemplateLayout(getWidth(), getHeight());
        }
        invalidate();
    }

    private void applyTemplateVisuals() {
        switch (mTemplate) {
            case "xbox":
                mFillPaint.setColor(Color.argb(72, 18, 22, 20));
                mFillActivePaint.setColor(Color.argb(150, 38, 138, 66));
                mRingPaint.setColor(Color.argb(150, 185, 225, 195));
                mRingActivePaint.setColor(Color.rgb(102, 220, 128));
                mBtns[0].label = "A"; mBtns[0].accentColor = Color.rgb(76, 175, 80);
                mBtns[1].label = "B"; mBtns[1].accentColor = Color.rgb(229, 57, 53);
                mBtns[2].label = "X"; mBtns[2].accentColor = Color.rgb(33, 150, 243);
                mBtns[3].label = "Y"; mBtns[3].accentColor = Color.rgb(251, 192, 45);
                mBtns[4].label = "LB"; mBtns[5].label = "RB";
                mBtns[6].label = "LT"; mBtns[7].label = "RT";
                mBtns[8].label = "VIEW"; mBtns[9].label = "MENU"; mBtns[10].label = "LS+RS";
                break;

            case "switch":
                mFillPaint.setColor(Color.argb(72, 22, 22, 28));
                mFillActivePaint.setColor(Color.argb(150, 210, 45, 55));
                mRingPaint.setColor(Color.argb(150, 225, 225, 232));
                mRingActivePaint.setColor(Color.rgb(255, 82, 82));
                // Positional layout: bottom = B, right = A, left = Y, top = X
                mBtns[0].label = "B"; mBtns[0].accentColor = Color.rgb(230, 230, 238);
                mBtns[1].label = "A"; mBtns[1].accentColor = Color.rgb(255, 92, 92);
                mBtns[2].label = "Y"; mBtns[2].accentColor = Color.rgb(230, 230, 238);
                mBtns[3].label = "X"; mBtns[3].accentColor = Color.rgb(79, 195, 247);
                mBtns[4].label = "L";  mBtns[5].label = "R";
                mBtns[6].label = "ZL"; mBtns[7].label = "ZR";
                mBtns[8].label = "－"; mBtns[9].label = "＋"; mBtns[10].label = "INT";
                break;

            case "ps4":
                mFillPaint.setColor(Color.argb(70, 16, 20, 34));
                mFillActivePaint.setColor(Color.argb(150, 54, 118, 230));
                mRingPaint.setColor(Color.argb(155, 118, 172, 255));
                mRingActivePaint.setColor(Color.rgb(110, 178, 255));
                setPsFaceColors();
                mBtns[4].label = "L1"; mBtns[5].label = "R1";
                mBtns[6].label = "L2"; mBtns[7].label = "R2";
                mBtns[8].label = "SHR"; mBtns[9].label = "OPT"; mBtns[10].label = "INT";
                break;

            case "ps5":
                mFillPaint.setColor(Color.argb(75, 26, 30, 40));
                mFillActivePaint.setColor(Color.argb(150, 88, 165, 245));
                mRingPaint.setColor(Color.argb(165, 210, 232, 255));
                mRingActivePaint.setColor(Color.rgb(160, 224, 255));
                setPsFaceColors();
                mBtns[4].label = "L1"; mBtns[5].label = "R1";
                mBtns[6].label = "L2"; mBtns[7].label = "R2";
                mBtns[8].label = "CRE"; mBtns[9].label = "OPT"; mBtns[10].label = "INT";
                break;

            default: // ps3
                mFillPaint.setColor(Color.argb(66, 20, 20, 28));
                mFillActivePaint.setColor(Color.argb(145, 210, 40, 60));
                mRingPaint.setColor(Color.argb(145, 255, 255, 255));
                mRingActivePaint.setColor(Color.rgb(245, 92, 108));
                setPsFaceColors();
                mBtns[4].label = "L1"; mBtns[5].label = "R1";
                mBtns[6].label = "L2"; mBtns[7].label = "R2";
                mBtns[8].label = "SEL"; mBtns[9].label = "STA"; mBtns[10].label = "INT";
                break;
        }
    }

    private void setPsFaceColors() {
        mBtns[0].label = "✕"; mBtns[0].accentColor = Color.rgb(91, 155, 255);  // Cross
        mBtns[1].label = "○"; mBtns[1].accentColor = Color.rgb(255, 90, 95);   // Circle
        mBtns[2].label = "□"; mBtns[2].accentColor = Color.rgb(240, 114, 182); // Square
        mBtns[3].label = "△"; mBtns[3].accentColor = Color.rgb(76, 217, 100);  // Triangle
    }

    private void ensureRegistered() {
        if (mRegistered) return;
        try {
            SDLControllerManager.nativeAddJoystick(
                    VDEV_ID, "DOD3 Virtual Pad", "Virtual Pad",
                    0x054C, 0x05C4, false, 0xFFFFFFFF, 6, 0x3F, 0, 0);
            mRegistered = true;
        } catch (Throwable ignored) {
            // Native library may still be initializing; retry on next touch.
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyTemplateLayout(w, h);
    }

    private void applyTemplateLayout(int w, int h) {
        float u = Math.min(w, h) / 100f;
        mUnit = u;
        mRingPaint.setStrokeWidth(Math.max(2f, u * 0.28f));
        mRingActivePaint.setStrokeWidth(Math.max(3f, u * 0.40f));
        mGlyphPaint.setStrokeWidth(Math.max(3f, u * 0.45f));
        mTextPaint.setTextSize(u * 4.0f);
        mSmallTextPaint.setTextSize(u * 3.0f);

        // Toggle button top-center
        mToggleCx = w * 0.50f; mToggleCy = u * 6.5f; mToggleR = u * 4.8f;

        boolean asymmetric = mTemplate.equals("xbox") || mTemplate.equals("switch");
        if (asymmetric) {
            // Xbox / Switch Pro ergonomic layout: Left Stick raised, D-Pad lower-inner
            mLsR = u * 13.0f; mLsCx = u * 17.5f; mLsCy = u * 44f;
            float dx = u * 25.0f, dy = h - u * 19.5f, dr = u * 4.6f, dd = u * 8.6f;
            setBtn(11, dx, dy - dd, dr);
            setBtn(12, dx, dy + dd, dr);
            setBtn(13, dx - dd, dy, dr);
            setBtn(14, dx + dd, dy, dr);
        } else {
            // PlayStation symmetrical layout: D-Pad upper-left, Left Stick lower-left
            mLsR = u * 13.5f; mLsCx = u * 18f; mLsCy = h - u * 22f;
            float dx = u * 17f, dy = u * 41f, dr = u * 4.8f, dd = u * 9.5f;
            setBtn(11, dx, dy - dd, dr);
            setBtn(12, dx, dy + dd, dr);
            setBtn(13, dx - dd, dy, dr);
            setBtn(14, dx + dd, dy, dr);
        }

        // Right stick (bottom-right inner, clear of face buttons and MENU)
        mRsR = u * 11.0f; mRsCx = w - u * 52.5f; mRsCy = h - u * 19f;

        // Face buttons (right, inside screen bounds with margin)
        float fx = w - u * 20.5f, fy = h - u * 26f, fr = u * 6.3f, fd = u * 12.0f;
        setBtn(0, fx, fy + fd, fr); // Bottom (Cross / A / B)
        setBtn(1, fx + fd, fy, fr); // Right  (Circle / B / A)
        setBtn(2, fx - fd, fy, fr); // Left   (Square / X / Y)
        setBtn(3, fx, fy - fd, fr); // Top    (Triangle / Y / X)

        // Shoulders (rounded pills)
        setPill(4, u * 11f, u * 16.5f, u * 8.0f, u * 4.6f);          // L1 / LB / L
        setPill(6, u * 29f, u * 12.5f, u * 7.6f, u * 4.6f);          // L2 / LT / ZL
        setPill(5, w - u * 11f, u * 16.5f, u * 8.0f, u * 4.6f);      // R1 / RB / R
        setPill(7, w - u * 29f, u * 12.5f, u * 7.6f, u * 4.6f);      // R2 / RT / ZR

        // Utility center pills (Select / Start / Intoner)
        setPill(8,  w * 0.41f, h - u * 8.2f, u * 7.0f, u * 3.9f);
        setPill(9,  w * 0.59f, h - u * 8.2f, u * 7.0f, u * 3.9f);
        setPill(10, w - u * 11f, u * 30.5f, u * 7.4f, u * 4.2f);
    }

    private void setBtn(int i, float cx, float cy, float r) {
        mBtns[i].cx = cx; mBtns[i].cy = cy; mBtns[i].r = r;
        mBtns[i].hitHalfW = r * 1.25f;
        mBtns[i].hitHalfH = r * 1.25f;
    }

    private void setPill(int i, float cx, float cy, float hw, float hh) {
        mBtns[i].cx = cx; mBtns[i].cy = cy;
        mBtns[i].r = hh;
        mBtns[i].hitHalfW = hw;
        mBtns[i].hitHalfH = hh;
    }

    @Override
    protected void onDraw(Canvas c) {
        // Always draw tiny PAD toggle
        c.drawCircle(mToggleCx, mToggleCy, mToggleR, mFillPaint);
        c.drawCircle(mToggleCx, mToggleCy, mToggleR, mRingPaint);
        c.drawText(mVisible ? "PAD" : "+PAD", mToggleCx, mToggleCy + mSmallTextPaint.getTextSize() * 0.35f, mSmallTextPaint);
        if (!mVisible) return;

        // Sticks
        drawStick(c, mLsCx, mLsCy, mLsR, mLsDx, mLsDy, mLsPtr != -1);
        drawStick(c, mRsCx, mRsCy, mRsR, mRsDx, mRsDy, mRsPtr != -1);

        boolean psSymbols = mTemplate.equals("ps3") || mTemplate.equals("ps4") || mTemplate.equals("ps5");
        float tySmall = mSmallTextPaint.getTextSize() * 0.35f;
        float tyFace = mTextPaint.getTextSize() * 0.35f;

        for (Btn b : mBtns) {
            boolean pressed = b.pointerId != -1;
            Paint fill = pressed ? mFillActivePaint : mFillPaint;
            Paint ring = pressed ? mRingActivePaint : mRingPaint;

            if (b.kind == KIND_PILL) {
                float hw = b.hitHalfW;
                float hh = b.hitHalfH;
                mPillRect.set(b.cx - hw, b.cy - hh, b.cx + hw, b.cy + hh);
                c.drawRoundRect(mPillRect, hh, hh, fill);
                c.drawRoundRect(mPillRect, hh, hh, ring);
                c.drawText(b.label, b.cx, b.cy + tySmall, mSmallTextPaint);
            } else if (b.kind >= KIND_DPAD_UP && b.kind <= KIND_DPAD_RIGHT) {
                c.drawCircle(b.cx, b.cy, b.r, fill);
                c.drawCircle(b.cx, b.cy, b.r, ring);
                drawDpadArrow(c, b);
            } else {
                // Face button
                c.drawCircle(b.cx, b.cy, b.r, fill);
                int savedStrokeColor = ring.getColor();
                ring.setColor(pressed ? Color.WHITE : Color.argb(175, Color.red(b.accentColor),
                        Color.green(b.accentColor), Color.blue(b.accentColor)));
                c.drawCircle(b.cx, b.cy, b.r, ring);
                ring.setColor(savedStrokeColor);

                if (psSymbols) {
                    drawPsGlyph(c, b);
                } else {
                    mTextPaint.setColor(b.accentColor);
                    c.drawText(b.label, b.cx, b.cy + tyFace, mTextPaint);
                }
            }
        }
    }

    private void drawPsGlyph(Canvas c, Btn b) {
        mGlyphPaint.setColor(b.accentColor);
        float s = b.r * 0.44f;
        switch (b.kind) {
            case KIND_FACE_BOTTOM: // Cross X
                c.drawLine(b.cx - s, b.cy - s, b.cx + s, b.cy + s, mGlyphPaint);
                c.drawLine(b.cx + s, b.cy - s, b.cx - s, b.cy + s, mGlyphPaint);
                break;
            case KIND_FACE_RIGHT:  // Circle O
                c.drawCircle(b.cx, b.cy, s, mGlyphPaint);
                break;
            case KIND_FACE_LEFT:   // Square
                c.drawRect(b.cx - s, b.cy - s, b.cx + s, b.cy + s, mGlyphPaint);
                break;
            case KIND_FACE_TOP:    // Triangle
                mArrowPath.reset();
                mArrowPath.moveTo(b.cx, b.cy - s * 1.05f);
                mArrowPath.lineTo(b.cx - s * 1.02f, b.cy + s * 0.88f);
                mArrowPath.lineTo(b.cx + s * 1.02f, b.cy + s * 0.88f);
                mArrowPath.close();
                c.drawPath(mArrowPath, mGlyphPaint);
                break;
        }
    }

    private void drawDpadArrow(Canvas c, Btn b) {
        mArrowPath.reset();
        float s = b.r * 0.42f;
        switch (b.kind) {
            case KIND_DPAD_UP:
                mArrowPath.moveTo(b.cx, b.cy - s);
                mArrowPath.lineTo(b.cx - s, b.cy + s * 0.65f);
                mArrowPath.lineTo(b.cx + s, b.cy + s * 0.65f);
                break;
            case KIND_DPAD_DOWN:
                mArrowPath.moveTo(b.cx, b.cy + s);
                mArrowPath.lineTo(b.cx - s, b.cy - s * 0.65f);
                mArrowPath.lineTo(b.cx + s, b.cy - s * 0.65f);
                break;
            case KIND_DPAD_LEFT:
                mArrowPath.moveTo(b.cx - s, b.cy);
                mArrowPath.lineTo(b.cx + s * 0.65f, b.cy - s);
                mArrowPath.lineTo(b.cx + s * 0.65f, b.cy + s);
                break;
            case KIND_DPAD_RIGHT:
                mArrowPath.moveTo(b.cx + s, b.cy);
                mArrowPath.lineTo(b.cx - s * 0.65f, b.cy - s);
                mArrowPath.lineTo(b.cx - s * 0.65f, b.cy + s);
                break;
        }
        mArrowPath.close();
        Paint.Style oldStyle = mGlyphPaint.getStyle();
        int oldColor = mGlyphPaint.getColor();
        mGlyphPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        mGlyphPaint.setColor(Color.argb(220, 240, 240, 245));
        c.drawPath(mArrowPath, mGlyphPaint);
        mGlyphPaint.setStyle(oldStyle);
        mGlyphPaint.setColor(oldColor);
    }

    private void drawStick(Canvas c, float cx, float cy, float r, float dx, float dy, boolean active) {
        c.drawCircle(cx, cy, r, mFillPaint);
        c.drawCircle(cx, cy, r, mRingPaint);
        c.drawCircle(cx + dx * r * 0.65f, cy + dy * r * 0.65f, r * 0.42f,
                active ? mFillActivePaint : mFillPaint);
        c.drawCircle(cx + dx * r * 0.65f, cy + dy * r * 0.65f, r * 0.42f, active ? mRingActivePaint : mRingPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        int pid = e.getPointerId(idx);

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            float x = e.getX(idx), y = e.getY(idx);
            if (dist(x, y, mToggleCx, mToggleCy) <= mToggleR * 1.4f) {
                mVisible = !mVisible;
                invalidate();
                return true;
            }
            if (!mVisible) return false;

            ensureRegistered();
            if (mLsPtr == -1 && dist(x, y, mLsCx, mLsCy) <= mLsR * 1.35f) {
                mLsPtr = pid; updateStick(true, x, y); invalidate(); return true;
            }
            if (mRsPtr == -1 && dist(x, y, mRsCx, mRsCy) <= mRsR * 1.35f) {
                mRsPtr = pid; updateStick(false, x, y); invalidate(); return true;
            }
            for (Btn b : mBtns) {
                if (b.pointerId == -1) {
                    boolean hit = (b.kind == KIND_PILL)
                            ? (Math.abs(x - b.cx) <= b.hitHalfW * 1.15f && Math.abs(y - b.cy) <= b.hitHalfH * 1.35f)
                            : (dist(x, y, b.cx, b.cy) <= b.r * 1.25f);
                    if (hit) {
                        b.pointerId = pid;
                        sendBtn(b, true);
                        invalidate();
                        return true;
                    }
                }
            }
            return false;
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (!mVisible) return false;
            boolean handled = false;
            for (int i = 0; i < e.getPointerCount(); i++) {
                int p = e.getPointerId(i);
                if (p == mLsPtr) { updateStick(true, e.getX(i), e.getY(i)); handled = true; }
                else if (p == mRsPtr) { updateStick(false, e.getX(i), e.getY(i)); handled = true; }
            }
            if (handled) invalidate();
            return handled;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
                || action == MotionEvent.ACTION_CANCEL) {
            boolean handled = false;
            if (pid == mLsPtr) {
                mLsPtr = -1; mLsDx = 0; mLsDy = 0;
                if (mRegistered) {
                    SDLControllerManager.onNativeJoy(VDEV_ID, 0, 0f);
                    SDLControllerManager.onNativeJoy(VDEV_ID, 1, 0f);
                }
                handled = true;
            }
            if (pid == mRsPtr) {
                mRsPtr = -1; mRsDx = 0; mRsDy = 0;
                if (mRegistered) {
                    SDLControllerManager.onNativeJoy(VDEV_ID, 2, 0f);
                    SDLControllerManager.onNativeJoy(VDEV_ID, 3, 0f);
                }
                handled = true;
            }
            for (Btn b : mBtns) {
                if (b.pointerId == pid) {
                    b.pointerId = -1;
                    sendBtn(b, false);
                    handled = true;
                }
            }
            if (handled) invalidate();
            return handled;
        }
        return false;
    }

    private void updateStick(boolean left, float x, float y) {
        float cx = left ? mLsCx : mRsCx;
        float cy = left ? mLsCy : mRsCy;
        float r  = left ? mLsR  : mRsR;
        float dx = (x - cx) / r;
        float dy = (y - cy) / r;
        float len = (float) Math.hypot(dx, dy);
        if (len > 1f) { dx /= len; dy /= len; }
        if (left) { mLsDx = dx; mLsDy = dy; } else { mRsDx = dx; mRsDy = dy; }
        if (mRegistered) {
            SDLControllerManager.onNativeJoy(VDEV_ID, left ? 0 : 2, dx);
            SDLControllerManager.onNativeJoy(VDEV_ID, left ? 1 : 3, dy);
        }
    }

    private void sendBtn(Btn b, boolean down) {
        if (!mRegistered) return;
        if (down) {
            SDLControllerManager.onNativePadDown(VDEV_ID, b.keycode);
            if (b.keycode2 != 0) SDLControllerManager.onNativePadDown(VDEV_ID, b.keycode2);
        } else {
            SDLControllerManager.onNativePadUp(VDEV_ID, b.keycode);
            if (b.keycode2 != 0) SDLControllerManager.onNativePadUp(VDEV_ID, b.keycode2);
        }
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x1 - x2, y1 - y2);
    }
}
