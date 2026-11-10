package com.kongye.keyboard;

/** Standalone Java-only form in the test APK; never installed in the real app. */
public final class VaultFormActivity extends android.app.Activity {
    @Override public void dump(String prefix, java.io.FileDescriptor fd, java.io.PrintWriter out, String[] args) {
        // Only synthetic fixture assertions: never emit field contents, even in tests.
        android.widget.EditText account=findViewById(200), login=findViewById(201), generated=findViewById(202), confirm=findViewById(203);
        out.println(prefix+"VaultFixture: accountMatch="+"test@example.test".contentEquals(account.getText())
            +" loginMatch="+"Synthetic-Only!9".contentEquals(login.getText())
            +" generatedLength="+generated.length()+" confirmLength="+confirm.length()
            +" confirmationMatch="+(generated.length()>0 && generated.getText().toString().contentEquals(confirm.getText())));
    }
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        android.widget.LinearLayout root=new android.widget.LinearLayout(this);
        root.setOrientation(1);root.setPadding(40,80,40,20);
        android.widget.TextView title=new android.widget.TextView(this);title.setText("密码填充 · 隔离测试表单");title.setTextSize(22);root.addView(title);
        add(root,200,"账号",android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        add(root,201,"密码",android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        add(root,202,"设置密码",android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        add(root,203,"确认密码",android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        setContentView(root);
    }
    private void add(android.widget.LinearLayout root,int id,String hint,int type,int action) {
        android.widget.EditText field=new android.widget.EditText(this);field.setId(id);field.setHint(hint);field.setContentDescription("测试"+hint);
        field.setInputType(type);field.setImeOptions(action);field.setSaveEnabled(false);field.setTextSize(18);
        root.addView(field,new android.widget.LinearLayout.LayoutParams(-1,(int)(64*getResources().getDisplayMetrics().density)));
    }
}
