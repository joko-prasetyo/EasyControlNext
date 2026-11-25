package com.shiyunjin.easycontrolnext.app.client.decode;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.util.Pair;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;

import com.shiyunjin.easycontrolnext.app.client.tools.StreamStats;

public class VideoDecode {
  private MediaCodec decodec;
  private StreamStats stats;
  private final MediaCodec.Callback callback = new MediaCodec.Callback() {
    @Override
    public void onInputBufferAvailable(@NonNull MediaCodec mediaCodec, int inIndex) {
      intputBufferQueue.offer(inIndex);
    }

    @Override
    public void onOutputBufferAvailable(@NonNull MediaCodec mediaCodec, int outIndex, @NonNull MediaCodec.BufferInfo bufferInfo) {
      try {
        // 旧代码把服务端的pts(微秒、另一台设备的时钟)当作本机纳秒时间戳传入；若恰好落在“未来1秒内”，
        // 合成器会把该帧扣留到那个时间点。使用本机当前时间：尽快显示，且同一vsync内的旧帧会被丢弃
        if (stats != null) stats.onFrameRendered(bufferInfo.presentationTimeUs);
        mediaCodec.releaseOutputBuffer(outIndex, System.nanoTime());
      } catch (IllegalStateException ignored) {
      }
    }

    @Override
    public void onError(@NonNull MediaCodec mediaCodec, @NonNull MediaCodec.CodecException e) {
    }

    @Override
    public void onOutputFormatChanged(@NonNull MediaCodec mediaCodec, @NonNull MediaFormat format) {
    }
  };

  public VideoDecode(Pair<Integer, Integer> videoSize, Surface surface, ByteBuffer csd0, ByteBuffer csd1, Handler playHandler, StreamStats stats) throws IOException, InterruptedException {
    this.stats = stats;
    try {
      setVideoDecodec(videoSize, surface, csd0, csd1, playHandler);
    } catch (IOException | InterruptedException | RuntimeException e) {
      // 构造中途失败(包括重连时被中断)必须释放：否则解码器一直占用这个Surface，
      // 之后在同一SurfaceTexture上创建的解码器全部configure失败
      release();
      throw e;
    }
  }

  public void release() {
    if (decodec == null) return;
    try {
      decodec.stop();
    } catch (Exception ignored) {
    }
    try {
      decodec.release();
    } catch (Exception ignored) {
    }
  }

  private final LinkedBlockingQueue<Integer> intputBufferQueue = new LinkedBlockingQueue<>();

  public void decodeIn(ByteBuffer data) throws InterruptedException {
    try {
      long pts = data.getLong();
      int inIndex = intputBufferQueue.take();
      decodec.getInputBuffer(inIndex).put(data);
      decodec.queueInputBuffer(inIndex, 0, data.capacity() - 8, pts, 0);
    } catch (IllegalStateException ignored) {
    }
  }

  // 创建Codec
  private void setVideoDecodec(Pair<Integer, Integer> videoSize, Surface surface, ByteBuffer csd0, ByteBuffer csd1, Handler playHandler) throws IOException, InterruptedException {
    boolean useH265 = csd1 == null;
    // 创建解码器
    String codecMime = useH265 ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
    try {
      String codecName = DecodecTools.getVideoDecoder(useH265);
      if (Objects.equals(codecName, "")) decodec = MediaCodec.createDecoderByType(codecMime);
      else decodec = MediaCodec.createByCodecName(codecName);
    } catch (Exception ignord) {
      decodec = MediaCodec.createDecoderByType(codecMime);
    }
    // 配置解码器：先尝试低延迟参数，个别解码器不接受时退回普通配置
    try {
      setCallback(playHandler);
      decodec.configure(createFormat(codecMime, videoSize, csd0, csd1, useH265, true), surface, null, 0);
    } catch (IllegalArgumentException | IllegalStateException e) {
      decodec.reset();
      setCallback(playHandler);
      decodec.configure(createFormat(codecMime, videoSize, csd0, csd1, useH265, false), surface, null, 0);
    }
    // 启动解码器
    decodec.start();
    // 解析首帧，解决开始黑屏问题
    csd0.position(0);
    decodeIn(csd0);
    if (!useH265) {
      csd1.position(0);
      decodeIn(csd1);
    }
  }

  private void setCallback(Handler playHandler) {
    // 异步解码
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && playHandler != null) {
      decodec.setCallback(callback, playHandler);
    } else decodec.setCallback(callback);
  }

  private MediaFormat createFormat(String codecMime, Pair<Integer, Integer> videoSize, ByteBuffer csd0, ByteBuffer csd1, boolean useH265, boolean lowLatency) {
    MediaFormat decodecFormat = MediaFormat.createVideoFormat(codecMime, videoSize.first, videoSize.second);
    // 关键帧可能远大于解码器按分辨率估算的默认输入缓冲(实测有约600KB的关键帧)，放不下会抛BufferOverflowException
    decodecFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(1024 * 1024, videoSize.first * videoSize.second));
    // 获取视频标识头
    csd0.position(8);
    decodecFormat.setByteBuffer("csd-0", csd0);
    if (!useH265) {
      csd1.position(8);
      decodecFormat.setByteBuffer("csd-1", csd1);
    }
    if (lowLatency) applyLowLatency(decodecFormat, codecMime);
    return decodecFormat;
  }

  // 解码器低延迟设置。没有这些参数时，不少硬件解码器(尤其高通)会先缓存若干帧再输出，
  // 在流式场景下直接表现为几十毫秒以上的额外延迟。厂商键名参考 Moonlight (MediaCodecHelper)。
  private void applyLowLatency(MediaFormat format, String codecMime) {
    String name = decodec.getName().toLowerCase();
    // Android 11+ 官方低延迟模式
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      try {
        if (decodec.getCodecInfo().getCapabilitiesForType(codecMime).isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency))
          format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
      } catch (Exception ignored) {
      }
    }
    // 厂商扩展参数(Android 8.0+支持vendor.*键)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      if (name.startsWith("omx.qcom.") || name.startsWith("c2.qti.")) {
        format.setInteger("vendor.qti-ext-dec-picture-order.enable", 1);
        format.setInteger("vendor.qti-ext-dec-low-latency.enable", 1);
      } else if (name.startsWith("omx.exynos.") || name.startsWith("c2.exynos.")) {
        format.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1);
      } else if (name.startsWith("omx.hisi.") || name.startsWith("c2.hisi.")) {
        format.setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req", 1);
        format.setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy", -1);
      } else if (name.startsWith("omx.amlogic.") || name.startsWith("c2.amlogic.")) {
        format.setInteger("vendor.low-latency.enable", 1);
      }
    }
    // 高通解码器以最高频率运行可进一步降低单帧解码耗时。
    // 不同时设置KEY_PRIORITY=0：两者叠加在部分机型上会导致解码器崩溃(Moonlight的经验)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && (name.startsWith("omx.qcom.") || name.startsWith("c2.qti.")))
      format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE);
  }

}
