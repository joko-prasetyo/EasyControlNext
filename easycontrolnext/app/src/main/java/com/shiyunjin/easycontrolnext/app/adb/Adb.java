package com.shiyunjin.easycontrolnext.app.adb;

import android.hardware.usb.UsbDevice;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.shiyunjin.easycontrolnext.app.buffer.BufferStream;
import com.shiyunjin.easycontrolnext.app.entity.MyInterface;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

// 此部分代码摘抄借鉴了tananaev大佬的开源代码(https://github.com/tananaev/adblib)以及开源库dadb(https://github.com/mobile-dev-inc/dadb)
// 因为官方adb协议文档写的十分糟糕，因此此部分代码的实现参考了cstyan大佬所整理的文档，再次进行感谢：https://github.com/cstyan/adbDocumentation
public class Adb {
  private boolean isClosed = false;
  private final AdbChannel channel;
  // 重连线程与主线程可能同时打开流，ID分配必须原子
  private final AtomicInteger localIdPool = new AtomicInteger(1);
  // 等待超时而放弃的OPEN：若对端稍后才回应，创建出来的流要立即关闭，不能留成“孤儿”
  private final Set<Integer> abandonedOpens = Collections.newSetFromMap(new ConcurrentHashMap<>());
  private int MAX_DATA = AdbProtocol.CONNECT_MAXDATA;
  // Both sides support delayed_ack (see AdbProtocol.DELAYED_ACK_WINDOW)
  private volatile boolean delayedAck = false;
  public final ConcurrentHashMap<Integer, BufferStream> connectionStreams = new ConcurrentHashMap<>(10);
  private final ConcurrentHashMap<Integer, BufferStream> openStreams = new ConcurrentHashMap<>(5);

  private final Thread handleInThread = new Thread(this::handleIn);

  public Adb(String address,int port, AdbKeyPair keyPair) throws Exception {
    channel = new TcpChannel(address, port);
    connect(keyPair, AdbProtocol.CONNECT_MAXDATA_NETWORK, true);
  }

  public Adb(UsbDevice usbDevice, AdbKeyPair keyPair) throws Exception {
    if (usbDevice == null) throw new IOException("no usb connect");
    channel = new UsbChannel(usbDevice);
    connect(keyPair, AdbProtocol.CONNECT_MAXDATA, false);
  }

  public Adb(AbsAdbConnectionManager manager) throws IOException, InterruptedException {
    channel = new ManagerChannel(manager, this);
  }

  private void connect(AdbKeyPair keyPair, int maxData, boolean offerDelayedAck) throws Exception {
    // 连接ADB并认证
    channel.write(AdbProtocol.generateConnect(maxData, offerDelayedAck));
    AdbProtocol.AdbMessage message = AdbProtocol.AdbMessage.parseAdbMessage(channel);
    if (message.command == AdbProtocol.CMD_AUTH) {
      channel.write(AdbProtocol.generateAuth(AdbProtocol.AUTH_TYPE_SIGNATURE, keyPair.signPayload(message.payload)));
      message = AdbProtocol.AdbMessage.parseAdbMessage(channel);
      if (message.command == AdbProtocol.CMD_AUTH) {
        channel.write(AdbProtocol.generateAuth(AdbProtocol.AUTH_TYPE_RSA_PUBLIC, keyPair.publicKeyBytes));
        message = AdbProtocol.AdbMessage.parseAdbMessage(channel);
      }
    }
    if (message.command != AdbProtocol.CMD_CNXN) {
      channel.close();
      throw new Exception("ADB连接失败");
    }
    MAX_DATA = message.arg1;
    // The device banner lists its features ("device::...;features=a,b,c"); use delayed_ack only if both sides have it
    if (offerDelayedAck && message.payload != null) {
      byte[] banner = new byte[message.payload.remaining()];
      message.payload.duplicate().get(banner);
      String text = new String(banner, java.nio.charset.StandardCharsets.UTF_8);
      int index = text.indexOf("features=");
      if (index >= 0) {
        for (String feature : text.substring(index + 9).split("[,;\\x00]")) {
          if (AdbProtocol.FEATURE_DELAYED_ACK.equals(feature.trim())) delayedAck = true;
        }
      }
    }
    // 启动后台进程
    handleInThread.setPriority(Thread.MAX_PRIORITY);
    handleInThread.start();
  }

  // 等待对端回应OPEN的最长时间。以前无限等待：ADB连接若无声无息地断了(无线网络切换等)，调用方会永久卡住
  private static final int OPEN_TIMEOUT = 1000 * 10;

  private BufferStream open(String destination, boolean canMultipleSend) throws InterruptedException, IOException {
    return open(destination, canMultipleSend, OPEN_TIMEOUT);
  }

  private BufferStream open(String destination, boolean canMultipleSend, int timeoutMs) throws InterruptedException, IOException {
    int localId = localIdPool.getAndIncrement() * (canMultipleSend ? 1 : -1);
    writeToChannel(AdbProtocol.generateOpen(localId, destination, delayedAck ? AdbProtocol.DELAYED_ACK_WINDOW : 0));
    long deadline = System.currentTimeMillis() + timeoutMs;
    BufferStream bufferStream = null;
    synchronized (this) {
      // 在同一把锁内检查再等待，避免错过notifyAll
      while (!isClosed && (bufferStream = openStreams.get(localId)) == null) {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) {
          abandonedOpens.add(localId);
          BufferStream late = openStreams.remove(localId);
          if (late != null && abandonedOpens.remove(localId)) late.close();
          throw new IOException("adb open timeout: " + destination);
        }
        wait(remaining);
      }
    }
    openStreams.remove(localId);
    if (bufferStream == null) throw new IOException("adb closed");
    return bufferStream;
  }

  public String restartOnTcpip(int port) throws InterruptedException, IOException {
    BufferStream bufferStream = open("tcpip:" + port, false);
    do {
      synchronized (this) {
        wait();
      }
    } while (!bufferStream.isClosed());
    return new String(bufferStream.readByteArrayBeforeClose().array());
  }

  public void pushFile(InputStream file, String remotePath, MyInterface.MyFunctionInt handleProcess) throws Exception {
    // 打开链接
    BufferStream bufferStream = open("sync:", false);
    // 发送信令，建立push通道
    String sendString = remotePath + ",33206";
    byte[] bytes = sendString.getBytes();
    bufferStream.write(AdbProtocol.generateSyncHeader("SEND", sendString.length()));
    bufferStream.write(ByteBuffer.wrap(bytes));
    // 发送文件
    byte[] byteArray = new byte[10240 - 8];
    int hasSendLen = 0;
    int allNeedSendLen = file.available();
    int lastProcess = 0;
    int len = file.read(byteArray, 0, byteArray.length);
    do {
      bufferStream.write(AdbProtocol.generateSyncHeader("DATA", len));
      bufferStream.write(ByteBuffer.wrap(byteArray, 0, len));
      hasSendLen += len;
      int newProcess = (int) (((float) hasSendLen / allNeedSendLen) * 100);
      if (newProcess != lastProcess) {
        lastProcess = newProcess;
        if (handleProcess != null) handleProcess.run(lastProcess);
      }
      len = file.read(byteArray, 0, byteArray.length);
    } while (len > 0);
    file.close();
    // 传输完成，为了方便，文件日期定为2024.1.1 0:0
    bufferStream.write(AdbProtocol.generateSyncHeader("DONE", 1704038400));
    bufferStream.write(AdbProtocol.generateSyncHeader("QUIT", 0));
    do {
      synchronized (this) {
        wait();
      }
    } while (!bufferStream.isClosed());
  }

  // shell命令的最长执行时间。以前无限等待：ADB失效时调用线程(包括控制器主线程)会永久卡死
  private static final int CMD_TIMEOUT = 1000 * 30;

  public String runAdbCmd(String cmd) throws Exception {
    BufferStream bufferStream = open("shell:" + cmd, true);
    long deadline = System.currentTimeMillis() + CMD_TIMEOUT;
    synchronized (this) {
      while (!bufferStream.isClosed()) {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) {
          bufferStream.close();
          throw new IOException("adb shell timeout: " + cmd);
        }
        wait(remaining);
      }
    }
    return new String(bufferStream.readByteArrayBeforeClose().array());
  }

  public BufferStream getShell() throws InterruptedException, IOException {
    return open("shell:", true);
  }

  public BufferStream tcpForward(int port) throws IOException, InterruptedException {
    return tcpForward(port, OPEN_TIMEOUT);
  }

  public BufferStream tcpForward(int port, int timeoutMs) throws IOException, InterruptedException {
    BufferStream bufferStream = open("tcp:" + port, true, timeoutMs);
    if (bufferStream.isClosed()) throw new IOException("error forward");
    return bufferStream;
  }

  public BufferStream localSocketForward(String socketName) throws IOException, InterruptedException {
    BufferStream bufferStream = open("localabstract:" + socketName, true);
    if (bufferStream.isClosed()) throw new IOException("error forward");
    return bufferStream;
  }

  private void handleIn() {
    try {
      while (!Thread.interrupted()) {
        AdbProtocol.AdbMessage message = AdbProtocol.AdbMessage.parseAdbMessage(channel);
        BufferStream bufferStream = connectionStreams.get(message.arg1);
        boolean isNeedNotify = bufferStream == null;
        // 新连接
        if (isNeedNotify) bufferStream = createNewStream(message.arg1, message.arg0, message.arg1 > 0);
        switch (message.command) {
          case AdbProtocol.CMD_OKAY:
            bufferStream.setCanWrite(true);
            break;
          case AdbProtocol.CMD_WRTE:
            bufferStream.pushSource(message.payload);
            // delayed_ack: the OKAY returns the credit; the device does not wait for it unless the window is used up
            writeToChannel(delayedAck ? AdbProtocol.generateOkay(message.arg1, message.arg0, message.payloadLength)
              : AdbProtocol.generateOkay(message.arg1, message.arg0));
            break;
          case AdbProtocol.CMD_CLSE:
            bufferStream.close();
            isNeedNotify = true;
            break;
        }
        if (isNeedNotify) {
          synchronized (this) {
            notifyAll();
          }
        }
      }
    } catch (Exception ignored) {
      close();
    }
  }

  public void writeToChannel(ByteBuffer byteBuffer) {
    synchronized (channel) {
      try {
        channel.write(byteBuffer);
      } catch (Exception ignored) {
        close();
      }
    }
  }

  public BufferStream createNewStream(int localId, int remoteId, boolean canMultipleSend) throws Exception {
    BufferStream bufferStream = newStream(localId, remoteId, canMultipleSend);
    if (abandonedOpens.remove(localId)) {
      openStreams.remove(localId);
      bufferStream.close();
    }
    return bufferStream;
  }

  private BufferStream newStream(int localId, int remoteId, boolean canMultipleSend) throws Exception {
    return new BufferStream(false, canMultipleSend, new BufferStream.UnderlySocketFunction() {
      @Override
      public void connect(BufferStream bufferStream) {
        connectionStreams.put(localId, bufferStream);
        openStreams.put(localId, bufferStream);
      }

      @Override
      public void write(BufferStream bufferStream, ByteBuffer buffer) {
        while (buffer.hasRemaining()) {
          byte[] byteArray = new byte[Math.min(MAX_DATA - 128, buffer.remaining())];
          buffer.get(byteArray);
          writeToChannel(AdbProtocol.generateWrite(localId, remoteId, byteArray));
        }
      }

      @Override
      public void flush(BufferStream bufferStream) {
        writeToChannel(delayedAck ? AdbProtocol.generateOkay(localId, remoteId, 0) : AdbProtocol.generateOkay(localId, remoteId));
      }

      @Override
      public void close(BufferStream bufferStream) {
        connectionStreams.remove(localId);
        writeToChannel(AdbProtocol.generateClose(localId, remoteId));
      }
    });
  }

  public boolean isClosed() {
    return isClosed;
  }

  public void close() {
    if (isClosed) return;
    isClosed = true;
    handleInThread.interrupt();
    for (Object bufferStream : connectionStreams.values().toArray()) ((BufferStream) bufferStream).close();
    channel.close();
    synchronized (this) {
      notifyAll();
    }
  }

}
