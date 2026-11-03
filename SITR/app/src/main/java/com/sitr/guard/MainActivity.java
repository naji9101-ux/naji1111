package com.sitr.guard;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.NumberFormat;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int BACK = Color.rgb(10, 8, 32);
    private static final int CARD = Color.rgb(28, 19, 60);
    private static final int PURPLE = Color.rgb(172, 67, 255);
    private static final int CYAN = Color.rgb(50, 230, 244);
    private static final int MUTED = Color.rgb(181, 171, 211);
    private static final int WHITE = Color.WHITE;
    private static final int REQUEST_VPN = 391;

    private SharedPreferences prefs;
    private Switch adsSwitch;
    private Switch privacySwitch;
    private Switch dailySwitch;
    private TextView status, blockedValue, filterValue, dnsWarning;
    private EditText allowField;
    private boolean syncing = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 2000);
        }
    };

    private int dp(float n) {
        return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable box(int color, float radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private GradientDrawable gradient(int start, int end, float radius) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{start, end});
        d.setCornerRadius(dp(radius));
        return d;
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return l;
    }

    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return l;
    }

    private LinearLayout.LayoutParams itemParams(int height, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height < 0 ? height : dp(height));
        p.topMargin = dp(top);
        return p;
    }

    private TextView label(String text, int size, int color, boolean strong) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(size);
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        t.setTypeface(Typeface.create(strong ? "sans-serif-medium" : "sans-serif",
                Typeface.NORMAL));
        return t;
    }

    private void padding(View v, int all) {
        v.setPadding(dp(all), dp(all), dp(all), dp(all));
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BACK);
        getWindow().setNavigationBarColor(BACK);
        getWindow().getDecorView().setSystemUiVisibility(0);
        prefs = getSharedPreferences("sitr", MODE_PRIVATE);
        BlockRules.load(this);
        createUi();
    }

    private void createUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackground(gradient(BACK, Color.rgb(18, 10, 47), 0));
        LinearLayout main = vertical();
        main.setPadding(dp(18), dp(24), dp(18), dp(30));
        scroll.addView(main);

        TextView brand = label("سِتر", 34, WHITE, true);
        brand.setGravity(Gravity.CENTER);
        brand.setLetterSpacing(0.03f);
        main.addView(brand);
        TextView roman = label("S  I  T  R", 12, MUTED, true);
        roman.setGravity(Gravity.CENTER);
        main.addView(roman);
        TextView tagline = label("حماية هادئة. تحكم كامل.", 13, MUTED, false);
        tagline.setGravity(Gravity.CENTER);
        main.addView(tagline, itemParams(-2, 10));

        ShieldGraphic shield = new ShieldGraphic();
        main.addView(shield, itemParams(200, 12));

        status = label("الحماية غير مفعّلة", 16, WHITE, true);
        status.setGravity(Gravity.CENTER);
        status.setBackground(box(Color.rgb(19, 42, 56), 40, Color.rgb(34, 122, 132)));
        status.setPadding(dp(15), dp(8), dp(15), dp(8));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        statusParams.setMargins(dp(42), 0, dp(42), dp(6));
        main.addView(status, statusParams);

        adsSwitch = modeCard(main, "حجب الإعلانات",
                "فلترة نطاقات إعلانية في التطبيقات والألعاب",
                "⊘", PURPLE, prefs.getBoolean("ads", false));
        privacySwitch = modeCard(main, "الخصوصية القصوى",
                "حجب متعقّبات وتشفير DNS عند الإمكان",
                "♢", CYAN, prefs.getBoolean("privacy", false));
        CompoundButton.OnCheckedChangeListener listener = (button, checked) -> {
            if (!syncing) applyModes();
        };
        adsSwitch.setOnCheckedChangeListener(listener);
        privacySwitch.setOnCheckedChangeListener(listener);

        Button combined = new Button(this);
        combined.setText("⚡ تشغيل الحالتين معًا");
        combined.setTextColor(WHITE);
        combined.setTextSize(19);
        combined.setAllCaps(false);
        combined.setTypeface(Typeface.DEFAULT_BOLD);
        combined.setBackground(gradient(PURPLE, Color.rgb(94, 29, 191), 22));
        combined.setElevation(dp(6));
        main.addView(combined, itemParams(64, 16));
        combined.setOnClickListener(v -> {
            syncing = true;
            adsSwitch.setChecked(true);
            privacySwitch.setChecked(true);
            syncing = false;
            applyModes();
        });

        LinearLayout statCard = vertical();
        statCard.setBackground(box(CARD, 23, Color.rgb(79, 44, 135)));
        padding(statCard, 18);
        main.addView(statCard, itemParams(-2, 20));
        TextView statTitle = label("نشاط الحماية", 19, WHITE, true);
        statCard.addView(statTitle);
        LinearLayout values = horizontal();
        statCard.addView(values, itemParams(76, 12));
        LinearLayout blockedColumn = vertical();
        blockedValue = label("٠", 29, CYAN, true);
        blockedColumn.addView(blockedValue);
        blockedColumn.addView(label("طلبات DNS محجوبة", 12, MUTED, false));
        values.addView(blockedColumn, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout listColumn = vertical();
        filterValue = label("٠", 29, PURPLE, true);
        listColumn.addView(filterValue);
        listColumn.addView(label("نطاقات في قوائم الحجب", 12, MUTED, false));
        values.addView(listColumn, new LinearLayout.LayoutParams(0, -2, 1));
        dnsWarning = label("", 12, Color.rgb(255, 198, 94), false);
        statCard.addView(dnsWarning);

        LinearLayout settingsCard = vertical();
        settingsCard.setBackground(box(CARD, 23, Color.rgb(71, 51, 107)));
        padding(settingsCard, 18);
        main.addView(settingsCard, itemParams(-2, 18));
        settingsCard.addView(label("الإعدادات", 19, WHITE, true));

        LinearLayout dailyRow = horizontal();
        dailyRow.addView(label("تذكير كل 24 ساعة", 15, WHITE, true),
                new LinearLayout.LayoutParams(0, dp(50), 1));
        dailySwitch = new Switch(this);
        dailySwitch.setChecked(prefs.getBoolean("daily", true));
        dailySwitch.setThumbTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{CYAN, MUTED}));
        dailyRow.addView(dailySwitch);
        settingsCard.addView(dailyRow, itemParams(-2, 10));
        dailySwitch.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean("daily", checked).apply();
            if (checked && ShieldVpnService.isRunning) DailyReminder.schedule(this);
            else DailyReminder.cancel(this);
        });

        settingsCard.addView(label("نطاقات مسموحة (افصل بينها بفاصلة)", 13, MUTED, false),
                itemParams(-2, 13));
        allowField = new EditText(this);
        allowField.setSingleLine(true);
        allowField.setText(prefs.getString("allow", ""));
        allowField.setTextColor(WHITE);
        allowField.setHintTextColor(MUTED);
        allowField.setHint("example.com, another.org");
        allowField.setTextSize(14);
        allowField.setBackground(box(Color.rgb(18, 13, 44), 10, Color.rgb(89, 61, 136)));
        allowField.setPadding(dp(12), dp(12), dp(12), dp(12));
        settingsCard.addView(allowField, itemParams(53, 8));
        smallButton(settingsCard, "حفظ الاستثناءات", () -> {
            prefs.edit().putString("allow", allowField.getText().toString()).apply();
            Toast.makeText(this, "تم حفظ القائمة", Toast.LENGTH_SHORT).show();
        });

        smallButton(settingsCard, "تحديث قوائم الحجب", () -> {
            Toast.makeText(this, "يجري تحميل قائمة النطاقات...", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try {
                    int count = BlockRules.update(getApplicationContext());
                    runOnUiThread(() -> {
                        refresh();
                        Toast.makeText(this, "تم تحديث " + count + " نطاق",
                                Toast.LENGTH_LONG).show();
                    });
                } catch (Exception ex) {
                    runOnUiThread(() -> Toast.makeText(this,
                            "تعذّر التحديث: تحقق من الاتصال", Toast.LENGTH_LONG).show());
                }
            }, "SITR-Filter-Update").start();
        });

        smallButton(settingsCard, "إعدادات VPN دائم التشغيل", () -> {
            try { startActivity(new Intent(Settings.ACTION_VPN_SETTINGS)); }
            catch (Exception ex) { Toast.makeText(this, "افتح VPN من إعدادات الهاتف", Toast.LENGTH_LONG).show(); }
        });

        TextView explanation = label(
                "معلومة مهمة: سِتر يحجب نطاقات إعلانية ومتتبعات عبر DNS. "
                + "لا يستطيع حذف كل إعلانات يوتيوب أو فيسبوك الرسميين، "
                + "ولا يخفي عنوان IP أو نشاط حساباتك عن الجهات التي تستخدمها. "
                + "يمكن أن تتجاوز بعض التطبيقات فلاتر DNS الخاصة بالنظام.",
                12, MUTED, false);
        explanation.setLineSpacing(dp(3), 1);
        main.addView(explanation, itemParams(-2, 18));
        setContentView(scroll);
        refresh();
    }

    private Switch modeCard(LinearLayout parent, String title, String subtitle,
                            String symbol, int accent, boolean enabled) {
        LinearLayout card = horizontal();
        card.setBackground(box(CARD, 20, accent == PURPLE
                ? Color.rgb(103, 45, 153) : Color.rgb(33, 112, 139)));
        padding(card, 15);
        parent.addView(card, itemParams(103, 13));

        TextView icon = label(symbol, 29, accent, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(box(Color.rgb(35, 22, 76), 15, accent));
        card.addView(icon, new LinearLayout.LayoutParams(dp(50), dp(54)));
        LinearLayout texts = vertical();
        LinearLayout.LayoutParams tx = new LinearLayout.LayoutParams(0, -2, 1);
        tx.setMargins(dp(10), 0, dp(10), 0);
        card.addView(texts, tx);
        texts.addView(label(title, 19, WHITE, true));
        TextView detail = label(subtitle, 12, MUTED, false);
        detail.setMaxLines(2);
        texts.addView(detail);

        Switch toggle = new Switch(this);
        toggle.setChecked(enabled);
        toggle.setShowText(false);
        toggle.setThumbTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{accent, MUTED}));
        card.addView(toggle);
        card.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked()));
        return toggle;
    }

    private void smallButton(LinearLayout parent, String title, Runnable click) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(title);
        button.setTextSize(14);
        button.setTextColor(WHITE);
        button.setBackground(box(Color.rgb(48, 27, 90), 14,
                Color.rgb(104, 57, 158)));
        parent.addView(button, itemParams(49, 11));
        button.setOnClickListener(v -> click.run());
    }

    private void applyModes() {
        boolean ads = adsSwitch.isChecked();
        boolean privacy = privacySwitch.isChecked();
        prefs.edit().putBoolean("ads", ads).putBoolean("privacy", privacy).apply();
        if (!ads && !privacy) {
            ShieldVpnService.requestStop(this);
            refresh();
            return;
        }
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) startActivityForResult(prepare, REQUEST_VPN);
        else ShieldVpnService.requestStart(this);
        refresh();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_VPN) {
            if (result == RESULT_OK) {
                ShieldVpnService.requestStart(this);
            } else {
                prefs.edit().putBoolean("ads", false).putBoolean("privacy", false).apply();
                syncing = true;
                adsSwitch.setChecked(false);
                privacySwitch.setChecked(false);
                syncing = false;
                Toast.makeText(this, "يلزم إذن VPN لتفعيل الحماية", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void refresh() {
        if (status == null) return;
        boolean active = ShieldVpnService.isRunning;
        status.setText(active ? "✓ الحماية تعمل الآن" : "○ الحماية غير متصلة");
        status.setTextColor(active ? CYAN : MUTED);
        blockedValue.setText(NumberFormat.getInstance(new Locale("ar")).format(
                prefs.getLong("blocked", 0)));
        filterValue.setText(NumberFormat.getInstance(new Locale("ar")).format(
                BlockRules.filterCount()));
        boolean downgraded = prefs.getBoolean("dns_fallback", false);
        dnsWarning.setText(downgraded && prefs.getBoolean("privacy", false)
                ? "تنبيه: تعذر DNS المشفّر مؤقتًا، استُخدم DNS عادي." : "");
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    /** Decorative shield; actual protection state is shown in the status text. */
    private class ShieldGraphic extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ShieldGraphic() { super(MainActivity.this); setLayerType(View.LAYER_TYPE_SOFTWARE, null); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(getWidth(), getHeight()) * .41f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(5));
            paint.setColor(Color.rgb(68, 34, 129));
            canvas.drawCircle(cx, cy, r, paint);
            paint.setColor(PURPLE);
            canvas.drawArc(new RectF(cx-r,cy-r,cx+r,cy+r),-90,145,false,paint);
            paint.setColor(CYAN);
            canvas.drawArc(new RectF(cx-r,cy-r,cx+r,cy+r),70,95,false,paint);
            paint.setStyle(Paint.Style.FILL);
            Path shield = new Path();
            shield.moveTo(cx, cy-r*.72f);
            shield.lineTo(cx+r*.48f, cy-r*.43f);
            shield.lineTo(cx+r*.39f, cy+r*.32f);
            shield.quadTo(cx+r*.22f,cy+r*.65f,cx,cy+r*.78f);
            shield.quadTo(cx-r*.22f,cy+r*.65f,cx-r*.39f,cy+r*.32f);
            shield.lineTo(cx-r*.48f,cy-r*.43f);
            shield.close();
            paint.setColor(Color.rgb(54, 28, 114));
            canvas.drawPath(shield, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3));
            paint.setColor(PURPLE);
            canvas.drawPath(shield, paint);
            Path check = new Path();
            check.moveTo(cx-r*.23f,cy);
            check.lineTo(cx-r*.04f,cy+r*.19f);
            check.lineTo(cx+r*.27f,cy-r*.19f);
            paint.setStrokeWidth(dp(7));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(CYAN);
            canvas.drawPath(check, paint);
        }
    }
}
