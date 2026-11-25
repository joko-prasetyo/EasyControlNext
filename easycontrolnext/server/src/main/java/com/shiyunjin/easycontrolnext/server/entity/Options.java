/*
 * 本项目大量借鉴学习了开源投屏软件：Scrcpy，在此对该项目表示感谢
 */
package com.shiyunjin.easycontrolnext.server.entity;

public final class Options {
  public static int serverPort=25166;
  public static boolean listenerClip=true;
  public static boolean isAudio = true;
  public static int maxSize = 1600;
  public static int maxVideoBit = 4000000;
  public static int maxFps = 60;
  public static boolean keepAwake = true;
  public static boolean supportH265 = true;
  public static boolean supportOpus = true;
  public static String startApp = "";
  // 调试用：每5秒向stdout输出编码延迟统计
  public static boolean stats = false;
  // 断线后等待客户端重连的秒数，超时才退出并恢复设备设置
  public static int reconnectGrace = 60;
  // 连接令牌(32位十六进制)。为空则不校验
  private static byte[] token = null;
  // 编码器调优参数(客户端不传时使用默认值)
  // 实测(redroid OMX编码器)每个周期关键帧约600KB，按10秒间隔会周期性卡顿；传输是TCP且重连时会主动请求关键帧，所以拉长
  public static int iFrameInterval = 60;
  public static int repeatAfterMs = 100;
  // -1:编码器默认 0:CQ 1:VBR 2:CBR
  public static int bitrateMode = -1;
  public static boolean lowLatency = true;
  // Force a specific video encoder by codec name (empty: system default for the mime type)
  public static String encoder = "";
  // Extra integer format keys for experiments: "key:value,key:value"
  public static String extraFormat = "";
  // Audio bitrate in bit/s (0: default 64k Opus / 128k AAC)
  public static int audioBit = 0;

  // 常量时间比较
  public static boolean checkToken(byte[] received) {
    if (token == null) return true;
    if (received == null || received.length != token.length) return false;
    int diff = 0;
    for (int i = 0; i < token.length; i++) diff |= token[i] ^ received[i];
    return diff == 0;
  }

  public static void parse(String... args) {
    for (String arg : args) {
      int equalIndex = arg.indexOf('=');
      if (equalIndex == -1) throw new IllegalArgumentException("参数格式错误");
      String key = arg.substring(0, equalIndex);
      String value = arg.substring(equalIndex + 1);
      switch (key) {
        case "serverPort":
          serverPort = Integer.parseInt(value);
          break;
        // 客户端实际发送的是listenClip，旧代码只识别listenerClip，导致剪贴板监听无法关闭
        case "listenClip":
        case "listenerClip":
          listenerClip = Integer.parseInt(value) == 1;
          break;
        case "isAudio":
          isAudio = Integer.parseInt(value) == 1;
          break;
        case "maxSize":
          maxSize = Integer.parseInt(value);
          break;
        case "maxFps":
          maxFps = Integer.parseInt(value);
          break;
        case "maxVideoBit":
          maxVideoBit = Integer.parseInt(value) * 1000000;
          break;
        case "keepAwake":
          keepAwake = Integer.parseInt(value) == 1;
          break;
        case "supportH265":
          supportH265 = Integer.parseInt(value) == 1;
          break;
        case "supportOpus":
          supportOpus = Integer.parseInt(value) == 1;
          break;
        case "startApp":
          startApp = value;
          break;
        case "stats":
          stats = Integer.parseInt(value) == 1;
          break;
        case "iFrameInterval":
          iFrameInterval = Integer.parseInt(value);
          break;
        case "repeatAfterMs":
          repeatAfterMs = Integer.parseInt(value);
          break;
        case "bitrateMode":
          bitrateMode = Integer.parseInt(value);
          break;
        case "lowLatency":
          lowLatency = Integer.parseInt(value) == 1;
          break;
        case "maxVideoBitK":
          // kbit/s, for targets below 1 Mbit/s
          maxVideoBit = Integer.parseInt(value) * 1000;
          break;
        case "encoder":
          encoder = value;
          break;
        case "fmt":
          extraFormat = value;
          break;
        case "audioBit":
          audioBit = Integer.parseInt(value);
          break;
        case "reconnectGrace":
          reconnectGrace = Integer.parseInt(value);
          break;
        case "token":
          if (value.length() == 32) {
            token = new byte[16];
            for (int i = 0; i < 16; i++) token[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
          }
          break;
      }
    }
  }
}

