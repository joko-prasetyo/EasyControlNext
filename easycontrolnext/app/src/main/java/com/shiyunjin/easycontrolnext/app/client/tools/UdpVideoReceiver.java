package com.shiyunjin.easycontrolnext.app.client.tools;

import android.os.Process;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.LinkedBlockingQueue;

// Receives video over UDP from the server's UdpVideo (see the format description there).
//
// Loss handling:
//  - a gap in packet numbers is requested again (NACK) right away and repeated every NACK_INTERVAL_MS;
//  - a frame that is still incomplete FRAME_DEADLINE_MS after its first packet (or that never showed up while
//    later frames did) is given up: the app asks for a keyframe and skips frames until one arrives, so the
//    decoder never gets a frame whose references are missing. Waiting longer would only add latency.
// The limits below are for a LAN. On a long link (VPN, mobile data) a re-sent packet needs a full round trip,
// so they are stretched by the measured RTT: with a 210 ms round trip the old 150 ms deadline gave up every
// frame that lost a packet, and the keyframe it then asked for took longer than the re-sent packet would have.
// Frames are handed out strictly in order. Skipped frames are still reported (data == null) so the
// acknowledgements to the server keep moving and its flow control does not mistake a loss for a backlog.
public final class UdpVideoReceiver {
  public static final class Frame {
    public final int seq;
    // [pts:long][encoded data], or null for a frame that was skipped
    public final ByteBuffer data;

    Frame(int seq, ByteBuffer data) {
      this.seq = seq;
      this.data = data;
    }
  }

  public interface KeyframeRequest {
    void request();
  }

  private static final byte T_VIDEO = 1;
  private static final byte T_HELLO = 2;
  private static final byte T_WELCOME = 3;
  private static final byte T_NACK = 4;
  private static final byte T_KEEPALIVE = 5;
  private static final int HEADER = 14;
  private static final long NACK_FIRST_MS = 3;
  private static final long NACK_INTERVAL_MS = 25;
  private static final int NACK_MAX_TRIES = 12;
  private static final long MISSING_FORGET_MS = 800;
  private static final long FRAME_DEADLINE_MS = 150;
  // Giving up a keyframe means waiting for (and re-sending) another big one; worth waiting longer for it
  private static final long KEYFRAME_DEADLINE_MS = 600;
  private static final long ABSENT_FRAME_DEADLINE_MS = 100;
  private static final long KEEPALIVE_MS = 1000;
  private static final long KEYFRAME_REQUEST_INTERVAL_MS = 300;

  // Round trip to the server (ms), from the control channel's ping
  private volatile int rttMs = 0;

  private final DatagramSocket socket;
  private final InetSocketAddress server;
  private final byte[] token;
  private int sessionId = 0;
  private volatile boolean closed = false;
  private boolean h265 = false;
  private KeyframeRequest keyframeRequest;
  private final LinkedBlockingQueue<Frame> frames = new LinkedBlockingQueue<>();
  private Thread thread;

  // Receiver thread state
  private final TreeMap<Integer, Assembly> assemblies = new TreeMap<>();
  private final LinkedHashMap<Integer, long[]> missing = new LinkedHashMap<>();
  private int expectedPacket = 0;
  private int nextFrame;
  private boolean skipping = false;
  private long lastKeepaliveMs = 0;
  // Diagnostics, logged every 5 s
  private long statPackets = 0;
  private long statNacks = 0;
  private long statFrames = 0;
  private long statSkipped = 0;
  private long statKeyRequests = 0;
  private long statSinceMs = 0;
  private long lastKeyframeRequestMs = 0;

  private static final class Assembly {
    final byte[][] chunks;
    int received = 0;
    final long firstSeenMs;
    boolean key = false;

    Assembly(int count, long now) {
      chunks = new byte[count][];
      firstSeenMs = now;
    }

    boolean complete() {
      return received == chunks.length;
    }

    ByteBuffer join() {
      int length = 0;
      for (byte[] c : chunks) length += c.length;
      ByteBuffer out = ByteBuffer.allocate(length);
      for (byte[] c : chunks) out.put(c);
      out.flip();
      return out;
    }
  }

  public void setRttMs(int ms) {
    rttMs = Math.max(0, Math.min(ms, 2000));
  }

  // Each limit is at least its LAN value; on a long link it leaves time for one re-send (two for a keyframe)
  private long nackIntervalMs() {
    return Math.max(NACK_INTERVAL_MS, rttMs * 5L / 4 + 10);
  }

  private long missingForgetMs() {
    return Math.max(MISSING_FORGET_MS, 3L * rttMs + 200);
  }

  private long frameDeadlineMs(boolean key) {
    return key ? Math.max(KEYFRAME_DEADLINE_MS, 2L * rttMs + 200) : Math.max(FRAME_DEADLINE_MS, rttMs + 100L);
  }

  private long absentFrameDeadlineMs() {
    return Math.max(ABSENT_FRAME_DEADLINE_MS, rttMs + 80L);
  }

  public UdpVideoReceiver(InetAddress serverIp, int port, byte[] token) throws IOException {
    this.server = new InetSocketAddress(serverIp, port);
    this.token = token;
    socket = new DatagramSocket();
    socket.setReceiveBufferSize(4 * 1024 * 1024);
    socket.connect(server);
  }

  // Sends HELLO until a WELCOME arrives. Returns the session id; throws if UDP does not get through
  public int handshake(long timeoutMs) throws IOException {
    int nonce = new SecureRandom().nextInt();
    ByteBuffer hello = ByteBuffer.allocate(22);
    hello.put(T_HELLO).put((byte) 0).put(token).putInt(nonce);
    byte[] buffer = new byte[64];
    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
    long deadline = System.currentTimeMillis() + timeoutMs;
    socket.setSoTimeout(100);
    while (!closed && System.currentTimeMillis() < deadline) {
      socket.send(new DatagramPacket(hello.array(), hello.capacity()));
      try {
        packet.setLength(buffer.length);
        socket.receive(packet);
        ByteBuffer in = ByteBuffer.wrap(buffer, 0, packet.getLength());
        if (in.remaining() >= 10 && in.get() == T_WELCOME) {
          in.get();
          int id = in.getInt();
          if (in.getInt() == nonce) {
            sessionId = id;
            return id;
          }
        }
      } catch (SocketTimeoutException ignored) {
      } catch (IOException ignored) {
        // ICMP port unreachable on a connected socket shows up here: keep trying until the deadline
      }
    }
    throw new IOException("UDP not reachable");
  }

  // Video switched to UDP; firstFrame is the number of the first frame that will arrive here
  public void start(int firstFrame, boolean h265, KeyframeRequest keyframeRequest) {
    this.nextFrame = firstFrame;
    this.h265 = h265;
    this.keyframeRequest = keyframeRequest;
    thread = new Thread(this::receiveLoop, "easycontrol_udp");
    thread.start();
  }

  public Frame take() throws InterruptedException, IOException {
    while (true) {
      if (closed) throw new IOException("udp closed");
      Frame frame = frames.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
      if (frame != null) return frame;
    }
  }

  public void close() {
    closed = true;
    socket.close();
    if (thread != null) thread.interrupt();
  }

  private void receiveLoop() {
    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
    byte[] buffer = new byte[2048];
    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
    try {
      socket.setSoTimeout(10);
    } catch (IOException ignored) {
    }
    while (!closed) {
      try {
        packet.setLength(buffer.length);
        socket.receive(packet);
        statPackets++;
        onPacket(buffer, packet.getLength());
      } catch (SocketTimeoutException ignored) {
      } catch (IOException e) {
        if (closed) return;
      }
      long now = System.currentTimeMillis();
      sendNacks(now);
      deliver(now);
      if (statSinceMs == 0) statSinceMs = now;
      if (now - statSinceMs >= 5000) {
        android.util.Log.i("EasyControlUdp", "packets=" + statPackets + " nacked=" + statNacks + " frames=" + statFrames + " skipped=" + statSkipped
          + " keyframeRequests=" + statKeyRequests + " missing=" + missing.size() + " pendingFrames=" + assemblies.size() + " nextFrame=" + nextFrame);
        statPackets = statNacks = statFrames = statSkipped = statKeyRequests = 0;
        statSinceMs = now;
      }
      if (now - lastKeepaliveMs >= KEEPALIVE_MS) {
        lastKeepaliveMs = now;
        sendControl(ByteBuffer.allocate(6).put(T_KEEPALIVE).put((byte) 0).putInt(sessionId));
      }
    }
  }

  private void onPacket(byte[] buffer, int length) {
    if (length < HEADER || buffer[0] != T_VIDEO) return;
    ByteBuffer in = ByteBuffer.wrap(buffer, 0, length);
    in.position(2);
    int packetSeq = in.getInt();
    int frameSeq = in.getInt();
    int index = in.getShort() & 0xFFFF;
    int count = in.getShort() & 0xFFFF;
    long now = System.currentTimeMillis();
    if (packetSeq >= expectedPacket) {
      for (int s = expectedPacket; s < packetSeq; s++) missing.put(s, new long[]{now, 0, 0});
      expectedPacket = packetSeq + 1;
    } else missing.remove(packetSeq);
    if (frameSeq < nextFrame || count == 0 || index >= count) return;
    Assembly assembly = assemblies.get(frameSeq);
    if (assembly == null) {
      assembly = new Assembly(count, now);
      assemblies.put(frameSeq, assembly);
    }
    if (assembly.chunks.length != count || assembly.chunks[index] != null) return;
    byte[] chunk = new byte[length - HEADER];
    System.arraycopy(buffer, HEADER, chunk, 0, chunk.length);
    assembly.chunks[index] = chunk;
    assembly.received++;
    if (index == 0) assembly.key = isKeyframe(ByteBuffer.wrap(chunk));
  }

  private void sendNacks(long now) {
    if (missing.isEmpty()) return;
    long forgetMs = missingForgetMs();
    long intervalMs = nackIntervalMs();
    List<Integer> request = new ArrayList<>();
    Iterator<Map.Entry<Integer, long[]>> it = missing.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<Integer, long[]> entry = it.next();
      long[] state = entry.getValue();
      if (now - state[0] > forgetMs || state[2] >= NACK_MAX_TRIES) {
        it.remove();
        continue;
      }
      boolean due = state[2] == 0 ? now - state[0] >= NACK_FIRST_MS : now - state[1] >= intervalMs;
      if (due && request.size() < 200) {
        request.add(entry.getKey());
        state[1] = now;
        state[2]++;
      }
    }
    if (request.isEmpty()) return;
    ByteBuffer nack = ByteBuffer.allocate(8 + 4 * request.size());
    nack.put(T_NACK).put((byte) 0).putInt(sessionId).putShort((short) request.size());
    for (int seq : request) nack.putInt(seq);
    statNacks += request.size();
    sendControl(nack);
  }

  private void deliver(long now) {
    while (true) {
      Assembly assembly = assemblies.get(nextFrame);
      if (assembly != null && assembly.complete()) {
        assemblies.remove(nextFrame);
        ByteBuffer data = assembly.join();
        if (skipping && !isKeyframe(data)) {
          frames.offer(new Frame(nextFrame, null));
          statSkipped++;
        } else {
          skipping = false;
          frames.offer(new Frame(nextFrame, data));
          statFrames++;
        }
        nextFrame++;
        continue;
      }
      boolean lost;
      if (assembly != null) lost = now - assembly.firstSeenMs > frameDeadlineMs(assembly.key);
      else {
        Map.Entry<Integer, Assembly> later = assemblies.higherEntry(nextFrame);
        lost = later != null && now - later.getValue().firstSeenMs > absentFrameDeadlineMs();
      }
      if (!lost) return;
      assemblies.remove(nextFrame);
      frames.offer(new Frame(nextFrame, null));
      nextFrame++;
      skipping = true;
      statSkipped++;
      // The keyframe takes at least a round trip to arrive; asking again sooner only adds another big frame
      if (now - lastKeyframeRequestMs > Math.max(KEYFRAME_REQUEST_INTERVAL_MS, 2L * rttMs)) {
        lastKeyframeRequestMs = now;
        statKeyRequests++;
        if (keyframeRequest != null) keyframeRequest.request();
      }
    }
  }

  // A frame the decoder can start from: carries parameter sets or an IDR/IRAP picture
  private boolean isKeyframe(ByteBuffer frame) {
    int end = Math.min(frame.limit(), 8 + 64 * 1024);
    for (int i = 8; i + 3 < end; i++) {
      if (frame.get(i) != 0 || frame.get(i + 1) != 0 || frame.get(i + 2) != 1) continue;
      int header = frame.get(i + 3) & 0xFF;
      if (h265) {
        int type = (header >> 1) & 0x3F;
        if ((type >= 16 && type <= 21) || (type >= 32 && type <= 34)) return true;
        if (type <= 31) return false;
      } else {
        int type = header & 0x1F;
        if (type == 5 || type == 7) return true;
        if (type >= 1 && type <= 4) return false;
      }
      i += 3;
    }
    return false;
  }

  private void sendControl(ByteBuffer message) {
    try {
      socket.send(new DatagramPacket(message.array(), message.position()));
    } catch (IOException ignored) {
    }
  }
}
