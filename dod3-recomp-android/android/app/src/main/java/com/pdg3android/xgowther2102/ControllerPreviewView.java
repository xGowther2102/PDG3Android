package com.pdg3android.xgowther2102;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Interactive visual controller silhouette and button mapping preview for the Launcher.
 * Supports PS3, PS4, PS5, Xbox One/Series, and Switch Pro styles.
 */
public class ControllerPreviewView extends View {

    private String mStyle = "ps3";

    private final Paint mBodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBodyStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShoulderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStickRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLegendPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final Path mPath = new Path();

    public ControllerPreviewView(Context ctx) {
        super(ctx);
        mBodyPaint.setStyle(Paint.Style.FILL);
        mBodyStroke.setStyle(Paint.Style.STROKE);
        mBodyStroke.setStrokeWidth(2.5f);

        mShoulderPaint.setStyle(Paint.Style.FILL);
        mPadPaint.setStyle(Paint.Style.FILL);
        mStickRing.setStyle(Paint.Style.STROKE);

        mLabelPaint.setTextAlign(Paint.Align.CENTER);
        mLabelPaint.setFakeBoldText(true);
        mLabelPaint.setColor(Color.rgb(235, 236, 242));

        mLegendPaint.setTextAlign(Paint.Align.CENTER);
        mLegendPaint.setColor(Color.rgb(185, 186, 198));

        mGlyphPaint.setStrokeCap(Paint.Cap.ROUND);
        mGlyphPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setStyle(String style) {
        String s = (style == null) ? "ps3" : style.trim().toLowerCase();
        if (!s.equals("ps3") && !s.equals("ps4") && !s.equals("ps5")
                && !s.equals("xbox") && !s.equals("switch")) {
            s = "ps3";
        }
        mStyle = s;
        invalidate();
    }

    public String getStyle() {
        return mStyle;
    }

    public static String friendlyStyleName(String style) {
        if (style == null) return "PlayStation 3";
        switch (style.toLowerCase()) {
            case "xbox":   return "Xbox One / Series";
            case "ps4":    return "PlayStation 4 (DualShock 4)";
            case "ps5":    return "PlayStation 5 (DualSense)";
            case "switch": return "Nintendo Switch Pro";
            default:       return "PlayStation 3 (DualShock 3)";
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        int accentColor;
        String l1, l2, r1, r2, selLbl, staLbl;
        boolean asymmetric = false;
        boolean psSymbols = true;

        switch (mStyle) {
            case "xbox":
                mBodyPaint.setColor(Color.rgb(24, 28, 26));
                mBodyStroke.setColor(Color.argb(170, 92, 200, 112));
                accentColor = Color.rgb(76, 175, 80);
                l1 = "LB"; l2 = "LT"; r1 = "RB"; r2 = "RT";
                selLbl = "VIEW"; staLbl = "MENU";
                asymmetric = true;
                psSymbols = false;
                break;
            case "switch":
                mBodyPaint.setColor(Color.rgb(26, 27, 32));
                mBodyStroke.setColor(Color.argb(170, 229, 72, 82));
                accentColor = Color.rgb(229, 72, 82);
                l1 = "L"; l2 = "ZL"; r1 = "R"; r2 = "ZR";
                selLbl = "－"; staLbl = "＋";
                asymmetric = true;
                psSymbols = false;
                break;
            case "ps4":
                mBodyPaint.setColor(Color.rgb(22, 25, 36));
                mBodyStroke.setColor(Color.argb(175, 92, 152, 255));
                accentColor = Color.rgb(92, 152, 255);
                l1 = "L1"; l2 = "L2"; r1 = "R1"; r2 = "R2";
                selLbl = "SHR"; staLbl = "OPT";
                break;
            case "ps5":
                mBodyPaint.setColor(Color.rgb(36, 40, 50));
                mBodyStroke.setColor(Color.argb(190, 178, 220, 255));
                accentColor = Color.rgb(152, 212, 255);
                l1 = "L1"; l2 = "L2"; r1 = "R1"; r2 = "R2";
                selLbl = "CRE"; staLbl = "OPT";
                break;
            default: // ps3
                mBodyPaint.setColor(Color.rgb(24, 24, 30));
                mBodyStroke.setColor(Color.argb(145, 220, 220, 232));
                accentColor = Color.rgb(214, 58, 68);
                l1 = "L1"; l2 = "L2"; r1 = "R1"; r2 = "R2";
                selLbl = "SEL"; staLbl = "STA";
                break;
        }

        float cx = w * 0.5f;
        float topY = h * 0.04f;
        float bodyH = h * 0.56f;
        float bodyW = Math.min(w * 0.54f, bodyH * 1.92f);
        float bodyTop = topY + h * 0.14f;
        float left = cx - bodyW * 0.5f;
        float right = cx + bodyW * 0.5f;
        float u = bodyH / 100f;

        mLabelPaint.setTextSize(u * 10.2f);
        mLegendPaint.setTextSize(Math.max(18f, h * 0.068f));

        // 1. Triggers (L2/R2) & Bumpers (L1/R1) above silhouette
        float shW = bodyW * 0.145f;
        float shH = u * 16f;
        float bY = bodyTop - shH * 0.68f;
        drawShoulderPill(c, left + bodyW * 0.10f, bY - shH * 0.72f, shW * 0.85f, shH * 0.76f, l2, accentColor);
        drawShoulderPill(c, left + bodyW * 0.24f, bY, shW, shH * 0.84f, l1, accentColor);
        drawShoulderPill(c, right - bodyW * 0.24f, bY, shW, shH * 0.84f, r1, accentColor);
        drawShoulderPill(c, right - bodyW * 0.10f, bY - shH * 0.72f, shW * 0.85f, shH * 0.76f, r2, accentColor);

        // 2. Grips + Central Body silhouette
        float gripW = bodyW * 0.24f;
        float gripH = bodyH * 0.72f;
        drawGrip(c, left + gripW * 0.42f, bodyTop + bodyH * 0.62f, gripW, gripH, -15f);
        drawGrip(c, right - gripW * 0.42f, bodyTop + bodyH * 0.62f, gripW, gripH, 15f);

        mRect.set(left, bodyTop, right, bodyTop + bodyH * 0.84f);
        c.drawRoundRect(mRect, bodyH * 0.36f, bodyH * 0.36f, mBodyPaint);
        c.drawRoundRect(mRect, bodyH * 0.36f, bodyH * 0.36f, mBodyStroke);

        // PS4 / PS5 touchpad
        if (mStyle.equals("ps4") || mStyle.equals("ps5")) {
            mPadPaint.setColor(Color.rgb(14, 16, 24));
            float tpW = bodyW * 0.27f, tpH = bodyH * 0.30f;
            mRect.set(cx - tpW * 0.5f, bodyTop + u * 6f, cx + tpW * 0.5f, bodyTop + u * 6f + tpH);
            c.drawRoundRect(mRect, u * 5f, u * 5f, mPadPaint);
            mStickRing.setColor(accentColor);
            mStickRing.setStrokeWidth(2f);
            c.drawLine(mRect.left + u * 4f, mRect.top + u * 3f, mRect.right - u * 4f, mRect.top + u * 3f, mStickRing);
        } else if (mStyle.equals("xbox")) {
            // Xbox glowing orb
            mPadPaint.setColor(Color.argb(45, 76, 175, 80));
            c.drawCircle(cx, bodyTop + u * 18f, u * 9.5f, mPadPaint);
            mStickRing.setColor(accentColor);
            mStickRing.setStrokeWidth(2.4f);
            c.drawCircle(cx, bodyTop + u * 18f, u * 8.5f, mStickRing);
        }

        // Center Start / Select pills
        float midY = bodyTop + bodyH * 0.41f;
        drawCenterBtn(c, cx - bodyW * 0.085f, midY, u * 14f, u * 7f, selLbl);
        drawCenterBtn(c, cx + bodyW * 0.085f, midY, u * 14f, u * 7f, staLbl);

        // 3. Left controls (Symmetrical vs Asymmetric)
        float dpadCx, dpadCy, lsCx, lsCy;
        if (asymmetric) {
            lsCx = left + bodyW * 0.22f; lsCy = bodyTop + bodyH * 0.34f;
            dpadCx = cx - bodyW * 0.145f; dpadCy = bodyTop + bodyH * 0.62f;
        } else {
            dpadCx = left + bodyW * 0.21f; dpadCy = bodyTop + bodyH * 0.35f;
            lsCx = cx - bodyW * 0.145f; lsCy = bodyTop + bodyH * 0.63f;
        }
        float rsCx = cx + bodyW * 0.145f;
        float rsCy = bodyTop + bodyH * 0.63f;

        drawDpad(c, dpadCx, dpadCy, u * 13.5f);
        drawStick(c, lsCx, lsCy, u * 12.2f, accentColor, mStyle.equals("xbox") ? "LS" : "L3");
        drawStick(c, rsCx, rsCy, u * 12.2f, mStyle.equals("switch") ? Color.rgb(79, 195, 247) : accentColor,
                mStyle.equals("xbox") ? "RS" : "R3");

        // 4. Face buttons cluster (Right upper)
        float fx = right - bodyW * 0.21f;
        float fy = bodyTop + bodyH * 0.35f;
        float fd = u * 14.5f;
        float fr = u * 7.2f;

        if (psSymbols) {
            drawFaceBtn(c, fx, fy + fd, fr, Color.rgb(91, 155, 255), null, 1);  // Cross
            drawFaceBtn(c, fx + fd, fy, fr, Color.rgb(255, 90, 95), null, 2);   // Circle
            drawFaceBtn(c, fx - fd, fy, fr, Color.rgb(240, 114, 182), null, 3); // Square
            drawFaceBtn(c, fx, fy - fd, fr, Color.rgb(76, 217, 100), null, 4);  // Triangle
        } else if (mStyle.equals("xbox")) {
            drawFaceBtn(c, fx, fy + fd, fr, Color.rgb(76, 175, 80), "A", 0);
            drawFaceBtn(c, fx + fd, fy, fr, Color.rgb(229, 57, 53), "B", 0);
            drawFaceBtn(c, fx - fd, fy, fr, Color.rgb(33, 150, 243), "X", 0);
            drawFaceBtn(c, fx, fy - fd, fr, Color.rgb(251, 192, 45), "Y", 0);
        } else { // Switch Pro positional
            drawFaceBtn(c, fx, fy + fd, fr, Color.rgb(225, 225, 234), "B", 0);
            drawFaceBtn(c, fx + fd, fy, fr, Color.rgb(255, 92, 92), "A", 0);
            drawFaceBtn(c, fx - fd, fy, fr, Color.rgb(225, 225, 234), "Y", 0);
            drawFaceBtn(c, fx, fy - fd, fr, Color.rgb(79, 195, 247), "X", 0);
        }

        // 5. Bottom action legend for Drakengard 3
        String line1, line2;
        if (mStyle.equals("xbox")) {
            line1 = "X Ataque  ·  Y Ataque fuerte  ·  A Saltar  ·  B Guardia";
            line2 = "RB Esquivar  ·  LB Fijar objetivo  ·  LT / RT Armas  ·  LS+RS Intoner";
        } else if (mStyle.equals("switch")) {
            line1 = "Y Ataque  ·  X Ataque fuerte  ·  B Saltar  ·  A Guardia";
            line2 = "R Esquivar  ·  L Fijar objetivo  ·  ZL / ZR Armas  ·  L3+R3 Intoner";
        } else {
            line1 = "□ Ataque  ·  △ Ataque fuerte  ·  ✕ Saltar  ·  ○ Guardia";
            line2 = "R1 Esquivar  ·  L1 Fijar objetivo  ·  L2 / R2 Armas  ·  L3+R3 Intoner";
        }
        float legY1 = h * 0.86f;
        float legY2 = h * 0.96f;
        c.drawText(line1, cx, legY1, mLegendPaint);
        c.drawText(line2, cx, legY2, mLegendPaint);
    }

    private void drawShoulderPill(Canvas c, float cx, float cy, float pw, float ph, String label, int accent) {
        mShoulderPaint.setColor(Color.rgb(34, 36, 46));
        mRect.set(cx - pw * 0.5f, cy - ph * 0.5f, cx + pw * 0.5f, cy + ph * 0.5f);
        c.drawRoundRect(mRect, ph * 0.42f, ph * 0.42f, mShoulderPaint);
        mStickRing.setColor(Color.argb(140, Color.red(accent), Color.green(accent), Color.blue(accent)));
        mStickRing.setStrokeWidth(1.8f);
        c.drawRoundRect(mRect, ph * 0.42f, ph * 0.42f, mStickRing);
        float ts = mLabelPaint.getTextSize();
        mLabelPaint.setTextSize(ph * 0.56f);
        c.drawText(label, cx, cy + mLabelPaint.getTextSize() * 0.35f, mLabelPaint);
        mLabelPaint.setTextSize(ts);
    }

    private void drawGrip(Canvas c, float cx, float cy, float gw, float gh, float angle) {
        c.save();
        c.rotate(angle, cx, cy);
        mRect.set(cx - gw * 0.5f, cy - gh * 0.5f, cx + gw * 0.5f, cy + gh * 0.5f);
        c.drawRoundRect(mRect, gw * 0.48f, gw * 0.48f, mBodyPaint);
        c.drawRoundRect(mRect, gw * 0.48f, gw * 0.48f, mBodyStroke);
        c.restore();
    }

    private void drawCenterBtn(Canvas c, float cx, float cy, float bw, float bh, String label) {
        mPadPaint.setColor(Color.rgb(42, 44, 56));
        mRect.set(cx - bw * 0.5f, cy - bh * 0.5f, cx + bw * 0.5f, cy + bh * 0.5f);
        c.drawRoundRect(mRect, bh * 0.5f, bh * 0.5f, mPadPaint);
        float ts = mLabelPaint.getTextSize();
        mLabelPaint.setTextSize(bh * 0.72f);
        c.drawText(label, cx, cy + mLabelPaint.getTextSize() * 0.35f, mLabelPaint);
        mLabelPaint.setTextSize(ts);
    }

    private void drawDpad(Canvas c, float cx, float cy, float size) {
        float arm = size * 0.34f;
        mPadPaint.setColor(Color.rgb(16, 17, 22));
        c.drawCircle(cx, cy, size * 1.05f, mPadPaint);
        mPadPaint.setColor(Color.rgb(44, 46, 56));
        mRect.set(cx - arm, cy - size * 0.88f, cx + arm, cy + size * 0.88f);
        c.drawRoundRect(mRect, arm * 0.4f, arm * 0.4f, mPadPaint);
        mRect.set(cx - size * 0.88f, cy - arm, cx + size * 0.88f, cy + arm);
        c.drawRoundRect(mRect, arm * 0.4f, arm * 0.4f, mPadPaint);
    }

    private void drawStick(Canvas c, float cx, float cy, float r, int accent, String lbl) {
        mPadPaint.setColor(Color.rgb(14, 15, 20));
        c.drawCircle(cx, cy, r, mPadPaint);
        mStickRing.setColor(Color.argb(145, Color.red(accent), Color.green(accent), Color.blue(accent)));
        mStickRing.setStrokeWidth(2.0f);
        c.drawCircle(cx, cy, r, mStickRing);
        mPadPaint.setColor(Color.rgb(38, 40, 50));
        c.drawCircle(cx, cy, r * 0.68f, mPadPaint);
        float ts = mLabelPaint.getTextSize();
        mLabelPaint.setTextSize(r * 0.58f);
        c.drawText(lbl, cx, cy + mLabelPaint.getTextSize() * 0.35f, mLabelPaint);
        mLabelPaint.setTextSize(ts);
    }

    private void drawFaceBtn(Canvas c, float cx, float cy, float r, int color, String letter, int psKind) {
        mPadPaint.setColor(Color.rgb(16, 18, 24));
        c.drawCircle(cx, cy, r, mPadPaint);
        mStickRing.setColor(color);
        mStickRing.setStrokeWidth(2.0f);
        c.drawCircle(cx, cy, r, mStickRing);

        if (psKind == 0) {
            int old = mLabelPaint.getColor();
            float ts = mLabelPaint.getTextSize();
            mLabelPaint.setColor(color);
            mLabelPaint.setTextSize(r * 1.15f);
            c.drawText(letter, cx, cy + mLabelPaint.getTextSize() * 0.35f, mLabelPaint);
            mLabelPaint.setColor(old);
            mLabelPaint.setTextSize(ts);
        } else {
            mGlyphPaint.setStyle(Paint.Style.STROKE);
            mGlyphPaint.setStrokeWidth(Math.max(2f, r * 0.22f));
            mGlyphPaint.setColor(color);
            float s = r * 0.44f;
            if (psKind == 1) { // Cross
                c.drawLine(cx - s, cy - s, cx + s, cy + s, mGlyphPaint);
                c.drawLine(cx + s, cy - s, cx - s, cy + s, mGlyphPaint);
            } else if (psKind == 2) { // Circle
                c.drawCircle(cx, cy, s, mGlyphPaint);
            } else if (psKind == 3) { // Square
                c.drawRect(cx - s, cy - s, cx + s, cy + s, mGlyphPaint);
            } else { // Triangle
                mPath.reset();
                mPath.moveTo(cx, cy - s * 1.05f);
                mPath.lineTo(cx - s * 1.0f, cy + s * 0.85f);
                mPath.lineTo(cx + s * 1.0f, cy + s * 0.85f);
                mPath.close();
                c.drawPath(mPath, mGlyphPaint);
            }
        }
    }
}
