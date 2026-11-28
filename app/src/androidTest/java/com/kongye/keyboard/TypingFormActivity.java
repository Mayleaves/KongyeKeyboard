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
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setImeOptions(getIntent().getBooleanExtra("learning",false) ? 0 : android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        field.setGravity(android.view.Gravity.TOP); field.setTextSize(22); field.setSaveEnabled(false);
        field.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        if (getIntent().getBooleanExtra("bottom", false)) {
            // Modern edge-to-edge activities receive IME insets instead of an automatic resize.
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                root.setOnApplyWindowInsetsListener((view, insets) -> {
                    int bottom = Math.max(insets.getInsets(android.view.WindowInsets.Type.ime()).bottom,
                            insets.getInsets(android.view.WindowInsets.Type.systemBars()).bottom);
                    view.setPadding(30, 70, 30, 20 + bottom);
                    return insets;
                });
            }
            root.addView(new android.view.View(this), new android.widget.LinearLayout.LayoutParams(-1, 0, 1));
            root.addView(field, new android.widget.LinearLayout.LayoutParams(-1, 180));
        } else root.addView(field, new android.widget.LinearLayout.LayoutParams(-1, 600));
        setContentView(root);
        if (getIntent().getBooleanExtra("show_keyboard", false)) {
            field.requestFocus();
            field.postDelayed(() -> ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT), 600);
        }
    }
}
