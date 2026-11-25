/*
 * 本项目大量借鉴学习了开源投屏软件：Scrcpy，在此对该项目表示感谢
 */
package com.shiyunjin.easycontrolnext.server.helper;

import android.graphics.Rect;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.system.ErrnoException;
import android.view.Surface;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.util.Objects;

import com.shiyunjin.easycontrolnext.server.Server;
import com.shiyunjin.easycontrolnext.server.entity.Device;
import com.shiyunjin.easycontrolnext.server.entity.Options;
import com.shiyunjin.easycontrolnext.server.wrappers.DisplayManager;
import com.shiyunjin.easycontrolnext.server.wrappers.SurfaceControl;

public final class VideoEncode {
  private static MediaCodec encedec;
  private static MediaFormat encodecFormat;
  public static volatile boolean isHasChangeConfig = false;
  private static boolean useH265;

  private static IBinder display;

  private static VirtualDisplay virtualDisplay;

  // 视频头(是否H265、分辨率)由Server在每次连接握手时发送
  public static void init() throws InvocationTargetException, NoSuchMethodException, IllegalAccessException, IOException, ErrnoException {
    useH265 = Options.supportH265 && EncodecTools.isSupportH265();
    // 创建Codec
    if (!Options.encoder.isEmpty()) useH265 = false; // a named encoder is always H.264 here
    encedec = createCodec();
    detectQpControl();
    // 记录实际使用的编码器：同一设备上可能落到软件编码器，性能差异很大
    System.out.println("视频编码器: " + encedec.getName());
    System.out.flush();
    createEncodecFormat();
    startEncode();
  }

  private static MediaCodec createCodec() throws IOException {
    if (!Options.encoder.isEmpty()) return MediaCodec.createByCodecName(Options.encoder);
    return MediaCodec.createEncoderByType(useH265 ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC);
  }

  private static void createEncodecFormat() {
    String codecMime = useH265 ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
    encodecFormat = new MediaFormat();
    encodecFormat.setString(MediaFormat.KEY_MIME, codecMime);
    encodecFormat.setInteger(MediaFormat.KEY_BIT_RATE, Options.maxVideoBit);
    encodecFormat.setInteger(MediaFormat.KEY_FRAME_RATE, Options.maxFps);
    // 传输层是TCP(无丢包)，周期性关键帧只会带来码率尖峰，因此间隔可以拉长
    encodecFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, Options.iFrameInterval);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) encodecFormat.setInteger(MediaFormat.KEY_INTRA_REFRESH_PERIOD, Options.maxFps * 3);
    encodecFormat.setFloat("max-fps-to-encoder", Options.maxFps);
    // 画面静止时重复上一帧的间隔；太短会在静止画面上持续浪费带宽
    encodecFormat.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, Options.repeatAfterMs * 1000L);
    encodecFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
    if (Options.bitrateMode >= 0) encodecFormat.setInteger(MediaFormat.KEY_BITRATE_MODE, Options.bitrateMode);
    if (!Options.extraFormat.isEmpty()) {
      for (String kv : Options.extraFormat.split(",")) {
        String[] pair = kv.split(":");
        if (pair.length == 2) encodecFormat.setInteger(pair[0], Integer.parseInt(pair[1]));
      }
    }
    if (Options.lowLatency) {
      // 实时优先级
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) encodecFormat.setInteger(MediaFormat.KEY_PRIORITY, 0);
      // 不使用B帧(B帧需要重排序，会增加至少一帧延迟)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) encodecFormat.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
      // 编码器内部最多缓存1帧
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) encodecFormat.setInteger(MediaFormat.KEY_LATENCY, 1);
    }
  }

  // 初始化编码器
  private static Surface surface;

  // 当前档位下送入编码器的帧率(见applyLevel)
  private static int effectiveFps = 0;

  private static void setFormatSizeAndFps() {
    encodecFormat.setInteger(MediaFormat.KEY_WIDTH, Device.videoSize.first);
    encodecFormat.setInteger(MediaFormat.KEY_HEIGHT, Device.videoSize.second);
    if (effectiveFps > 0) {
      encodecFormat.setInteger(MediaFormat.KEY_FRAME_RATE, effectiveFps);
      encodecFormat.setFloat("max-fps-to-encoder", effectiveFps);
    }
  }

  public static void startEncode() throws InvocationTargetException, NoSuchMethodException, IllegalAccessException, IOException, ErrnoException {
    ControlPacket.sendVideoSizeEvent();
    setFormatSizeAndFps();
    try {
      encedec.configure(encodecFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    } catch (IllegalArgumentException | IllegalStateException e) {
      // 个别编码器不接受低延迟/码率模式参数：去掉这些参数后重试一次
      if (!Options.lowLatency && Options.bitrateMode < 0) throw e;
      Options.lowLatency = false;
      Options.bitrateMode = -1;
      encedec.reset();
      createEncodecFormat();
      setFormatSizeAndFps();
      encedec.configure(encodecFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }
    // 绑定Display和Surface

    surface = encedec.createInputSurface();

    // 创建显示器
    try {
      virtualDisplay = DisplayManager.createVirtualDisplay("easycontrolnext", Device.displayInfo.width, Device.displayInfo.height, Device.displayInfo.displayId, surface);
    } catch (Exception displayManagerException) {
      display = SurfaceControl.createDisplay("easycontrolnext", Build.VERSION.SDK_INT < Build.VERSION_CODES.R || (Build.VERSION.SDK_INT == Build.VERSION_CODES.R && !"S".equals(Build.VERSION.CODENAME)));
      setDisplaySurface(display, surface);
    }

    // 启动编码
    encedec.start();
    appliedBitrate = Options.maxVideoBit;
  }

  public static void stopEncode() {
    // An encoder that has entered the error state throws from stop()/reset(). That used to kill the whole
    // server; recreate the codec instead so the session survives an encoder failure
    try {
      encedec.stop();
    } catch (Exception ignored) {
    }
    try {
      encedec.reset();
    } catch (Exception e) {
      try {
        encedec.release();
      } catch (Exception ignored) {
      }
      try {
        encedec = createCodec();
      } catch (IOException io) {
        throw new IllegalStateException("cannot recreate video encoder", io);
      }
    }
    if (surface != null) surface.release();

    try {
      if (display != null) {
        SurfaceControl.destroyDisplay(display);
        display = null;
      }
      if (virtualDisplay != null) {
          virtualDisplay.release();
          virtualDisplay = null;
      }
    } catch (Exception ignored) {
    }
  }

  private static void setDisplaySurface(IBinder display, Surface surface) throws InvocationTargetException, NoSuchMethodException, IllegalAccessException {
    SurfaceControl.openTransaction();
    try {
      SurfaceControl.setDisplaySurface(display, surface);
      SurfaceControl.setDisplayProjection(display, 0, new Rect(0, 0, Device.displayInfo.width, Device.displayInfo.height), new Rect(0, 0, Device.videoSize.first, Device.videoSize.second));
      SurfaceControl.setDisplayLayerStack(display, Device.displayInfo.layerStack);
    } finally {
      SurfaceControl.closeTransaction();
    }
  }

  private static final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

  public static boolean isH265() {
    return useH265;
  }

  // 最近一次的编解码配置(SPS/PPS/VPS)，用于重连后重新发送
  private static byte[] codecConfig = null;
  // 新连接需要先收到 配置+关键帧 才能开始解码
  private static volatile boolean needResync = true;
  private static long lastSyncRequestMs = 0;
  private static volatile long resyncSinceMs = 0;
  private static volatile boolean restartedForResync = false;

  public static void prepareResync() {
    resyncSinceMs = System.currentTimeMillis();
    restartedForResync = false;
    needResync = true;
  }

  // A frame lost over UDP: the app has to restart from a keyframe. Rate-limited, since several frames
  // can be reported lost in a row and the forced encoder restart behind prepareResync is not free
  private static long lastLossResyncMs = 0;

  public static synchronized void resyncAfterLoss() {
    long now = System.currentTimeMillis();
    if (now - lastLossResyncMs < 300) return;
    lastLossResyncMs = now;
    prepareResync();
    requestSyncFrame();
  }

  public static void requestSyncFrame() {
    lastSyncRequestMs = System.currentTimeMillis();
    try {
      Bundle bundle = new Bundle();
      bundle.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
      encedec.setParameters(bundle);
    } catch (Exception ignored) {
    }
  }

  public static void encodeOut() throws IOException {
    try {
      // 画面静止时编码器不一定会重复输出帧(实测redroid的OMX编码器会停几秒)，请求的关键帧也就迟迟不来，
      // 新连接会一直黑屏。等待超过150ms就重启编码器：新的虚拟显示一定会合成一帧，从而立即产生关键帧
      if (needResync && !restartedForResync && Server.isAttached() && System.currentTimeMillis() - resyncSinceMs > 150) {
        restartedForResync = true;
        isHasChangeConfig = true;
        return;
      }
      if (applyLevel()) return;
      applyBitrate();
      // 找到已完成的输出缓冲区。使用超时而不是无限等待：画面静止时也要能回到外层循环处理重启请求
      int outIndex = encedec.dequeueOutputBuffer(bufferInfo, 100_000);
      if (outIndex < 0) return;
      ByteBuffer buffer = encedec.getOutputBuffer(outIndex);
      if (buffer == null) return;
      long pts = bufferInfo.presentationTimeUs;
      boolean isConfig = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
      if (isConfig) {
        codecConfig = new byte[buffer.remaining()];
        buffer.duplicate().get(codecConfig);
      } else detectReordering(pts);
      if (Options.stats && !isConfig) recordStats(pts, bufferInfo.size);
      // 先读代数再读needResync(与Server.attach中的写入顺序相反)
      int gen = Server.generation();
      if (Server.isAttached()) {
        // 解析开头的NAL类型(只读到第一个图像分片，开销很小)。不能只依赖MediaCodec的标志：
        // 实测redroid的OMX编码器从不设置KEY_FRAME/CODEC_CONFIG，而是把SPS/PPS与IDR放在同一个缓冲区里(带内参数集)
        int nal = (!useH265 || needResync) ? scanNal(buffer) : 0;
        if (!needResync) {
          if (isConfig) sendPacket(pts, buffer, gen, true);
          else {
            int action = FlowControl.check();
            if (action == FlowControl.SEND) sendPacket(pts, buffer, gen, (nal & NAL_SPS) != 0);
            else if (action == FlowControl.RESUME) {
              // 拥塞期间丢了帧，之后的P帧已无法解码：从新的关键帧恢复
              prepareResync();
              requestSyncFrame();
            }
          }
        } else if (!isConfig) {
          boolean isKey = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 || (nal & NAL_KEY) != 0;
          boolean hasInbandConfig = (nal & NAL_PARAMETER_SETS) != 0;
          if (isKey && hasInbandConfig) {
            if (sendPacket(pts, buffer, gen, true)) needResync = false;
          } else if (isKey && codecConfig != null) {
            if (sendPacket(pts, ByteBuffer.wrap(codecConfig), gen, true)
              && sendPacket(pts, buffer, gen, false)) needResync = false;
          } else if (System.currentTimeMillis() - lastSyncRequestMs > 300) {
            // 等待关键帧期间丢弃非关键帧；请求可能落在编码器重启等时机而丢失，定期补发
            requestSyncFrame();
          }
        }
      }
      // 未连接时直接丢弃(编码器保持运行，重连无需重建)
      encedec.releaseOutputBuffer(outIndex, false);
    } catch (IllegalStateException ignored) {
    }
  }

  // 发送一个视频包。H.264的SPS补上“无重排序”声明，解码器才会收到一帧就输出一帧(见SpsFixer)
  private static boolean sendPacket(long pts, ByteBuffer buffer, int gen, boolean mayHaveSps) {
    if (!useH265 && mayHaveSps) {
      byte[] fixed = SpsFixer.fix(buffer);
      if (fixed != null) return ControlPacket.sendVideoEvent(pts, ByteBuffer.wrap(fixed), gen);
    }
    return ControlPacket.sendVideoEvent(pts, buffer, gen);
  }

  // 降级档位：有的编码器不理会码率设置(实测redroid的OMX编码器是固定画质，设1Mbps或8Mbps输出都一样)，
  // 对它们只有降低帧率和编码分辨率才能减少数据量。档位由FlowControl根据“实际输出码率是否超过目标”决定。
  // {帧率除数, 画面缩放百分比}
  private static final int[][] LEVELS_BASIC = {{1, 100, 0}, {2, 100, 0}, {2, 75, 0}, {3, 50, 0}};
  private static final int[] PERCENT_BASIC = {100, 68, 48, 25};
  // QP-capable redroid encoder (see detectQpControl): raise the quantizer first, which keeps the frame rate and
  // resolution, and only then reduce frame rate / size. {fps divisor, scale percent, QP increase}.
  // Percentages measured on the redroid AMD encoder: QP+3 = 65%, QP+6 = 46%, QP+9 = 36% of the bytes.
  private static final int[][] LEVELS_QP = {{1, 100, 0}, {1, 100, 3}, {1, 100, 6}, {2, 100, 6}, {2, 100, 9}, {2, 75, 9}, {3, 50, 9}};
  private static final int[] PERCENT_QP = {100, 65, 46, 31, 24, 17, 9};
  private static int[][] LEVELS = LEVELS_BASIC;
  private static int appliedLevel = 0;

  // Redroid builds whose encoder library reads debug.redroid.enc.* at every encoder start let us pick the
  // quantizer at runtime (the stock redroid encoder ignores every bitrate/quality request).
  // Ownership: persist.redroid.enc.* is the user's setting (Android Settings UI); debug.redroid.enc.* is a temporary
  // override for streaming apps and wins over it. We only ever write debug.*, and only while the network needs it.
  private static final String PROP_QP = "debug.redroid.enc.qp";
  private static final String PROP_USER_QP = "persist.redroid.enc.qp";
  // Remembers the user's own value while we override it, so a server that dies cannot leave it changed
  private static final String PROP_QP_SAVED = "debug.easycontrolnext.enc.qp";
  private static final int MAX_QP = 45;
  private static boolean qpControl = false;
  private static int baseQp = 31;
  private static String userQp = "";

  private static void detectQpControl() {
    try {
      if (!"OMX.redroid.h264.encoder".equals(encedec.getName())) return;
      if (!fileContains("/vendor/lib/libOmxCore.so", PROP_QP) && !fileContains("/vendor/lib64/libOmxCore.so", PROP_QP)) return;
      // A previous server that died while overriding the value left the original here
      String saved = getProp(PROP_QP_SAVED);
      if (saved.startsWith("v")) {
        setProp(PROP_QP, saved.substring(1));
        setProp(PROP_QP_SAVED, "");
      }
      encoderProps = true;
      userQp = getProp(PROP_QP);
      propsSignature = propsSignature();
      // A fixed bitrate mode chosen by the user makes the quantizer irrelevant: leave it alone
      if (userChoseBitrateMode()) return;
      baseQp = currentBaseQp();
      qpControl = true;
      LEVELS = LEVELS_QP;
      FlowControl.setLevelPercent(PERCENT_QP);
      System.out.println("QP control available, base QP " + baseQp);
      System.out.flush();
    } catch (Exception ignored) {
    }
  }

  // Same priority as the encoder library: debug (set by hand) > persist (Settings UI) > boot property > default.
  // Read again at every change so a new value chosen in Settings during the session is respected
  private static int currentBaseQp() {
    return parseQp(userQp, parseQp(getProp(PROP_USER_QP), parseQp(getProp("ro.boot.redroid_enc_qp"), 31)));
  }

  // --- Live encoder settings -------------------------------------------------------------------------------
  // The encoder library reads these at every encoder start. A change made in Settings (EncoderSettings app) or with
  // setprop used to apply only on the next connection; now it restarts the encoder within about a second.
  private static final String[] ENCODER_PROPS = {
    "debug.redroid.enc.qp", "debug.redroid.enc.gop", "debug.redroid.enc.rc_mode", "debug.redroid.enc.bitrate",
    "persist.redroid.enc.qp", "persist.redroid.enc.gop", "persist.redroid.enc.rc_mode", "persist.redroid.enc.bitrate"};
  private static final Object propsLock = new Object();
  private static boolean encoderProps = false;
  private static String propsSignature = null;

  private static String propsSignature() {
    StringBuilder signature = new StringBuilder();
    for (String key : ENCODER_PROPS) signature.append(getProp(key)).append('|');
    return signature.toString();
  }

  private static boolean userChoseBitrateMode() {
    String rcMode = getProp("debug.redroid.enc.rc_mode");
    if (rcMode.isEmpty()) rcMode = getProp("persist.redroid.enc.rc_mode");
    return rcMode.equalsIgnoreCase("VBR") || rcMode.equalsIgnoreCase("CBR");
  }

  // Called once a second (heartbeat thread)
  public static void checkEncoderSettings() {
    if (!encoderProps) return;
    synchronized (propsLock) {
      String signature = propsSignature();
      if (signature.equals(propsSignature)) return;
      propsSignature = signature;
      // Somebody else changed the settings (our own writes refresh the signature right away)
      String qpNow = getProp(PROP_QP);
      if (writtenQp != null && !qpNow.equals(writtenQp)) {
        // The user set a QP while we had raised it: theirs wins, stop overriding
        writtenQp = null;
        setProp(PROP_QP_SAVED, "");
      }
      if (writtenQp == null) userQp = qpNow;
      boolean bitrateMode = userChoseBitrateMode();
      if (bitrateMode && writtenQp != null) restoreQpLocked();
      // Switching to VBR/CBR hands the quantizer to the encoder; back to CQP lets the network ladder use it again
      qpControl = !bitrateMode && LEVELS == LEVELS_QP;
      System.out.println("编码设置已更改，重启编码器: " + signature);
      System.out.flush();
      isHasChangeConfig = true;
    }
  }

  private static int parseQp(String value, int fallback) {
    try {
      int qp = Integer.parseInt(value.trim());
      return qp >= 18 && qp <= MAX_QP ? qp : fallback;
    } catch (Exception e) {
      return fallback;
    }
  }

  // The new value applies to the next encoder start, which the level change triggers anyway
  // The value we last wrote; null while we are not overriding
  private static String writtenQp = null;

  private static void applyQp(int delta) {
    synchronized (propsLock) {
      if (!qpControl) return;
      if (delta <= 0) restoreQpLocked();
      else {
        baseQp = currentBaseQp();
        setProp(PROP_QP_SAVED, "v" + userQp);
        writtenQp = String.valueOf(Math.min(MAX_QP, baseQp + delta));
        setProp(PROP_QP, writtenQp);
        // Our own change: the encoder restart for the new level is already on its way
        propsSignature = propsSignature();
      }
    }
  }

  private static void restoreQp() {
    synchronized (propsLock) {
      restoreQpLocked();
    }
  }

  private static void restoreQpLocked() {
    if (writtenQp == null) return;
    // Someone else changed the property after us (a person or another tool): keep their value
    if (getProp(PROP_QP).equals(writtenQp)) setProp(PROP_QP, userQp);
    setProp(PROP_QP_SAVED, "");
    writtenQp = null;
    propsSignature = propsSignature();
  }

  private static boolean fileContains(String path, String text) {
    byte[] needle = text.getBytes();
    try (java.io.InputStream in = new java.io.FileInputStream(path)) {
      byte[] buf = new byte[1 << 16];
      int matched = 0;
      int n;
      while ((n = in.read(buf)) > 0) {
        for (int i = 0; i < n; i++) {
          if (buf[i] == needle[matched]) {
            if (++matched == needle.length) return true;
          } else matched = buf[i] == needle[0] ? 1 : 0;
        }
      }
    } catch (Exception ignored) {
    }
    return false;
  }

  private static String getProp(String key) {
    try {
      Object value = Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class).invoke(null, key, "");
      return value == null ? "" : (String) value;
    } catch (Exception e) {
      return "";
    }
  }

  private static void setProp(String key, String value) {
    try {
      Class.forName("android.os.SystemProperties").getMethod("set", String.class, String.class).invoke(null, key, value);
    } catch (Exception ignored) {
    }
  }

  // 档位变化时调整帧率/分辨率并请求重启编码器。返回true表示本轮不要继续取帧
  private static boolean applyLevel() {
    int level = Math.min(FlowControl.level(), LEVELS.length - 1);
    if (level == appliedLevel) return false;
    appliedLevel = level;
    effectiveFps = Math.min(Options.maxFps, Math.max(15, Options.maxFps / LEVELS[level][0]));
    Device.setVideoScale(LEVELS[level][1]);
    applyQp(LEVELS[level][2]);
    System.out.println("视频档位 " + level + ": " + effectiveFps + " fps, " + Device.videoSize.first + "x" + Device.videoSize.second
      + (qpControl ? ", QP " + Math.min(MAX_QP, currentBaseQp() + LEVELS[level][2]) : ""));
    System.out.flush();
    isHasChangeConfig = true;
    return true;
  }

  // 当前实际生效的码率(流控在网络拥塞时会临时调低)
  private static int appliedBitrate = 0;

  private static void applyBitrate() {
    int target = FlowControl.targetBitrate();
    if (target <= 0 || target == appliedBitrate) return;
    appliedBitrate = target;
    try {
      Bundle bundle = new Bundle();
      bundle.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, target);
      encedec.setParameters(bundle);
    } catch (Exception ignored) {
    }
  }

  // 没有B帧时，编码器按采集顺序输出，pts严格递增。一旦pts倒退说明存在帧重排序，
  // 此时不能再声明“无重排序”，否则解码器会按错误顺序显示。关闭SPS修改并从关键帧重新开始
  private static long lastFramePts = Long.MIN_VALUE;

  private static void detectReordering(long pts) {
    if (pts < lastFramePts && SpsFixer.enabled && !useH265) {
      SpsFixer.enabled = false;
      System.out.println("检测到帧重排序(B帧)：不再修改SPS");
      System.out.flush();
      if (Server.isAttached()) {
        prepareResync();
        requestSyncFrame();
      }
    }
    lastFramePts = pts;
  }

  private static final int NAL_KEY = 1;
  private static final int NAL_PARAMETER_SETS = 2;
  private static final int NAL_SPS = 4;

  // 扫描Annex-B码流开头的NAL单元(遇到第一个图像分片即停止)，判断是否为关键帧、是否带参数集
  private static int scanNal(ByteBuffer buffer) {
    int result = 0;
    int start = buffer.position();
    int end = Math.min(buffer.limit(), start + 64 * 1024);
    for (int i = start; i + 3 < end; i++) {
      if (buffer.get(i) != 0 || buffer.get(i + 1) != 0 || buffer.get(i + 2) != 1) continue;
      int header = buffer.get(i + 3) & 0xFF;
      if (useH265) {
        int type = (header >> 1) & 0x3F;
        if (type >= 32 && type <= 34) result |= NAL_PARAMETER_SETS;
        else if (type <= 31) {
          // 16~21为IRAP(BLA/IDR/CRA)
          if (type >= 16 && type <= 21) result |= NAL_KEY;
          return result;
        }
      } else {
        int type = header & 0x1F;
        if (type == 7) result |= NAL_PARAMETER_SETS | NAL_SPS;
        else if (type == 8) result |= NAL_PARAMETER_SETS;
        else if (type >= 1 && type <= 5) {
          if (type == 5) result |= NAL_KEY;
          return result;
        }
      }
      i += 3;
    }
    return result;
  }

  // 统计：采集时间(pts) -> 编码输出 的延迟
  private static final long[] statsLatency = new long[4096];
  private static int statsCount = 0;
  private static long statsBytes = 0;
  private static long statsWindowStartUs = 0;

  private static void recordStats(long ptsUs, int size) {
    long nowUs = System.nanoTime() / 1000;
    if (statsWindowStartUs == 0) statsWindowStartUs = nowUs;
    if (statsCount < statsLatency.length) statsLatency[statsCount++] = nowUs - ptsUs;
    statsBytes += size;
    long windowUs = nowUs - statsWindowStartUs;
    if (windowUs >= 5_000_000 && statsCount > 0) {
      long[] sorted = java.util.Arrays.copyOf(statsLatency, statsCount);
      java.util.Arrays.sort(sorted);
      System.out.println("STATS enc_latency_ms p50=" + sorted[statsCount / 2] / 1000.0
        + " p95=" + sorted[Math.min(statsCount - 1, statsCount * 95 / 100)] / 1000.0
        + " max=" + sorted[statsCount - 1] / 1000.0
        + " fps=" + (statsCount * 1_000_000L / windowUs)
        + " kbps=" + (statsBytes * 8_000L / windowUs));
      System.out.flush();
      statsCount = 0;
      statsBytes = 0;
      statsWindowStartUs = nowUs;
    }
  }

  public static void release() {
    restoreQp();
    try {
      stopEncode();
      encedec.release();

      if (display != null) {
        SurfaceControl.destroyDisplay(display);
        display = null;
      }

      if (virtualDisplay != null) {
        virtualDisplay.release();
        virtualDisplay = null;
      }
    } catch (Exception ignored) {
    }
  }

}
