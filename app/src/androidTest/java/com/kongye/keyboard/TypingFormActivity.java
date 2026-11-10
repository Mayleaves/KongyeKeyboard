package com.kongye.keyboard;

/** Ordinary multiline field for synthetic keyboard-only QA; no saved state. */
public final class TypingFormActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(1); root.setPadding(30, 70, 30, 20);
        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("空叶键盘 · 独立输入测试"); title.setTextSize(22); root.addView(title);
        android.widget.EditText field = new android.widget.EditText(this);
        field.setId(300); field.setHint("只使用虚构测试内容"); field.setContentDescription("独立输入测试框");
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setGravity(android.view.Gravity.TOP); field.setTextSize(22); field.setSaveEnabled(false);
        field.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        root.addView(field, new android.widget.LinearLayout.LayoutParams(-1, 600)); setContentView(root);
    }
}
