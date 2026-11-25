package com.shiyunjin.easycontrolnext.app.client.tools;

import android.os.Process;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.locks.LockSupport;

// Adaptive jitter buffer for video frames, placed before the decoder.
//
// On a jittery link (busy 2.4 GHz Wi-Fi) frames arrive in clumps: nothing for 100-400 ms, then several at once.
// Shown as they arrive, motion stutters. Here every frame is released at
//   capture time (pts) + fastest observed network path (base) + D
// so frames come out with their original spacing. D follows the recent jitter (95th percentile over 4 s):
// it rises at once when the network gets worse and shrinks slowly when it calms down, and never exceeds the
// user's maximum. On a clean network D stays near zero, so the latency cost is only paid while it buys smoothness.
// A frame that arrives later than its slot is passed on immediately (a stall longer than D still shows).
//
// The two clocks (server pts, local nanoTime) never need to be synchronised: only differences of
// (arrival - pts) are used, and the sliding minimum also absorbs slow clock drift.
public final class JitterBuffer {
  public interface Sink {
    void deliver(ByteBuffer frame) throws InterruptedException;
  }

  private static final long BASE_WINDOW_US = 10_000_000;
  private static final long JITTER_WINDOW_US = 4_000_000;
  private static final long PERCENTILE_EVERY_US = 250_000;
  // Shrink D by this much per frame when the network calms down (~30 ms/s at 60 fps)
  private static final long DECAY_PER_FRAME_US = 500;
  // Hard cap on queued frames (memory). The real limit is QUEUE_SLACK_US below
  private static final int QUEUE_CAPACITY = 240;
  // Never hold more than maxDelay + this much video. If the decoder is slower than the stream, frames would
  // otherwise pile up here (seconds of latency) while the server sees every frame acknowledged on arrival.
  // Blocking the network thread instead slows the acks down to the decoder's pace, so the server's flow
  // control sees a sustained backlog and lowers the load
  private static final long QUEUE_SLACK_US = 250_000;

  private final long maxDelayUs;
  private final Sink sink;
  private final LinkedBlockingDeque<Entry> queue = new LinkedBlockingDeque<>(QUEUE_CAPACITY);
  private final Thread thread;
  private volatile boolean stopped = false;
  private volatile Throwable failure = null;

  // Written by the network thread only
  private final ArrayDeque<long[]> baseWindow = new ArrayDeque<>();
  private final long[] jitterTime = new long[1024];
  private final long[] jitterValue = new long[1024];
  private int jitterNext = 0;
  private int jitterCount = 0;
  private long lastPercentileUs = 0;
  private long percentile95Us = 0;
  private volatile long baseUs = Long.MIN_VALUE;
  private volatile long delayUs = 0;

  private static final class Entry {
    final ByteBuffer frame;
    final long ptsUs;

    Entry(ByteBuffer frame, long ptsUs) {
      this.frame = frame;
      this.ptsUs = ptsUs;
    }
  }

  public JitterBuffer(int maxDelayMs, Sink sink) {
    this.maxDelayUs = maxDelayMs * 1000L;
    this.sink = sink;
    thread = new Thread(this::run, "easycontrol_jitter");
    thread.start();
  }

  // Network thread. The frame starts with its 8-byte pts (see VideoDecode.decodeIn)
  public void push(ByteBuffer frame) throws Exception {
    Throwable error = failure;
    if (error != null) throw error instanceof Exception ? (Exception) error : new RuntimeException(error);
    long nowUs = System.nanoTime() / 1000;
    long ptsUs = frame.getLong(frame.position());
    long offset = nowUs - ptsUs;
    // Sliding minimum of (arrival - pts): the fastest the network delivered recently
    while (!baseWindow.isEmpty() && baseWindow.peekLast()[1] >= offset) baseWindow.pollLast();
    baseWindow.addLast(new long[]{nowUs, offset});
    while (baseWindow.peekFirst()[0] < nowUs - BASE_WINDOW_US) baseWindow.pollFirst();
    long base = baseWindow.peekFirst()[1];
    baseUs = base;
    // How late this frame was compared with the fastest path
    jitterTime[jitterNext] = nowUs;
    jitterValue[jitterNext] = offset - base;
    jitterNext = (jitterNext + 1) % jitterTime.length;
    if (jitterCount < jitterTime.length) jitterCount++;
    if (nowUs - lastPercentileUs >= PERCENTILE_EVERY_US) {
      lastPercentileUs = nowUs;
      percentile95Us = percentile95(nowUs);
    }
    long target = Math.min(maxDelayUs, percentile95Us);
    long current = delayUs;
    delayUs = target > current ? target : Math.max(target, current - DECAY_PER_FRAME_US);
    queue.putLast(new Entry(frame, ptsUs));
    while (!stopped && queuedDurationUs() > maxDelayUs + QUEUE_SLACK_US) LockSupport.parkNanos(2_000_000);
  }

  private long queuedDurationUs() {
    Entry first = queue.peekFirst();
    Entry last = queue.peekLast();
    return first == null || last == null ? 0 : last.ptsUs - first.ptsUs;
  }

  private long percentile95(long nowUs) {
    long[] recent = new long[jitterCount];
    int n = 0;
    for (int i = 0; i < jitterCount; i++) if (jitterTime[i] >= nowUs - JITTER_WINDOW_US) recent[n++] = jitterValue[i];
    if (n == 0) return 0;
    Arrays.sort(recent, 0, n);
    return recent[Math.min(n - 1, n * 95 / 100)];
  }

  private void run() {
    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
    try {
      while (!stopped) {
        Entry entry = queue.takeFirst();
        long dueUs = entry.ptsUs + baseUs + delayUs;
        long waitUs = Math.min(dueUs - System.nanoTime() / 1000, maxDelayUs + 50_000);
        if (waitUs > 0) {
          long deadline = System.nanoTime() + waitUs * 1000;
          long left;
          while (!stopped && (left = deadline - System.nanoTime()) > 0) LockSupport.parkNanos(left);
        }
        if (stopped) return;
        sink.deliver(entry.frame);
      }
    } catch (InterruptedException ignored) {
    } catch (Throwable e) {
      failure = e;
    }
  }

  public int delayMs() {
    return (int) (delayUs / 1000);
  }

  public void stop() {
    stopped = true;
    thread.interrupt();
    try {
      thread.join(1000);
    } catch (InterruptedException ignored) {
    }
  }
}
