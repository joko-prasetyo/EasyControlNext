package com.shiyunjin.easycontrolnext.app.adb;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;

public class TcpChannel implements AdbChannel {
  private final Socket socket;
  private final InputStream inputStream;
  private final OutputStream outputStream;

  public TcpChannel(String host, int port) throws IOException {
    socket = new Socket();
    // 每个ADB包(包括每次收到数据后回复的OKAY)都是单独一次写入，必须立即发出：
    // 开启Nagle时OKAY会被扣留到对端确认(最长约40ms以上)，期间adbd无法发送下一个数据包
    socket.setTcpNoDelay(true);
    socket.setKeepAlive(true);
    socket.connect(new InetSocketAddress(host, port), 10_000);
    inputStream = socket.getInputStream();
    outputStream = socket.getOutputStream();
  }

  @Override
  public void write(ByteBuffer data) throws IOException {
    outputStream.write(data.array());
  }

  @Override
  public void flush() throws IOException {
    outputStream.flush();
  }

  @Override
  public ByteBuffer read(int size) throws IOException {
    byte[] buffer = new byte[size];
    int bytesRead = 0;
    while (bytesRead < size) {
      int read = inputStream.read(buffer, bytesRead, size - bytesRead);
      if (read == -1) break;
      bytesRead += read;
    }
    return ByteBuffer.wrap(buffer);
  }

  @Override
  public void close() {
    try {
      outputStream.close();
      inputStream.close();
      socket.close();
    } catch (Exception ignored) {
    }
  }
}
