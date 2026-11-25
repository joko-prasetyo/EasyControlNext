/*
 * 本项目大量借鉴学习了开源投屏软件：Scrcpy，在此对该项目表示感谢
 */
package com.shiyunjin.easycontrolnext.server;

import android.annotation.SuppressLint;
import android.os.IBinder;
import android.os.IInterface;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantLock;

import com.shiyunjin.easycontrolnext.server.entity.Device;
import com.shiyunjin.easycontrolnext.server.entity.Options;
import com.shiyunjin.easycontrolnext.server.helper.AudioEncode;
import com.shiyunjin.easycontrolnext.server.helper.ControlPacket;
import com.shiyunjin.easycontrolnext.server.helper.FlowControl;
import com.shiyunjin.easycontrolnext.server.helper.UdpVideo;
import com.shiyunjin.easycontrolnext.server.helper.VideoEncode;
import com.shiyunjin.easycontrolnext.server.wrappers.ClipboardManager;
import com.shiyunjin.easycontrolnext.server.wrappers.DisplayManager;
import com.shiyunjin.easycontrolnext.server.wrappers.InputManager;
import com.shiyunjin.easycontrolnext.server.wrappers.SurfaceControl;
import com.shiyunjin.easycontrolnext.server.wrappers.WindowManager;

// 此部分代码摘抄借鉴了著名投屏软件Scrcpy的开源代码(https://github.com/Genymobile/scrcpy/tree/master/server)
//
// 连接模型(支持断线重连)：
//  - 端口在启动时就占用，并在整个进程生命周期内保持监听；
//  - 客户端每个socket连上后先发送 [角色1字节][token 16字节]，因此main/video的连接顺序不再重要；
//  - 每次(重新)连接都会收到与首次连接完全相同的握手，视频从缓存的编解码配置+一个新的关键帧开始；
//  - 断线后编码器继续运行，输出被丢弃；Options.reconnectGrace 秒内没有重连才真正退出并恢复设备设置。
public final class Server {
  public static final byte ROLE_MAIN = 0;
  public static final byte ROLE_VIDEO = 1;
  public static final int TOKEN_SIZE = 16;

  private static final int firstConnectTimeout = 1000 * 20;
  // 超过该时间没有收到客户端任何数据，视为连接已死(客户端每秒发送一次心跳)
  private static final int keepAliveTimeout = 1000 * 10;
  private static final int helloTimeout = 1000 * 3;
  private static final int heartbeatInterval = 1000;

  private static ServerSocket serverSocket;

  // 当前连接(由connLock保护；attach时同时持有两个写锁)
  private static Socket mainSocket;
  private static Socket videoSocket;
  private static OutputStream mainOutputStream;
  private static OutputStream videoOutputStream;
  private static DataInputStream mainInputStream;
  // 连接代数：每次attach递增。写入时携带代数，代数变化后的旧数据直接丢弃，
  // 避免旧连接时期决定发送的帧落在新连接的握手之后
  private static volatile int generation = 0;
  private static volatile boolean attached = false;
  private static volatile boolean everAttached = false;
  private static volatile long detachedSince = System.currentTimeMillis();
  private static volatile long lastKeepAliveTime = System.currentTimeMillis();

  // 锁顺序：mainWriteLock -> videoWriteLock -> connLock
  private static final ReentrantLock mainWriteLock = new ReentrantLock();
  private static final Object videoWriteLock = new Object();
  private static final Object connLock = new Object();

  private static final Object exitLock = new Object();
  private static volatile boolean exiting = false;
  private static boolean canAudio = false;

  public static void main(String... args) {
    try {
      Options.parse(args);
      // 先占用端口，且在修改任何设备设置之前：若同端口已有服务端(例如正处于断线等待期)，
      // 本进程直接退出，客户端会连到旧服务端继续原来的会话
      serverSocket = new ServerSocket(Options.serverPort);
      // Same port number for UDP video (optional: the app falls back to TCP if it is unreachable)
      UdpVideo.start(Options.serverPort);
    } catch (Exception e) {
      e.printStackTrace();
      Runtime.getRuntime().exit(1);
      return;
    }
    Thread timeOutThread = new Thread(() -> {
      try {
        Thread.sleep(firstConnectTimeout + 1000 * 10);
        if (!everAttached) release();
      } catch (InterruptedException ignored) {
      }
    });
    timeOutThread.start();
    ArrayList<Thread> threads = new ArrayList<>();
    try {
      // 初始化
      setManagers();
      Device.init();
      // 初始化子服务(此时尚无连接，输出会被缓存/丢弃，连接后重新同步)
      canAudio = AudioEncode.init();
      VideoEncode.init();
      // 启动
      threads.add(new Thread(Server::executeVideoOut));
      if (canAudio) {
        threads.add(new Thread(Server::executeAudioIn));
        threads.add(new Thread(Server::executeAudioOut));
      }
      threads.add(new Thread(Server::executeControlIn));
      threads.add(new Thread(Server::executeHeartbeat));
      for (Thread thread : threads) thread.setPriority(Thread.MAX_PRIORITY);
      for (Thread thread : threads) thread.start();
      Thread acceptThread = new Thread(Server::executeAccept);
      acceptThread.setDaemon(true);
      acceptThread.start();
      // 程序运行
      long firstDeadline = System.currentTimeMillis() + firstConnectTimeout;
      synchronized (exitLock) {
        while (!exiting) {
          long now = System.currentTimeMillis();
          if (!everAttached && now >= firstDeadline) throw new IOException("首次连接超时");
          exitLock.wait(everAttached ? 0 : firstDeadline - now);
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      // 终止子服务
      for (Thread thread : threads) thread.interrupt();
      // 释放资源
      release();
    }
  }

  private static Method GET_SERVICE_METHOD;

  @SuppressLint({"DiscouragedPrivateApi", "PrivateApi"})
  private static void setManagers() throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
    GET_SERVICE_METHOD = Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String.class);
    // 1
    WindowManager.init(getService("window", "android.view.IWindowManager"));
    // 2
    DisplayManager.init(Class.forName("android.hardware.display.DisplayManagerGlobal").getDeclaredMethod("getInstance").invoke(null));
    // 3
    Class<?> inputManagerClass;
    try {
      inputManagerClass = Class.forName("android.hardware.input.InputManagerGlobal");
    } catch (ClassNotFoundException e) {
      inputManagerClass = android.hardware.input.InputManager.class;
    }
    InputManager.init(inputManagerClass.getDeclaredMethod("getInstance").invoke(null));
    // 4
    ClipboardManager.init(getService("clipboard", "android.content.IClipboard"));
    // 5
    SurfaceControl.init();
  }

  private static IInterface getService(String service, String type) {
    try {
      IBinder binder = (IBinder) GET_SERVICE_METHOD.invoke(null, service);
      Method asInterfaceMethod = Class.forName(type + "$Stub").getMethod("asInterface", IBinder.class);
      return (IInterface) asInterfaceMethod.invoke(null, binder);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  // 持续接受连接。每个连接在独立线程里读取hello，避免不发送hello的连接(例如ADB迟到打开的流)
  // 占住唯一的accept线程，拖慢真正客户端的重连
  private static void executeAccept() {
    while (!exiting) {
      Socket socket;
      try {
        socket = serverSocket.accept();
      } catch (IOException e) {
        if (exiting || serverSocket.isClosed()) return;
        try {
          Thread.sleep(100);
        } catch (InterruptedException ignored) {
          return;
        }
        continue;
      }
      Thread helloThread = new Thread(() -> readHello(socket));
      helloThread.setDaemon(true);
      helloThread.start();
    }
  }

  private static void readHello(Socket socket) {
    byte role;
    try {
      socket.setSoTimeout(helloTimeout);
      DataInputStream in = new DataInputStream(socket.getInputStream());
      role = in.readByte();
      byte[] token = new byte[TOKEN_SIZE];
      in.readFully(token);
      socket.setSoTimeout(0);
      if (!Options.checkToken(token)) throw new IOException("token错误");
      if (role != ROLE_MAIN && role != ROLE_VIDEO) throw new IOException("未知连接角色");
    } catch (IOException e) {
      closeQuietly(socket);
      return;
    }
    onHello(socket, role);
  }

  // 凑齐一对(main+video)后替换当前连接
  private static Socket pendingMain = null;
  private static Socket pendingVideo = null;

  private static synchronized void onHello(Socket socket, byte role) {
    if (role == ROLE_MAIN) {
      closeQuietly(pendingMain);
      pendingMain = socket;
    } else {
      closeQuietly(pendingVideo);
      pendingVideo = socket;
    }
    if (pendingMain != null && pendingVideo != null) {
      try {
        attach(pendingMain, pendingVideo);
      } catch (IOException e) {
        e.printStackTrace();
        closeQuietly(pendingMain);
        closeQuietly(pendingVideo);
      }
      pendingMain = null;
      pendingVideo = null;
    }
  }

  private static void attach(Socket newMain, Socket newVideo) throws IOException {
    // 关闭Nagle：小包(触控/音频)与每帧末尾的小分段不再被延迟确认(最多约40ms)卡住
    newMain.setTcpNoDelay(true);
    newVideo.setTcpNoDelay(true);
    OutputStream newMainOut = newMain.getOutputStream();
    OutputStream newVideoOut = newVideo.getOutputStream();
    // 带缓冲读取：一个触控包只需一次系统调用，而不是每个字节一次
    DataInputStream newMainIn = new DataInputStream(new BufferedInputStream(newMain.getInputStream(), 8192));
    // 客户端可能比服务端更早发现断线并已重连：先断开旧连接
    detach(-1, "被新连接替换");
    mainWriteLock.lock();
    try {
      synchronized (videoWriteLock) {
        synchronized (connLock) {
          // 握手(与首次连接完全相同)：video [是否H265][宽][高]；main [是否有音频][是否Opus]+[分辨率事件]
          ByteBuffer videoHeader = ByteBuffer.allocate(9);
          videoHeader.put((byte) (VideoEncode.isH265() ? 1 : 0));
          videoHeader.putInt(Device.videoSize.first);
          videoHeader.putInt(Device.videoSize.second);
          newVideoOut.write(videoHeader.array());
          if (canAudio) newMainOut.write(new byte[]{1, (byte) (AudioEncode.isOpus() ? 1 : 0)});
          else newMainOut.write(new byte[]{0});
          newMainOut.write(ControlPacket.createVideoSizeEvent().array());
          // 视频从(缓存的)编解码配置+新关键帧开始；音频从缓存的配置包开始。
          // 必须先于generation++：读到新代数的线程一定能看到needResync
          VideoEncode.prepareResync();
          AudioEncode.prepareResync();
          mainSocket = newMain;
          videoSocket = newVideo;
          mainOutputStream = newMainOut;
          videoOutputStream = newVideoOut;
          mainInputStream = newMainIn;
          lastKeepAliveTime = System.currentTimeMillis();
          FlowControl.reset(generation + 1);
          UdpVideo.reset();
          attached = true;
          everAttached = true;
          generation++;
          connLock.notifyAll();
        }
      }
    } finally {
      mainWriteLock.unlock();
    }
    // 必须在attached之后请求：之前产生的关键帧会被当作“无连接”丢弃
    VideoEncode.requestSyncFrame();
    System.out.println("客户端已连接 #" + generation);
    System.out.flush();
  }

  // gen<0 表示无论哪一代都断开
  private static void detach(int gen, String reason) {
    Socket oldMain;
    Socket oldVideo;
    synchronized (connLock) {
      if (!attached || (gen >= 0 && gen != generation)) return;
      attached = false;
      detachedSince = System.currentTimeMillis();
      UdpVideo.reset();
      oldMain = mainSocket;
      oldVideo = videoSocket;
      connLock.notifyAll();
    }
    System.out.println("连接断开: " + reason);
    System.out.flush();
    // 关闭socket以唤醒阻塞在读写上的线程
    closeQuietly(oldMain);
    closeQuietly(oldVideo);
    // 断线瞬间仍按下的手指会“粘住”，全部抬起
    Device.resetTouch();
  }

  public static boolean isAttached() {
    return attached;
  }

  public static int generation() {
    return generation;
  }

  private static void executeVideoOut() {
    try {
      while (!Thread.interrupted()) {
        if (VideoEncode.isHasChangeConfig) {
          VideoEncode.isHasChangeConfig = false;
          VideoEncode.stopEncode();
          VideoEncode.startEncode();
        }
        VideoEncode.encodeOut();
      }
    } catch (Exception e) {
      errorClose(e);
    }
  }

  private static void executeAudioIn() {
    while (!Thread.interrupted()) AudioEncode.encodeIn();
  }

  private static void executeAudioOut() {
    try {
      while (!Thread.interrupted()) AudioEncode.encodeOut();
    } catch (Exception e) {
      errorClose(e);
    }
  }

  // 心跳与超时：服务端每秒向客户端发送心跳(main类型4)，客户端据此判断连接是否存活
  private static void executeHeartbeat() {
    byte[] heartbeat = new byte[]{4};
    while (!Thread.interrupted()) {
      try {
        Thread.sleep(heartbeatInterval);
      } catch (InterruptedException e) {
        return;
      }
      long now = System.currentTimeMillis();
      if (attached) {
        int gen = generation;
        if (now - lastKeepAliveTime > keepAliveTimeout) {
          detach(gen, "心跳超时");
          continue;
        }
        // 主通道正在写(或因网络拥塞阻塞)时跳过：有数据在发就不需要心跳
        if (mainWriteLock.tryLock()) {
          try {
            if (attached && gen == generation) mainOutputStream.write(heartbeat);
          } catch (IOException e) {
            detach(gen, "心跳发送失败");
          } finally {
            mainWriteLock.unlock();
          }
        }
      } else if (everAttached && now - detachedSince > Options.reconnectGrace * 1000L) {
        errorClose(new IOException("重连等待超时"));
        return;
      }
    }
  }

  private static void executeControlIn() {
    while (!Thread.interrupted()) {
      DataInputStream in;
      int gen;
      synchronized (connLock) {
        while (!attached) {
          try {
            connLock.wait();
          } catch (InterruptedException e) {
            return;
          }
        }
        in = mainInputStream;
        gen = generation;
      }
      try {
        while (!Thread.interrupted()) {
          byte type = in.readByte();
          lastKeepAliveTime = System.currentTimeMillis();
          switch (type) {
            case 1:
              ControlPacket.handleTouchEvent(in);
              break;
            case 2:
              ControlPacket.handleKeyEvent(in);
              break;
            case 3:
              ControlPacket.handleClipboardEvent(in);
              break;
            case 4:
              break;
            case 5:
              Device.changeResolution(in.readFloat());
              break;
            case 6:
              Device.rotateDevice();
              break;
            case 7:
              Device.changeScreenPowerMode(in.readByte());
              break;
            case 8:
              Device.changePower(in.readInt());
              break;
            case 9:
              Device.changeResolution(in.readInt(), in.readInt());
              break;
            case 10:
              // 客户端主动关闭：立即退出并恢复设备设置，不进入重连等待
              errorClose(new IOException("客户端主动关闭"));
              return;
            case 11:
              VideoEncode.requestSyncFrame();
              break;
            case 12:
              ControlPacket.sendPong(in.readLong());
              break;
            case 13:
              // 视频确认：客户端已收到(并送入解码器)的累计帧数，用于流控
              FlowControl.onAck(in.readInt(), gen);
              break;
            case 14:
              // The app received our UDP WELCOME: move video to UDP
              switchVideoToUdp(gen, in.readInt());
              break;
            case 15:
              // UDP: a frame could not be completed, the decoder needs a fresh keyframe
              VideoEncode.resyncAfterLoss();
              break;
            default:
              // 不认识的类型无法得知其长度，继续读会把后续字节误当成命令执行(例如电源键、改分辨率)
              throw new IOException("未知控制包类型: " + type);
          }
        }
      } catch (IOException e) {
        detach(gen, "读取失败: " + e);
      }
    }
  }

  // 写入当前连接(剪贴板、分辨率等与连接代数无关的消息)
  public static void writeMain(ByteBuffer byteBuffer) throws IOException {
    writeMain(byteBuffer, generation);
  }

  // 返回是否已写入。gen与当前连接不符(期间发生了重连)时丢弃
  public static boolean writeMain(ByteBuffer byteBuffer, int gen) {
    mainWriteLock.lock();
    try {
      if (!attached || gen != generation) return false;
      try {
        mainOutputStream.write(byteBuffer.array());
        return true;
      } catch (IOException e) {
        detach(gen, "main写入失败: " + e);
        return false;
      }
    } finally {
      mainWriteLock.unlock();
    }
  }

  // Marker [-1][next frame number] is the last packet on the TCP video socket; the following frames go over UDP,
  // starting with a keyframe (the app keeps decoding with the same decoder)
  private static void switchVideoToUdp(int gen, int sessionId) {
    synchronized (videoWriteLock) {
      if (!attached || gen != generation || !UdpVideo.activate(gen, sessionId)) return;
      try {
        ByteBuffer marker = ByteBuffer.allocate(8);
        marker.putInt(-1);
        marker.putInt(FlowControl.sentCount());
        videoOutputStream.write(marker.array());
      } catch (IOException e) {
        UdpVideo.reset();
        detach(gen, "video写入失败: " + e);
        return;
      }
    }
    VideoEncode.prepareResync();
    VideoEncode.requestSyncFrame();
  }

  public static boolean writeVideo(byte[] data, int len, int gen) {
    synchronized (videoWriteLock) {
      if (!attached || gen != generation) return false;
      try {
        if (UdpVideo.isActive(gen)) {
          // Frame number = index FlowControl gives this frame, so the app's acks line up with it
          UdpVideo.sendFrame(data, 4, len - 4, FlowControl.sentCount());
        } else videoOutputStream.write(data, 0, len);
        FlowControl.onSent(len);
        return true;
      } catch (IOException e) {
        detach(gen, "video写入失败: " + e);
        return false;
      }
    }
  }

  public static void errorClose(Exception e) {
    e.printStackTrace();
    exiting = true;
    synchronized (exitLock) {
      exitLock.notifyAll();
    }
  }

  private static void closeQuietly(java.io.Closeable closeable) {
    try {
      if (closeable != null) closeable.close();
    } catch (Exception ignored) {
    }
  }

  // 释放资源
  private static void release() {
    exiting = true;
    for (int i = 0; i < 4; i++) {
      try {
        switch (i) {
          case 0:
            closeQuietly(serverSocket);
            UdpVideo.close();
            closeQuietly(mainSocket);
            closeQuietly(videoSocket);
            break;
          case 1:
            VideoEncode.release();
            AudioEncode.release();
            break;
          case 2:
            Device.fallbackResolution();
            Device.fallbackScreenLightTimeout();
          case 3:
            Runtime.getRuntime().exit(0);
            break;
        }
      } catch (Exception ignored) {
      }
    }
  }

}
