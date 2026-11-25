/*
 * 本项目大量借鉴学习了开源投屏软件：Scrcpy，在此对该项目表示感谢
 */
package com.shiyunjin.easycontrolnext.server.helper;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import com.shiyunjin.easycontrolnext.server.Server;
import com.shiyunjin.easycontrolnext.server.entity.Device;

public final class ControlPacket {

  // 复用的视频发送缓冲区，避免每帧分配(关键帧可达数百KB)造成GC抖动
  private static ByteBuffer videoPacket = ByteBuffer.allocate(256 * 1024);

  // 仅由视频输出线程调用；返回是否已写入gen对应的连接
  public static boolean sendVideoEvent(long pts, ByteBuffer data, int gen) {
    int size = data.remaining() + 8;
    if (size < 8) return false;
    if (videoPacket.capacity() < 4 + size) videoPacket = ByteBuffer.allocate(Integer.highestOneBit(4 + size) << 1);
    videoPacket.clear();
    videoPacket.putInt(size);
    videoPacket.putLong(pts);
    videoPacket.put(data);
    return Server.writeVideo(videoPacket.array(), videoPacket.position(), gen);
  }

  public static boolean sendAudioEvent(ByteBuffer data, int gen) {
    int size = data.remaining();
    if (size < 0) return false;
    ByteBuffer byteBuffer = ByteBuffer.allocate(5 + size);
    byteBuffer.put((byte) 1);
    byteBuffer.putInt(size);
    byteBuffer.put(data);
    byteBuffer.flip();
    return Server.writeMain(byteBuffer, gen);
  }

  public static void sendClipboardEvent(String newClipboardText) {
    byte[] tmpTextByte = newClipboardText.getBytes(StandardCharsets.UTF_8);
    if (tmpTextByte.length == 0 || tmpTextByte.length > 5000) return;
    ByteBuffer byteBuffer = ByteBuffer.allocate(5 + tmpTextByte.length);
    byteBuffer.put((byte) 2);
    byteBuffer.putInt(tmpTextByte.length);
    byteBuffer.put(tmpTextByte);
    byteBuffer.flip();
    try {
      Server.writeMain(byteBuffer);
    } catch (IOException e) {
      Server.errorClose(e);
    }
  }

  public static ByteBuffer createVideoSizeEvent() {
    ByteBuffer byteBuffer = ByteBuffer.allocate(9);
    byteBuffer.put((byte) 3);
    byteBuffer.putInt(Device.videoSize.first);
    byteBuffer.putInt(Device.videoSize.second);
    byteBuffer.flip();
    return byteBuffer;
  }

  // main类型5：[客户端发送时间ns][服务端当前时间us]。服务端时间与视频pts同为CLOCK_MONOTONIC，
  // 客户端据此换算出每帧从采集到解码完成的延迟
  public static void sendPong(long clientSentNs) throws IOException {
    ByteBuffer byteBuffer = ByteBuffer.allocate(17);
    byteBuffer.put((byte) 5);
    byteBuffer.putLong(clientSentNs);
    byteBuffer.putLong(System.nanoTime() / 1000);
    byteBuffer.flip();
    Server.writeMain(byteBuffer);
  }

  public static void sendVideoSizeEvent() throws IOException {
    Server.writeMain(createVideoSizeEvent());
  }

  // 从当前连接的输入流读取(重连时流会被替换，不能读静态字段)
  public static void handleTouchEvent(DataInputStream in) throws IOException {
    int action = in.readByte();
    int pointerId = in.readByte();
    float x = in.readFloat();
    float y = in.readFloat();
    int offsetTime = in.readInt();
    Device.touchEvent(action, x, y, pointerId, offsetTime);
  }

  public static void handleKeyEvent(DataInputStream in) throws IOException {
    int keyCode = in.readInt();
    int meta = in.readInt();
    Device.keyEvent(keyCode, meta);
  }

  public static void handleClipboardEvent(DataInputStream in) throws IOException {
    int size = in.readInt();
    if (size < 0 || size > 1024 * 1024) throw new IOException("剪贴板长度异常: " + size);
    byte[] textBytes = new byte[size];
    in.readFully(textBytes);
    String text = new String(textBytes, StandardCharsets.UTF_8);
    Device.setClipboardText(text);
  }

}

