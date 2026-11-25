package com.shiyunjin.easycontrolnext.app.entity;

import android.content.SharedPreferences;

import java.util.UUID;

public final class Setting {
  private final SharedPreferences sharedPreferences;

  private final SharedPreferences.Editor editor;

  public String getLocale() {
    return sharedPreferences.getString("locale", "");
  }

  public void setLocale(String value) {
    editor.putString("locale", value);
    editor.apply();
  }

  public boolean getAutoRotate() {
    return sharedPreferences.getBoolean("autoRotate", true);
  }

  public void setAutoRotate(boolean value) {
    editor.putBoolean("autoRotate", value);
    editor.apply();
  }

  // 画面左上角显示帧率/延迟等统计
  public boolean getShowStats() {
    return sharedPreferences.getBoolean("showStats", true);
  }

  public void setShowStats(boolean value) {
    editor.putBoolean("showStats", value);
    editor.apply();
  }

  // Video over UDP when the server port is reachable directly
  public boolean getUdpVideo() {
    return sharedPreferences.getBoolean("udpVideo", true);
  }

  public void setUdpVideo(boolean value) {
    editor.putBoolean("udpVideo", value);
    editor.apply();
  }

  // Maximum smoothness (jitter) buffer in ms; 0 = off
  public int getJitterBufferMax() {
    return sharedPreferences.getInt("jitterBufferMax", 100);
  }

  public void setJitterBufferMax(int value) {
    editor.putInt("jitterBufferMax", value);
    editor.apply();
  }

  public String getLocalUUID() {
    if (!sharedPreferences.contains("UUID")) {
      editor.putString("UUID", UUID.randomUUID().toString());
      editor.apply();
    }
    return sharedPreferences.getString("UUID", "");
  }

  public Setting(SharedPreferences sharedPreferences) {
    this.sharedPreferences = sharedPreferences;
    this.editor = sharedPreferences.edit();
  }
}
