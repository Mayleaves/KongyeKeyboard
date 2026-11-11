// JNI ABI adapter for Fcitx5 Android 0.1.3 (LGPL-2.1-or-later upstream).
package org.fcitx.fcitx5.android.core;
public final class CandidateWord {
 public final String label,text,comment;
 public final boolean spaceBetweenComment;
 public CandidateWord(String label,String text,String comment,boolean spaceBetweenComment) {
  this.label=label;this.text=text;this.comment=comment;this.spaceBetweenComment=spaceBetweenComment;
 }
 public String toString(){return text;}
}
