/*
 * 本项目大量借鉴学习了开源投屏软件：Scrcpy，在此对该项目表示感谢
 */
package com.shiyunjin.easycontrolnext.server.helper;

import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;

import java.io.IOException;
import java.nio.ByteBuffer;

import com.shiyunjin.easycontrolnext.server.Server;
import com.shiyunjin.easycontrolnext.server.entity.Options;

public final class AudioEncode {
  private static MediaCodec encedec;
  private static AudioRecord audioCapture;
  private static boolean useOpus;

  // 是否有音频、是否Opus由Server在每次连接握手时发送
  public static boolean init() {
    useOpus = Options.supportOpus && EncodecTools.isSupportOpus();
    try {
      // 从安卓12开始支持音频
      if (!Options.isAudio || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) throw new Exception("版本低");
      setAudioEncodec();
      encedec.start();
      audioCapture = AudioCapture.init();
    } catch (Exception ignored) {
      return false;
    }
    return true;
  }

  public static boolean isOpus() {
    return useOpus;
  }

  // 客户端把收到的第一个音频包当作解码器配置(csd-0)，因此每次连接都要先发送缓存的配置包
  private static byte[] audioConfig = null;
  private static volatile boolean needResync = true;

  public static void prepareResync() {
    needResync = true;
  }

  private static void setAudioEncodec() throws IOException {
    String codecMime = useOpus ? MediaFormat.MIMETYPE_AUDIO_OPUS : MediaFormat.MIMETYPE_AUDIO_AAC;
    encedec = MediaCodec.createEncoderByType(codecMime);
    MediaFormat encodecFormat = MediaFormat.createAudioFormat(codecMime, AudioCapture.SAMPLE_RATE, AudioCapture.CHANNELS);
    // Opus在64kbps立体声已足够清晰；AAC-LC码率过低音质下降明显，保持128kbps
    encodecFormat.setInteger(MediaFormat.KEY_BIT_RATE, Options.audioBit > 0 ? Options.audioBit : (useOpus ? 64000 : 128000));
    encodecFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AudioCapture.AUDIO_PACKET_SIZE);
    encedec.configure(encodecFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
  }

  public static void encodeIn() {
    try {
      int inIndex;
      do inIndex = encedec.dequeueInputBuffer(-1); while (inIndex < 0);
      ByteBuffer buffer = encedec.getInputBuffer(inIndex);
      if (buffer == null) return;
      int size = Math.min(buffer.remaining(), AudioCapture.AUDIO_PACKET_SIZE);
      audioCapture.read(buffer, size);
      encedec.queueInputBuffer(inIndex, 0, size, 0, 0);
    } catch (IllegalStateException ignored) {
    }
  }

  private static final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

  public static void encodeOut() throws IOException {
    try {
      // 找到已完成的输出缓冲区
      int outIndex;
      do outIndex = encedec.dequeueOutputBuffer(bufferInfo, -1); while (outIndex < 0);
      ByteBuffer buffer = encedec.getOutputBuffer(outIndex);
      if (buffer == null) return;
      boolean isConfig = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
      if (useOpus) {
        if (isConfig) {
          buffer.getLong();
          int size = (int) buffer.getLong();
          buffer.limit(buffer.position() + size);
        }
        // 当无声音时不发送
        if (buffer.remaining() < 5) {
          encedec.releaseOutputBuffer(outIndex, false);
          return;
        }
      }
      if (isConfig) {
        audioConfig = new byte[buffer.remaining()];
        buffer.duplicate().get(audioConfig);
      }
      int gen = Server.generation();
      if (Server.isAttached()) {
        if (needResync) {
          if (audioConfig != null && ControlPacket.sendAudioEvent(ByteBuffer.wrap(audioConfig), gen)) {
            needResync = false;
            if (!isConfig) ControlPacket.sendAudioEvent(buffer, gen);
          }
        } else if (!isConfig) ControlPacket.sendAudioEvent(buffer, gen);
      }
      encedec.releaseOutputBuffer(outIndex, false);
    } catch (IllegalStateException ignored) {
    }
  }

  public static void release() {
    try {
      encedec.stop();
      encedec.release();
      audioCapture.stop();
      audioCapture.release();
    } catch (Exception ignored) {
    }
  }
}

