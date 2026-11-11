package org.fcitx.fcitx5.android.core;
/** JNI ABI adapter for the unmodified Fcitx5 Android 0.1.3 runtime. */
public final class Fcitx {
 public static String preedit="", committed="";
 public static void showToast(String text) { android.util.Log.i("KongyeFcitx",text); }
 public static void handleFcitxEvent(int type,Object[] params) {
  if(type==3) preedit=params[0].toString();
  if(type==1) committed+=(String)params[0];
 }
 public static native void startupFcitx(String locale,String data,String libs,String user,String cache,String[] domains);
 public static native void activateInputContext(int uid,String pkg);
 public static native void focusInputContext(boolean focus);
 public static native void setInputMethod(String ime);
 public static native void setEnabledInputMethods(String[] imes);
 public static native void resetInputContext();
 public static native void sendKeySymToFcitx(int key,int state,int code,boolean up,int time);
 public static native void sendKeyToFcitxChar(char c,int state,int code,boolean up,int time);
 public static native CandidateWord[] getFcitxCandidates(int offset,int limit);
 public static native void setFcitxAddonConfig(String addon,RawConfig config);
 public static native void setCapabilityFlags(long flags);
 public static native boolean selectCandidate(int index);
 public static native void saveFcitxState();
 public static native void exitFcitx();
 public static native void loopOnce();
 public static native void scheduleEmpty();
 public static void flush(){scheduleEmpty();loopOnce();}
}
