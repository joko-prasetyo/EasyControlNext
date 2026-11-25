package com.shiyunjin.easycontrolnext.server.helper;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Random;
import java.util.concurrent.locks.LockSupport;

import com.shiyunjin.easycontrolnext.server.Server;
import com.shiyunjin.easycontrolnext.server.entity.Options;

// Video over UDP (the receiving side is the app's UdpVideoReceiver).
//
// Why: over TCP a single lost packet on Wi-Fi holds back everything behind it until TCP retransmits
// (on Linux at least 200 ms after a timeout). Here a lost packet only delays its own frame: the app asks for it
// again within a few tens of ms (NACK), and if a frame still cannot be completed the app skips ahead to a fresh
// keyframe instead of waiting. Control, audio, acks and flow control stay on the TCP connection.
//
// Session: the app sends HELLO (with the connection token) to the server port; the server answers WELCOME with
// a session id. Only after the app received it (both directions work) does it ask, over TCP, to switch; the server
// then writes a marker as the last TCP video packet and sends every following frame over UDP, starting with a
// keyframe. If UDP never works the app simply stays on TCP.
//
// Datagram format (big-endian), at most 1200 bytes so it survives Wi-Fi, VPNs and tunnels without fragmentation:
//   VIDEO     [1][flags][packetSeq:int][frameSeq:int][index:short][count:short][chunk]   flags bit0 = retransmission
//   HELLO     [2][0][token:16][nonce:int]                     (app -> server)
//   WELCOME   [3][0][sessionId:int][nonce:int]                (server -> app)
//   NACK      [4][0][sessionId:int][n:short][packetSeq:int]*n (app -> server)
//   KEEPALIVE [5][0][sessionId:int]                           (app -> server, keeps NAT mappings open)
// A frame is the same bytes as on TCP without the size prefix: [pts:long][encoded data].
public final class UdpVideo {
  public static final byte T_VIDEO = 1;
  public static final byte T_HELLO = 2;
  public static final byte T_WELCOME = 3;
  public static final byte T_NACK = 4;
  public static final byte T_KEEPALIVE = 5;
  private static final int HEADER = 14;
  private static final int DATAGRAM = 1200;
  private static final int CHUNK = DATAGRAM - HEADER;
  // Retransmission memory: ~10 MB worth of packets, far more than a few hundred ms of video
  private static final int RING = 8192;
  // Short pause after this many packets of one frame, so a big keyframe does not hit the Wi-Fi queue in one burst
  private static final int PACE_EVERY = 24;
  private static final long PACE_NS = 300_000;

  private static DatagramSocket socket;
  private static final SecureRandom secureRandom = new SecureRandom();
  private static final Random lossRandom = new Random();

  // Session (guarded by UdpVideo.class)
  private static int sessionGeneration = -1;
  private static int sessionId = 0;
  private static InetSocketAddress client = null;
  private static volatile boolean active = false;
  private static int nextPacketSeq = 0;
  private static final byte[][] ring = new byte[RING][];
  private static final int[] ringSeq = new int[RING];

  // Statistics, logged every 5 s while active
  private static long statPackets = 0;
  private static long statRetransmits = 0;
  private static long statNacked = 0;
  private static long statWindowStart = 0;

  // Testing only: drop this percentage of outgoing video datagrams (setprop debug.easycontrolnext.udploss N)
  private static volatile int lossPercent = 0;
  private static long lossCheckedMs = 0;

  private UdpVideo() {
  }

  public static void start(int port) {
    try {
      socket = new DatagramSocket(port);
      socket.setSendBufferSize(4 * 1024 * 1024);
      socket.setReceiveBufferSize(256 * 1024);
    } catch (Exception e) {
      System.out.println("UDP不可用: " + e);
      System.out.flush();
      socket = null;
      return;
    }
    Thread thread = new Thread(UdpVideo::receiveLoop, "udp_in");
    thread.setDaemon(true);
    thread.start();
  }

  public static void close() {
    if (socket != null) socket.close();
  }

  private static void receiveLoop() {
    byte[] buffer = new byte[1500];
    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
    while (!socket.isClosed()) {
      try {
        packet.setLength(buffer.length);
        socket.receive(packet);
        ByteBuffer in = ByteBuffer.wrap(buffer, 0, packet.getLength());
        if (in.remaining() < 2) continue;
        byte type = in.get();
        in.get();
        if (type == T_HELLO && in.remaining() >= 20) onHello(in, (InetSocketAddress) packet.getSocketAddress());
        else if (type == T_NACK && in.remaining() >= 6) onNack(in);
      } catch (IOException e) {
        if (socket.isClosed()) return;
      } catch (RuntimeException ignored) {
      }
    }
  }

  private static void onHello(ByteBuffer in, InetSocketAddress from) throws IOException {
    byte[] token = new byte[16];
    in.get(token);
    int nonce = in.getInt();
    if (!Options.checkToken(token) || !Server.isAttached()) return;
    int id;
    synchronized (UdpVideo.class) {
      int generation = Server.generation();
      if (sessionGeneration != generation) {
        sessionGeneration = generation;
        sessionId = secureRandom.nextInt();
        active = false;
        nextPacketSeq = 0;
        for (int i = 0; i < RING; i++) ring[i] = null;
      }
      client = from;
      id = sessionId;
    }
    ByteBuffer welcome = ByteBuffer.allocate(10);
    welcome.put(T_WELCOME).put((byte) 0).putInt(id).putInt(nonce);
    socket.send(new DatagramPacket(welcome.array(), welcome.position(), from));
  }

  private static void onNack(ByteBuffer in) throws IOException {
    int id = in.getInt();
    int n = in.getShort() & 0xFFFF;
    InetSocketAddress to;
    synchronized (UdpVideo.class) {
      if (!active || id != sessionId || client == null) return;
      to = client;
    }
    for (int i = 0; i < n && in.remaining() >= 4; i++) {
      int seq = in.getInt();
      byte[] data;
      synchronized (UdpVideo.class) {
        int slot = seq & (RING - 1);
        data = ringSeq[slot] == seq ? ring[slot] : null;
        statNacked++;
      }
      if (data == null) continue;
      data[1] = 1;
      if (!dropForTest()) socket.send(new DatagramPacket(data, data.length, to));
      synchronized (UdpVideo.class) {
        statRetransmits++;
      }
    }
  }

  // Called by Server when the app asks (over TCP) to switch. Returns false if no matching HELLO was seen
  public static synchronized boolean activate(int generation, int id) {
    if (socket == null || generation != sessionGeneration || id != sessionId || client == null) return false;
    active = true;
    statWindowStart = System.currentTimeMillis();
    System.out.println("视频改用UDP: " + client);
    System.out.flush();
    return true;
  }

  // New or lost connection: back to TCP until the app negotiates again
  public static synchronized void reset() {
    active = false;
    sessionGeneration = -1;
    client = null;
  }

  public static boolean isActive(int generation) {
    return active && generation == sessionGeneration;
  }

  // One frame ([pts][data]) as consecutive datagrams. Called from the video writer (under the video write lock)
  public static void sendFrame(byte[] data, int offset, int length, int frameSeq) throws IOException {
    InetSocketAddress to;
    synchronized (UdpVideo.class) {
      to = client;
    }
    if (to == null) return;
    int count = Math.max(1, (length + CHUNK - 1) / CHUNK);
    for (int index = 0; index < count; index++) {
      int chunkStart = offset + index * CHUNK;
      int chunkLength = Math.min(CHUNK, offset + length - chunkStart);
      byte[] datagram = new byte[HEADER + chunkLength];
      int seq;
      synchronized (UdpVideo.class) {
        seq = nextPacketSeq++;
        ring[seq & (RING - 1)] = datagram;
        ringSeq[seq & (RING - 1)] = seq;
        statPackets++;
      }
      ByteBuffer out = ByteBuffer.wrap(datagram);
      out.put(T_VIDEO).put((byte) 0).putInt(seq).putInt(frameSeq).putShort((short) index).putShort((short) count);
      out.put(data, chunkStart, chunkLength);
      if (!dropForTest()) socket.send(new DatagramPacket(datagram, datagram.length, to));
      if ((index + 1) % PACE_EVERY == 0 && index + 1 < count) LockSupport.parkNanos(PACE_NS);
    }
    logStats();
  }

  private static boolean dropForTest() {
    long now = System.currentTimeMillis();
    if (now - lossCheckedMs > 1000) {
      lossCheckedMs = now;
      try {
        Object value = Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class)
          .invoke(null, "debug.easycontrolnext.udploss", "0");
        lossPercent = Integer.parseInt(String.valueOf(value).trim());
      } catch (Exception e) {
        lossPercent = 0;
      }
    }
    return lossPercent > 0 && lossRandom.nextInt(100) < lossPercent;
  }

  private static void logStats() {
    long now = System.currentTimeMillis();
    String line = null;
    synchronized (UdpVideo.class) {
      if (now - statWindowStart >= 5000) {
        line = "UDP: 发送 " + statPackets + " 包, 被请求重发 " + statNacked + ", 已重发 " + statRetransmits
          + (lossPercent > 0 ? ", 模拟丢包 " + lossPercent + "%" : "");
        statPackets = 0;
        statNacked = 0;
        statRetransmits = 0;
        statWindowStart = now;
      }
    }
    if (line != null) {
      System.out.println(line);
      System.out.flush();
    }
  }
}
