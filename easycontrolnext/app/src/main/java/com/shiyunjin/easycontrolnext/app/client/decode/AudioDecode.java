package com.shiyunjin.easycontrolnext.app.client.decode;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Build;
import android.os.Handler;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;

public class AudioDecode {
  private MediaCodec decodec;
  private AudioTrack audioTrack;
  private LoudnessEnhancer loudnessEnhancer;
  private static final int SAMPLE_RATE = 48000;
  private static final int CHANNELS = 2;
  private static final int BYTES_PER_SAMPLE = 2;
  private static final int BYTES_PER_FRAME = CHANNELS * BYTES_PER_SAMPLE;
  private static final int AUDIO_PACKET_SIZE = SAMPLE_RATE * BYTES_PER_FRAME * 40 / 1000;
  // 播放缓冲中允许积压的最大音频量。网络抖动或两端时钟漂移会让积压只增不减，
  // 超过该值就丢弃本包，使音频延迟始终有上限
  private static final int MAX_BUFFERED_FRAMES = SAMPLE_RATE * 100 / 1000;
  // 已写入AudioTrack的帧数(按32位回绕，与getPlaybackHeadPosition一致)
  private int framesWritten = 0;
  // 普通(非FAST)流式AudioTrack要等缓冲区填满到启动阈值(默认为整个缓冲区)才开始播放，
  // 因此必须在开始播放之后才能按积压量丢包，否则永远达不到阈值，一直静音
  private boolean playbackStarted = false;
  private final MediaCodec.Callback callback = new MediaCodec.Callback() {
    @Override
    public void onInputBufferAvailable(@NonNull MediaCodec mediaCodec, int inIndex) {
      intputBufferQueue.offer(inIndex);
    }

    @Override
    public void onOutputBufferAvailable(@NonNull MediaCodec mediaCodec, int outIndex, @NonNull MediaCodec.BufferInfo bufferInfo) {
      try {
        ByteBuffer buffer = decodec.getOutputBuffer(outIndex);
        if (buffer == null) return;
        int head = audioTrack.getPlaybackHeadPosition();
        if (head != 0) playbackStarted = true;
        if (!playbackStarted || framesWritten - head <= MAX_BUFFERED_FRAMES) {
          int written = audioTrack.write(buffer, bufferInfo.size, AudioTrack.WRITE_NON_BLOCKING);
          if (written > 0) framesWritten += written / BYTES_PER_FRAME;
        }
        decodec.releaseOutputBuffer(outIndex, false);
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

  public AudioDecode(boolean useOpus, ByteBuffer csd0, Handler playHandler) throws IOException {
    try {
      // 创建Codec
      setAudioDecodec(useOpus, csd0, playHandler);
      // 创建AudioTrack
      setAudioTrack();
      // 创建音频放大器
      setLoudnessEnhancer();
    } catch (IOException | RuntimeException e) {
      // 构造失败时释放已创建的解码器/AudioTrack，否则每次失败都会泄漏一个解码器实例
      release();
      throw e;
    }
  }

  public void release() {
    try {
      if (decodec != null) decodec.stop();
    } catch (Exception ignored) {
    }
    try {
      if (decodec != null) decodec.release();
    } catch (Exception ignored) {
    }
    try {
      if (audioTrack != null) {
        audioTrack.stop();
        audioTrack.release();
      }
    } catch (Exception ignored) {
    }
    try {
      if (loudnessEnhancer != null) loudnessEnhancer.release();
    } catch (Exception ignored) {
    }
  }

  private final LinkedBlockingQueue<Integer> intputBufferQueue = new LinkedBlockingQueue<>();

  public void decodeIn(ByteBuffer data) throws InterruptedException {
    try {
      int inIndex = intputBufferQueue.take();
      decodec.getInputBuffer(inIndex).put(data);
      decodec.queueInputBuffer(inIndex, 0, data.capacity(), 0, 0);
    } catch (IllegalStateException ignored) {
    }
  }

  // 创建Codec
  private void setAudioDecodec(boolean useOpus, ByteBuffer csd0, Handler playHandler) throws IOException {
    // 创建解码器
    String codecMime = useOpus ? MediaFormat.MIMETYPE_AUDIO_OPUS : MediaFormat.MIMETYPE_AUDIO_AAC;
    decodec = MediaCodec.createDecoderByType(codecMime);
    // 音频参数
    int bitRate = 128000;
    MediaFormat decodecFormat = MediaFormat.createAudioFormat(codecMime, SAMPLE_RATE, CHANNELS);
    decodecFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
    decodecFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_PACKET_SIZE);
    // 获取音频标识头
    decodecFormat.setByteBuffer("csd-0", csd0);
    if (useOpus) {
      ByteBuffer csd12ByteBuffer = ByteBuffer.wrap(new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
      decodecFormat.setByteBuffer("csd-1", csd12ByteBuffer);
      decodecFormat.setByteBuffer("csd-2", csd12ByteBuffer);
    }
    // 异步解码
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && playHandler != null) {
      decodec.setCallback(callback, playHandler);
    } else decodec.setCallback(callback);
    // 配置解码器
    decodec.configure(decodecFormat, null, null, 0);
    // 启动解码器
    decodec.start();
  }

  // 创建AudioTrack
  private void setAudioTrack() {
    // 缓冲区为积压上限的2倍：起播前先填满(约200ms)，之后通过丢包把积压收敛到MAX_BUFFERED_FRAMES以内
    int bufferSize = Math.max(AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT) * 2, (MAX_BUFFERED_FRAMES * BYTES_PER_FRAME) * 2);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      AudioTrack.Builder audioTrackBuild = new AudioTrack.Builder();
      // 1
      AudioAttributes.Builder audioAttributesBulider = new AudioAttributes.Builder();
      audioAttributesBulider.setUsage(AudioAttributes.USAGE_MEDIA);
      audioAttributesBulider.setContentType(AudioAttributes.CONTENT_TYPE_MUSIC);
      // 2
      AudioFormat.Builder audioFormat = new AudioFormat.Builder();
      audioFormat.setEncoding(AudioFormat.ENCODING_PCM_16BIT);
      audioFormat.setSampleRate(SAMPLE_RATE);
      audioFormat.setChannelMask(AudioFormat.CHANNEL_OUT_STEREO);
      // 3
      audioTrackBuild.setAudioAttributes(audioAttributesBulider.build())
        .setAudioFormat(audioFormat.build())
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(bufferSize);
      // 不使用PERFORMANCE_MODE_LOW_LATENCY：FAST轨道上不允许挂载非硬件音效，下面的LoudnessEnhancer会创建失败。
      // 延迟的主要来源是积压无上限，已由MAX_BUFFERED_FRAMES解决
      // 4
      audioTrack = audioTrackBuild.build();
    } else audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT, bufferSize, AudioTrack.MODE_STREAM);
    audioTrack.play();
  }

  // 创建音频放大器
  private void setLoudnessEnhancer() {
    // 放大器是可选的：个别设备/音频策略下创建会失败，不能因此让整个音频(甚至连接)失效
    try {
      loudnessEnhancer = new LoudnessEnhancer(audioTrack.getAudioSessionId());
      loudnessEnhancer.setTargetGain(3000);
      loudnessEnhancer.setEnabled(true);
    } catch (RuntimeException ignored) {
      loudnessEnhancer = null;
    }
  }
}
