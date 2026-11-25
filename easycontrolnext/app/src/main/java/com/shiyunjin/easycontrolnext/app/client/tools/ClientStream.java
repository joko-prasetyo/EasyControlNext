package com.shiyunjin.easycontrolnext.app.client.tools;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.zip.CRC32;

import com.shiyunjin.easycontrolnext.app.BuildConfig;
import com.shiyunjin.easycontrolnext.app.R;
import com.shiyunjin.easycontrolnext.app.adb.Adb;
import com.shiyunjin.easycontrolnext.app.buffer.BufferStream;
import com.shiyunjin.easycontrolnext.app.client.decode.DecodecTools;
import com.shiyunjin.easycontrolnext.app.entity.AppData;
import com.shiyunjin.easycontrolnext.app.entity.Device;
import com.shiyunjin.easycontrolnext.app.entity.MyInterface;
import com.shiyunjin.easycontrolnext.app.helper.PublicTools;

// 连接协议：每个socket连上后先发送 [角色1字节][token 16字节]，服务端据此区分main/video并校验身份。
// 服务端在断线后会保留会话一段时间，reconnect() 可以接回原会话(编码器、分辨率等都不重建)。
public class ClientStream {
  private volatile boolean isClose = false;
  private volatile boolean connected = false;
  private volatile boolean connectDirect = false;
  private volatile boolean quitRequested = false;
  private volatile Adb adb;
  private volatile Socket mainSocket;
  private volatile Socket videoSocket;
  private volatile OutputStream mainOutputStream;
  private volatile DataInputStream mainDataInputStream;
  private volatile DataInputStream videoDataInputStream;
  private volatile BufferStream mainBufferStream;
  private volatile BufferStream videoBufferStream;
  private Thread connectThread = null;
  private final byte[] token;
  // UDP video (see UdpVideoReceiver): negotiated in the background after every (re)connect
  private volatile UdpVideoReceiver udpReceiver = null;
  private volatile int udpFirstFrame = 0;
  private int connectionCount = 0;
  // The switch may only be requested once the decoder has its first (TCP) frames: a marker arriving before
  // them would be taken for codec config
  private boolean videoStarted = false;
  private boolean udpGoPending = false;
  private int udpGoSession = 0;
  // Returned by readFrameFromVideo when the server moved video to UDP
  public static final ByteBuffer UDP_SWITCH = ByteBuffer.allocate(0);
  private static String serverName = null;
  private static final String serverLog = "/data/local/tmp/easycontrolnext_server.log";
  private static final boolean supportH265 = DecodecTools.isSupportH265();
  private static final boolean supportOpus = DecodecTools.isSupportOpus();

  private static final byte ROLE_MAIN = 0;
  private static final byte ROLE_VIDEO = 1;
  private static final int timeoutDelay = 1000 * 15;
  private static final int retryInterval = 300;

  public ClientStream(Device device, MyInterface.MyFunctionBoolean handle) {
    token = createToken(device);
    // 超时
    Thread timeOutThread = new Thread(() -> {
      try {
        Thread.sleep(timeoutDelay);
        PublicTools.logToast("stream", AppData.applicationContext.getString(R.string.toast_timeout), true);
        handle.run(false);
        if (connectThread != null) connectThread.interrupt();
      } catch (InterruptedException ignored) {
      }
    });
    // 连接
    connectThread = new Thread(() -> {
      try {
        adb = AdbTools.connectADB(device);
        startServer(device);
        connectServer(device, timeoutDelay);
        negotiateUdp(device);
        handle.run(true);
      } catch (Exception e) {
        PublicTools.logToast("stream", e.toString(), true);
        handle.run(false);
      } finally {
        timeOutThread.interrupt();
      }
    });
    connectThread.start();
    timeOutThread.start();
  }

  // 由设备uuid与本机ADB公钥派生：同一设备条目的令牌保持不变(断线重连、甚至重启App后都能接回仍在等待的服务端)，
  // 而局域网内的其他人无法猜到，不能连上服务端端口获取画面或注入操作
  private static byte[] createToken(Device device) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(device.uuid.getBytes(StandardCharsets.UTF_8));
      if (AppData.keyPair != null) digest.update(AppData.keyPair.publicKeyBytes);
      return Arrays.copyOf(digest.digest(), 16);
    } catch (Exception e) {
      byte[] random = new byte[16];
      new SecureRandom().nextBytes(random);
      return random;
    }
  }

  private byte[] hello(byte role) {
    byte[] hello = new byte[1 + token.length];
    hello[0] = role;
    System.arraycopy(token, 0, hello, 1, token.length);
    return hello;
  }

  private static String toHex(byte[] bytes) {
    StringBuilder builder = new StringBuilder();
    for (byte b : bytes) builder.append(String.format("%02x", b));
    return builder.toString();
  }

  // 服务端文件名包含服务端jar内容的CRC32。客户端只在文件不存在时才推送服务端：
  // 若文件名只含版本号，已存有同版本号旧服务端的设备会继续运行旧服务端，
  // 新旧协议不一致时会把握手/新命令的字节误当成别的控制命令执行(例如电源键、改分辨率)
  private static synchronized String getServerName() {
    if (serverName != null) return serverName;
    CRC32 crc = new CRC32();
    try (InputStream in = AppData.applicationContext.getResources().openRawResource(R.raw.easycontrolnext_server)) {
      byte[] buffer = new byte[8192];
      int len;
      while ((len = in.read(buffer)) > 0) crc.update(buffer, 0, len);
    } catch (IOException ignored) {
    }
    serverName = "/data/local/tmp/easycontrolnext_server_" + BuildConfig.VERSION_CODE + "_" + Long.toHexString(crc.getValue()) + ".jar";
    return serverName;
  }

  // 启动Server
  private void startServer(Device device) throws Exception {
    String serverName = getServerName();
    boolean isNewServer = !adb.runAdbCmd("ls /data/local/tmp/easycontrolnext_*").contains(serverName);
    // 需要推送新服务端，说明设备上若还有服务端在运行(包括断线等待期中的)，一定是别的版本：
    // 必须先结束它，否则新进程抢不到端口，客户端会连到协议不同的旧服务端上
    if (isNewServer) adb.runAdbCmd("pkill -f 'easycontrolnext[.]server[.]Server'; sleep 0.3");
    if (BuildConfig.ENABLE_DEBUG_FEATURE || isNewServer) {
      adb.runAdbCmd("rm /data/local/tmp/easycontrolnext_* ");
      adb.pushFile(AppData.applicationContext.getResources().openRawResource(R.raw.easycontrolnext_server), serverName, null);
    }
    String serverCmd = "app_process -Djava.class.path=" + serverName + " / com.shiyunjin.easycontrolnext.server.Server"
      + " serverPort=" + device.serverPort
      + " listenClip=" + (device.listenClip ? 1 : 0)
      + " isAudio=" + (device.isAudio ? 1 : 0)
      + " maxSize=" + device.maxSize
      + " maxFps=" + device.maxFps
      + " maxVideoBit=" + device.maxVideoBit
      + " keepAwake=" + (device.keepWakeOnRunning ? 1 : 0)
      + " supportH265=" + ((device.useH265 && supportH265) ? 1 : 0)
      + " supportOpus=" + (supportOpus ? 1 : 0)
      + " token=" + toHex(token)
      + " startApp=" + device.startApp;
    // 用setsid让服务端脱离adb shell会话：以前服务端运行在交互式shell里，无线ADB一断就被SIGHUP杀死，
    // 即使视频走的是直连TCP也会整体断开。现在服务端自己管理生命周期(首次连接20秒超时、断线后等待重连)。
    // 若同端口已有服务端在等待重连，新进程会直接退出，客户端随后接回原会话。
    adb.runAdbCmd("trap '' HUP; S=$(command -v setsid); $S " + serverCmd + " </dev/null >" + serverLog + " 2>&1 &");
  }

  // Path that worked last time; tried first on the next (re)connect
  private boolean preferAdb = false;

  // 连接Server：直连与ADB中转交替尝试，直到成功或超时。
  // 以前先把一半的超时时间全部花在直连上：被控端端口不可直达时(例如redroid的端口没有映射出来)，
  // 直连永远被拒绝，每次连接都白等数秒(首次约7.5秒，断线重连约2秒)才轮到ADB
  private void connectServer(Device device, int timeout) throws Exception {
    long deadline = System.currentTimeMillis() + timeout;
    boolean canDirect = !device.isLinkDevice();
    InetSocketAddress address = canDirect ? new InetSocketAddress(PublicTools.getIp(device.address), device.serverPort) : null;
    while (true) {
      if (canDirect && !preferAdb && tryDirect(address, deadline)) return;
      if (tryAdb(device, deadline)) return;
      if (canDirect && preferAdb && tryDirect(address, deadline)) return;
      if (isClose || System.currentTimeMillis() + retryInterval >= deadline) throw new Exception(AppData.applicationContext.getString(R.string.toast_connect_server));
      Thread.sleep(retryInterval);
    }
  }

  private boolean tryDirect(InetSocketAddress address, long deadline) throws Exception {
    Socket main = null;
    Socket video = null;
    try {
      // Short attempts: a port that is not reachable should not hold up the ADB path
      long attemptDeadline = Math.min(deadline, System.currentTimeMillis() + 1500);
      main = connectSocket(address, ROLE_MAIN, attemptDeadline);
      video = connectSocket(address, ROLE_VIDEO, attemptDeadline);
      OutputStream mainOut = main.getOutputStream();
      // 带缓冲读取：readInt/readByte不再每个字节一次系统调用
      DataInputStream mainIn = new DataInputStream(new BufferedInputStream(main.getInputStream(), 16 * 1024));
      DataInputStream videoIn = new DataInputStream(new BufferedInputStream(video.getInputStream(), 64 * 1024));
      synchronized (this) {
        mainSocket = main;
        videoSocket = video;
        mainOutputStream = mainOut;
        mainDataInputStream = mainIn;
        videoDataInputStream = videoIn;
        connectDirect = true;
        connected = true;
      }
      preferAdb = false;
      abortIfClosed();
      return true;
    } catch (Exception e) {
      closeQuietly(main);
      closeQuietly(video);
      PublicTools.logToast("stream", "direct connect failed: " + e, false);
      if (isClose) throw new IOException("closed");
      return false;
    }
  }

  private boolean tryAdb(Device device, long deadline) throws Exception {
    BufferStream main = null;
    BufferStream video = null;
    try {
      if (adb == null || adb.isClosed()) adb = AdbTools.connectADB(device);
      // 每次打开都受本次尝试剩余时间限制：ADB连接若已无声无息地失效，不会每次都卡满默认超时
      main = adb.tcpForward(device.serverPort, (int) Math.max(1000, deadline - System.currentTimeMillis()));
      main.write(ByteBuffer.wrap(hello(ROLE_MAIN)));
      // 为了减少adb同步阻塞的问题，此处分开音视频流
      video = adb.tcpForward(device.serverPort, (int) Math.max(1000, deadline - System.currentTimeMillis()));
      video.write(ByteBuffer.wrap(hello(ROLE_VIDEO)));
      synchronized (this) {
        mainBufferStream = main;
        videoBufferStream = video;
        connectDirect = false;
        connected = true;
      }
      preferAdb = true;
      abortIfClosed();
      return true;
    } catch (Exception e) {
      if (main != null) main.close();
      if (video != null) video.close();
      PublicTools.logToast("stream", "adb connect failed: " + e, false);
      if (isClose) throw new IOException("closed");
      return false;
    }
  }

  // 用户在重连过程中关闭了会话：刚建立的连接不能留下，否则服务端会挂在空闲连接上，
  // 设备的修改(分辨率、常亮等)要等心跳超时+等待期(约70秒)才恢复
  private void abortIfClosed() throws IOException {
    if (!isClose) return;
    if (quitRequested) {
      try {
        writeToMain(ControlPacket.createQuitEvent());
      } catch (Exception ignored) {
      }
    }
    closeStreams();
    throw new IOException("closed");
  }

  private Socket connectSocket(InetSocketAddress address, byte role, long deadline) throws IOException {
    Socket socket = new Socket();
    try {
      // 关闭Nagle：触控小包立即发出，不再与延迟ACK互相等待(最多约40ms)
      socket.setTcpNoDelay(true);
      socket.connect(address, (int) Math.max(500, Math.min(3000, deadline - System.currentTimeMillis())));
      socket.getOutputStream().write(hello(role));
      return socket;
    } catch (IOException e) {
      closeQuietly(socket);
      throw e;
    }
  }

  // 断线后接回服务端上原来的会话
  public void reconnect(Device device, int timeout) throws Exception {
    closeStreams();
    if (isClose) throw new IOException("closed");
    connectServer(device, timeout);
    negotiateUdp(device);
  }

  // Try UDP for this connection: HELLO/WELCOME proves both directions work, then ask the server to switch.
  // Runs in the background; if UDP is not reachable (e.g. the port is only reachable through ADB) nothing changes
  private void negotiateUdp(Device device) {
    if (!AppData.setting.getUdpVideo() || device.isLinkDevice()) return;
    final int connection;
    synchronized (this) {
      connection = ++connectionCount;
    }
    new Thread(() -> {
      UdpVideoReceiver receiver = null;
      try {
        java.net.InetAddress ip = java.net.InetAddress.getByName(PublicTools.getIp(device.address));
        receiver = new UdpVideoReceiver(ip, device.serverPort, token);
        int sessionId = receiver.handshake(1500);
        boolean sendNow;
        synchronized (this) {
          if (isClose || !connected || connection != connectionCount) throw new IOException("stale");
          udpReceiver = receiver;
          sendNow = videoStarted;
          udpGoPending = !sendNow;
          udpGoSession = sessionId;
        }
        if (sendNow) writeToMain(ControlPacket.createUdpGo(sessionId));
        PublicTools.logToast("stream", "UDP video ready", false);
      } catch (Exception e) {
        PublicTools.logToast("stream", "UDP not used: " + e, false);
        if (receiver != null && udpReceiver != receiver) receiver.close();
      }
    }, "easycontrol_udp_hello").start();
  }

  // Called by the player once the decoder is running
  public void onVideoStarted() throws Exception {
    int sessionId;
    synchronized (this) {
      videoStarted = true;
      if (!udpGoPending) return;
      udpGoPending = false;
      sessionId = udpGoSession;
    }
    writeToMain(ControlPacket.createUdpGo(sessionId));
  }

  public UdpVideoReceiver getUdpReceiver() {
    return udpReceiver;
  }

  public int getUdpFirstFrame() {
    return udpFirstFrame;
  }

  public boolean isConnected() {
    return connected;
  }

  // 重连一直失败时调用：服务端进程若已不在(崩溃、被系统杀死、等待重连超时后退出、被控端重启)，重新启动一个，
  // 随后的重连即可成功。服务端仍在运行时什么都不做
  public void restartServerIfDead(Device device) throws Exception {
    if (isClose) return;
    if (adb == null || adb.isClosed()) adb = AdbTools.connectADB(device);
    String out = adb.runAdbCmd("pgrep -f 'easycontrolnext[.]server[.]Server serverPort=" + device.serverPort + " ' >/dev/null && echo ALIVE || echo DEAD");
    if (!isClose && out.contains("DEAD")) startServer(device);
  }

  public String runShell(String cmd) throws Exception {
    return adb.runAdbCmd(cmd);
  }

  public byte readByteFromMain() throws IOException, InterruptedException {
    if (connectDirect) return mainDataInputStream.readByte();
    else return mainBufferStream.readByte();
  }

  public byte readByteFromVideo() throws IOException, InterruptedException {
    if (connectDirect) return videoDataInputStream.readByte();
    else return videoBufferStream.readByte();
  }

  public int readIntFromMain() throws IOException, InterruptedException {
    if (connectDirect) return mainDataInputStream.readInt();
    else return mainBufferStream.readInt();
  }

  public int readIntFromVideo() throws IOException, InterruptedException {
    if (connectDirect) return videoDataInputStream.readInt();
    else return videoBufferStream.readInt();
  }

  public ByteBuffer readByteArrayFromMain(int size) throws IOException, InterruptedException {
    if (connectDirect) {
      byte[] buffer = new byte[size];
      mainDataInputStream.readFully(buffer);
      return ByteBuffer.wrap(buffer);
    } else return mainBufferStream.readByteArray(size);
  }

  public ByteBuffer readByteArrayFromVideo(int size) throws IOException, InterruptedException {
    if (connectDirect) {
      byte[] buffer = new byte[size];
      videoDataInputStream.readFully(buffer);
      return ByteBuffer.wrap(buffer);
    }
    return videoBufferStream.readByteArray(size);
  }

  // 以前每读一帧都额外发一个ADB OKAY(flush)。Adb.handleIn在收到每个数据包时已经回复过OKAY，
  // 这个是多余的：每秒多发60~110个小包，白白占用上行带宽并让手机的无线电一直发射(费电)
  public ByteBuffer readFrameFromMain() throws Exception {
    return readByteArrayFromMain(readIntFromMain());
  }

  public ByteBuffer readFrameFromVideo() throws Exception {
    int size = readIntFromVideo();
    if (size == -1) {
      // Marker: the following frames come over UDP, starting with this frame number
      udpFirstFrame = readIntFromVideo();
      return UDP_SWITCH;
    }
    return readByteArrayFromVideo(size);
  }

  // 触控线程与主线程都会写入，加锁防止两个包的字节交错。
  // 断线期间直接失败(而不是排队)：恢复连接后不会把过时的触控补发给被控端
  public synchronized void writeToMain(ByteBuffer byteBuffer) throws Exception {
    if (!connected) throw new IOException("not connected");
    if (connectDirect) mainOutputStream.write(byteBuffer.array());
    else mainBufferStream.write(byteBuffer);
  }

  // 主动关闭时通知服务端立即退出并恢复设备设置(否则服务端会等待重连)
  public void sendQuit() {
    quitRequested = true;
    try {
      writeToMain(ControlPacket.createQuitEvent());
    } catch (Exception ignored) {
    }
  }

  // 只关闭数据连接(不加锁：阻塞在写入上的线程需要靠关闭socket来唤醒)
  public void closeStreams() {
    connected = false;
    synchronized (this) {
      videoStarted = false;
      udpGoPending = false;
    }
    UdpVideoReceiver receiver = udpReceiver;
    udpReceiver = null;
    if (receiver != null) receiver.close();
    closeQuietly(mainSocket);
    closeQuietly(videoSocket);
    BufferStream main = mainBufferStream;
    BufferStream video = videoBufferStream;
    if (main != null) main.close();
    if (video != null) video.close();
  }

  public void close() {
    if (isClose) return;
    isClose = true;
    // 与abortIfClosed配合：先置isClose再检查connected，重连线程先置connected再检查isClose，
    // 无论谁先谁后，至少有一方会把quit发出去(重复发送无害)
    if (quitRequested && connected) {
      try {
        writeToMain(ControlPacket.createQuitEvent());
      } catch (Exception ignored) {
      }
    }
    closeStreams();
  }

  private static void closeQuietly(Socket socket) {
    try {
      if (socket != null) socket.close();
    } catch (Exception ignored) {
    }
  }
}
