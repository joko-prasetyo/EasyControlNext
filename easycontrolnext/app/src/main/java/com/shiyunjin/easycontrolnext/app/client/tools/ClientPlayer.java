package com.shiyunjin.easycontrolnext.app.client.tools;

import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Pair;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.shiyunjin.easycontrolnext.app.client.Client;
import com.shiyunjin.easycontrolnext.app.client.decode.AudioDecode;
import com.shiyunjin.easycontrolnext.app.client.decode.VideoDecode;
import com.shiyunjin.easycontrolnext.app.entity.AppData;
import com.shiyunjin.easycontrolnext.app.helper.PublicTools;

// 一个ClientPlayer对应一次连接：断线重连时旧的整体关闭，再为新连接创建新的
public class ClientPlayer {
  private volatile boolean isClose = false;
  // 已因本机解码器故障上报结束会话：之后的读写异常不再触发重连
  private volatile boolean failed = false;
  private final String uuid;
  private final ClientController clientController;
  private final ClientStream clientStream;
  private final Thread mainStreamInThread = new Thread(this::mainStreamIn);
  private final Thread videoStreamInThread = new Thread(this::videoStreamIn);
  private final Thread watchdogThread = new Thread(this::watchdog);
  private Handler playHandler = null;
  // 注意：HandlerThread的第二个参数是Linux nice值，Thread.MAX_PRIORITY(=10)实际是后台低优先级
  private final HandlerThread playHandlerThread = new HandlerThread("easycontrol_play", Process.THREAD_PRIORITY_URGENT_DISPLAY);
  private static final int AUDIO_EVENT = 1;
  private static final int CLIPBOARD_EVENT = 2;
  private static final int CHANGE_SIZE_EVENT = 3;
  private static final int HEARTBEAT_EVENT = 4;
  private static final int PONG_EVENT = 5;
  // 服务端每秒发送一次心跳；超过该时间收不到任何数据即视为连接已死。
  // 不依赖TCP自身超时：Wi-Fi静默断开时TCP可能要十几分钟才报错，期间画面会一直冻结
  private static final int STREAM_TIMEOUT = 1000 * 5;
  private volatile long lastReceiveTime = System.currentTimeMillis();
  private volatile long lastMainTime = System.currentTimeMillis();
  private volatile long lastVideoTime = System.currentTimeMillis();
  // 视频确认(流控)：本连接已送入解码器的累计帧数
  private final AtomicInteger videoFramesDone = new AtomicInteger(0);
  private final AtomicBoolean videoAckPending = new AtomicBoolean(false);

  public ClientPlayer(String uuid, ClientStream clientStream) {
    this.uuid = uuid;
    clientController = Client.getClientController(uuid);
    this.clientStream = clientStream;
    if (clientController == null) return;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      playHandlerThread.start();
      playHandler = new Handler(playHandlerThread.getLooper());
    }
    mainStreamInThread.start();
    videoStreamInThread.start();
    watchdogThread.start();
  }

  private void mainStreamIn() {
    AudioDecode audioDecode = null;
    boolean audioFailed = false;
    boolean useOpus = true;
    try {
      if (clientStream.readByteFromMain() == 1) useOpus = clientStream.readByteFromMain() == 1;
      // 循环处理报文
      while (!Thread.interrupted()) {
        byte type = clientStream.readByteFromMain();
        lastReceiveTime = System.currentTimeMillis();
        lastMainTime = lastReceiveTime;
        switch (type) {
          case AUDIO_EVENT:
            ByteBuffer audioFrame = clientStream.readFrameFromMain();
            if (audioFailed) break;
            try {
              if (audioDecode != null) audioDecode.decodeIn(audioFrame);
              else audioDecode = new AudioDecode(useOpus, audioFrame, playHandler);
            } catch (IOException | RuntimeException e) {
              // 音频是可选的：本机音频解码/播放失败只停用音频，不能当作断线去反复重连
              audioFailed = true;
              if (audioDecode != null) audioDecode.release();
              audioDecode = null;
              PublicTools.logToast("player", "audio disabled: " + e, false);
            }
            break;
          case CLIPBOARD_EVENT:
            clientController.handleAction("setClipBoard", clientStream.readByteArrayFromMain(clientStream.readIntFromMain()), 0);
            break;
          case CHANGE_SIZE_EVENT:
            clientController.handleAction("updateVideoSize", clientStream.readByteArrayFromMain(8), 0);
            break;
          case HEARTBEAT_EVENT:
            break;
          case PONG_EVENT:
            ByteBuffer pong = clientStream.readByteArrayFromMain(16);
            clientController.stats.onPong(pong.getLong(), pong.getLong());
            break;
          default:
            // 无法得知未知类型的长度，继续读只会错位
            throw new IOException("unknown main packet type: " + type);
        }
      }
    } catch (InterruptedException ignored) {
    } catch (Exception e) {
      connectionLost("main: " + e);
    } finally {
      if (audioDecode != null) audioDecode.release();
    }
  }

  private void videoStreamIn() {
    VideoDecode videoDecode = null;
    Surface surface = null;
    try {
      boolean useH265 = clientStream.readByteFromVideo() == 1;
      Pair<Integer, Integer> videoSize = new Pair<>(clientStream.readIntFromVideo(), clientStream.readIntFromVideo());
      // 使用控制器保存的SurfaceTexture：重连时视图可能正处于分离状态(例如迷你窗口模式)
      SurfaceTexture surfaceTexture = clientController.getSurfaceTexture();
      if (surfaceTexture == null) surfaceTexture = clientController.getTextureView().getSurfaceTexture();
      ByteBuffer csd0 = clientStream.readFrameFromVideo();
      ByteBuffer csd1 = useH265 ? null : clientStream.readFrameFromVideo();
      lastReceiveTime = System.currentTimeMillis();
      // 解码器错误是本机问题，重连解决不了：直接结束会话(不触发自动重连，避免死循环)
      try {
        surface = new Surface(surfaceTexture);
        videoDecode = new VideoDecode(videoSize, surface, csd0, csd1, playHandler, clientController.stats);
      } catch (IOException | RuntimeException e) {
        decoderFailed("video decoder: " + e);
        return;
      }
      if (csd0 == ClientStream.UDP_SWITCH || csd1 == ClientStream.UDP_SWITCH) throw new IOException("udp switch before codec config");
      ackVideoFrames(useH265 ? 1 : 2);
      clientStream.onVideoStarted();
      // Smoothness buffer (Settings): evens out Wi-Fi jitter before the decoder. 0 = frames go straight in
      int maxBufferMs = AppData.setting.getJitterBufferMax();
      final VideoDecode decoder = videoDecode;
      if (maxBufferMs > 0) jitterBuffer = new JitterBuffer(maxBufferMs, decoder::decodeIn);
      UdpVideoReceiver udp = null;
      while (!Thread.interrupted()) {
        ByteBuffer frame;
        if (udp != null) {
          UdpVideoReceiver.Frame received = udp.take();
          lastReceiveTime = System.currentTimeMillis();
          lastVideoTime = lastReceiveTime;
          // Frame numbers are the server's; a skipped (lost) frame still counts as done for flow control
          ackVideoTo(received.seq + 1);
          if (received.data == null) continue;
          frame = received.data;
        } else {
          frame = clientStream.readFrameFromVideo();
          if (frame == ClientStream.UDP_SWITCH) {
            udp = clientStream.getUdpReceiver();
            if (udp == null) throw new IOException("udp switch without receiver");
            udp.start(clientStream.getUdpFirstFrame(), useH265, () -> clientController.runOnControlThread(() -> {
              try {
                clientStream.writeToMain(ControlPacket.createKeyframeRequest());
              } catch (Exception ignored) {
              }
            }, 0));
            clientController.stats.setTransport("udp");
            continue;
          }
          lastReceiveTime = System.currentTimeMillis();
          lastVideoTime = lastReceiveTime;
          // Acknowledge on arrival: the server measures the network, not our deliberate buffering.
          // A decoder that cannot keep up still shows: the buffer's queue fills, push() blocks and the acks stop
          ackVideoFrames(1);
        }
        clientController.stats.onVideoFrame(frame.remaining());
        try {
          if (jitterBuffer != null) {
            jitterBuffer.push(frame);
            clientController.stats.setBufferMs(jitterBuffer.delayMs());
          } else videoDecode.decodeIn(frame);
        } catch (RuntimeException e) {
          decoderFailed("video decode: " + e);
          return;
        }
      }
    } catch (InterruptedException ignored) {
    } catch (Exception e) {
      // 以前这里的异常被直接忽略：视频线程退出后画面永久冻结，而会话看起来仍然“在线”
      connectionLost("video: " + e);
    } finally {
      // The buffer thread feeds the decoder: stop it before releasing the decoder
      if (jitterBuffer != null) jitterBuffer.stop();
      if (videoDecode != null) videoDecode.release();
      if (surface != null) surface.release();
    }
  }

  private volatile JitterBuffer jitterBuffer = null;

  // 回报已处理的帧数。在解码器接收之后才确认：解码跟不上时服务端同样能发现积压。
  // 最多每VIDEO_ACK_INTERVAL毫秒发一次(只发最新的累计值)：服务端判断拥塞的阈值是400ms，不需要逐帧确认，
  // 而每个确认都是一次无线发射，逐帧发送既占上行带宽又费电
  private static final long VIDEO_ACK_INTERVAL = 50;
  private volatile long lastVideoAckTime = 0;

  private void ackVideoFrames(int frames) {
    videoFramesDone.addAndGet(frames);
    scheduleVideoAck();
  }

  // Absolute count (UDP: the server's frame numbers)
  private void ackVideoTo(int count) {
    int current;
    do {
      current = videoFramesDone.get();
      if (count <= current) return;
    } while (!videoFramesDone.compareAndSet(current, count));
    scheduleVideoAck();
  }

  private void scheduleVideoAck() {
    if (!videoAckPending.compareAndSet(false, true)) return;
    clientController.runOnControlThread(() -> {
      videoAckPending.set(false);
      if (isClose) return;
      lastVideoAckTime = System.currentTimeMillis();
      try {
        clientStream.writeToMain(ControlPacket.createVideoAck(videoFramesDone.get()));
      } catch (Exception ignored) {
        // 写入失败由读线程/看门狗发现断线并处理
      }
    }, lastVideoAckTime + VIDEO_ACK_INTERVAL - System.currentTimeMillis());
  }

  private void watchdog() {
    while (!isClose) {
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        return;
      }
      if (System.currentTimeMillis() - lastReceiveTime > STREAM_TIMEOUT) {
        // Where were the readers when data stopped? (diagnostics: network silence vs a stuck reader)
        StringBuilder where = new StringBuilder();
        for (Thread thread : new Thread[]{mainStreamInThread, videoStreamInThread}) {
          where.append("\n  ").append(thread.getName()).append(" ").append(thread.getState());
          StackTraceElement[] stack = thread.getStackTrace();
          for (int i = 0; i < Math.min(8, stack.length); i++) where.append("\n    at ").append(stack[i]);
        }
        PublicTools.logToast("player", "watchdog: no data for " + STREAM_TIMEOUT + " ms; last main " + (System.currentTimeMillis() - lastMainTime)
          + " ms ago, last video " + (System.currentTimeMillis() - lastVideoTime) + " ms ago" + where, false);
        connectionLost("timeout: no data for " + STREAM_TIMEOUT + "ms");
        return;
      }
    }
  }

  private void connectionLost(String reason) {
    if (isClose || failed) return;
    PublicTools.logToast("player", "connection lost: " + reason, false);
    Client.sendAction(uuid, "connectionLost", ByteBuffer.wrap(reason.getBytes(StandardCharsets.UTF_8)), 0);
  }

  private void decoderFailed(String reason) {
    if (isClose || failed) return;
    failed = true;
    ByteBuffer message = ByteBuffer.wrap(reason.getBytes(StandardCharsets.UTF_8));
    AppData.uiHandler.post(() -> Client.sendAction(uuid, "fatal", message, 0));
  }

  public void close() {
    if (isClose) return;
    isClose = true;
    mainStreamInThread.interrupt();
    videoStreamInThread.interrupt();
    watchdogThread.interrupt();
    playHandlerThread.quitSafely();
  }

  // 等待读线程退出并释放解码器：新解码器要绑定同一个Surface，旧的必须先释放
  public void join(long timeoutMs) {
    long deadline = System.currentTimeMillis() + timeoutMs;
    for (Thread thread : new Thread[]{mainStreamInThread, videoStreamInThread}) {
      long remaining = deadline - System.currentTimeMillis();
      if (remaining <= 0) return;
      try {
        thread.join(remaining);
      } catch (InterruptedException e) {
        return;
      }
    }
  }
}
