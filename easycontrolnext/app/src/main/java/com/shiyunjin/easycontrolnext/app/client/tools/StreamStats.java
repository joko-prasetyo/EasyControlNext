package com.shiyunjin.easycontrolnext.app.client.tools;

import android.util.Log;

import java.util.Arrays;

// 串流统计：帧率、码率、延迟、往返时间(RTT)。
//
// 延迟 = 被控端合成该帧的时刻 -> 本机解码完成的时刻(不含最后上屏的约1~2帧)。
// 两台设备的时钟不同，因此每秒发送一次ping，服务端回复自己的时钟(与视频pts同为CLOCK_MONOTONIC)，
// 用NTP方式估计两端时钟差；取最近若干次中RTT最小的一次，因为往返越快，“来回各一半”的假设误差越小。
public final class StreamStats {
  private static final int CLOCK_SAMPLES = 30;

  private long windowStartNs = System.nanoTime();
  private int renderedFrames = 0;
  private int receivedFrames = 0;
  private long receivedBytes = 0;
  private final long[] latencyUs = new long[1024];
  private int latencyCount = 0;

  private final long[] sampleRttUs = new long[CLOCK_SAMPLES];
  private final long[] sampleOffsetUs = new long[CLOCK_SAMPLES];
  private int sampleCount = 0;
  private int sampleNext = 0;
  private boolean hasOffset = false;
  // 本机时钟 - 服务端时钟(微秒)
  private long clockOffsetUs = 0;
  private long lastRttUs = -1;

  private volatile boolean reconnecting = false;
  // Current smoothness-buffer delay (ms), for the log line
  private volatile int bufferMs = 0;
  private volatile String transport = "tcp";

  public void setTransport(String transport) {
    this.transport = transport;
  }

  public void setBufferMs(int ms) {
    bufferMs = ms;
  }

  // Stutter metrics: longest gap between frames arriving from the network and between decoded frames
  private long lastReceiveNs = 0;
  private long lastRenderNs = 0;
  private long maxReceiveGapNs = 0;
  private long maxRenderGapNs = 0;
  private int renderGaps50 = 0;
  private int renderGaps100 = 0;

  public synchronized void onVideoFrame(int bytes) {
    receivedFrames++;
    receivedBytes += bytes;
    long now = System.nanoTime();
    if (lastReceiveNs != 0) maxReceiveGapNs = Math.max(maxReceiveGapNs, now - lastReceiveNs);
    lastReceiveNs = now;
  }

  // 在解码器输出该帧(即将渲染)时调用
  public synchronized void onFrameRendered(long ptsUs) {
    renderedFrames++;
    long now = System.nanoTime();
    if (lastRenderNs != 0) {
      long gap = now - lastRenderNs;
      maxRenderGapNs = Math.max(maxRenderGapNs, gap);
      if (gap > 50_000_000) renderGaps50++;
      if (gap > 100_000_000) renderGaps100++;
    }
    lastRenderNs = now;
    if (!hasOffset) return;
    long latency = System.nanoTime() / 1000 - (ptsUs + clockOffsetUs);
    // 超出合理范围说明pts不是同一时钟域(个别编码器)，不计入
    if (latency < 0 || latency > 10_000_000) return;
    if (latencyCount < latencyUs.length) latencyUs[latencyCount++] = latency;
  }

  public synchronized void onPong(long sentNs, long serverUs) {
    long nowNs = System.nanoTime();
    long rttUs = (nowNs - sentNs) / 1000;
    if (rttUs < 0 || rttUs > 10_000_000) return;
    lastRttUs = rttUs;
    sampleRttUs[sampleNext] = rttUs;
    sampleOffsetUs[sampleNext] = (sentNs / 2 + nowNs / 2) / 1000 - serverUs;
    sampleNext = (sampleNext + 1) % CLOCK_SAMPLES;
    if (sampleCount < CLOCK_SAMPLES) sampleCount++;
    int best = 0;
    for (int i = 1; i < sampleCount; i++) if (sampleRttUs[i] < sampleRttUs[best]) best = i;
    clockOffsetUs = sampleOffsetUs[best];
    hasOffset = true;
  }

  public void setReconnecting(boolean reconnecting) {
    this.reconnecting = reconnecting;
  }

  // 返回上一统计窗口的文字摘要并开始新窗口
  public synchronized String snapshot() {
    long nowNs = System.nanoTime();
    double seconds = Math.max(0.001, (nowNs - windowStartNs) / 1e9);
    String text;
    // 精简显示，尽量少遮挡画面：帧率 + 延迟中位数
    if (reconnecting) {
      text = "reconnecting";
    } else {
      String latency = "– ms";
      if (latencyCount > 0) {
        long[] sorted = Arrays.copyOf(latencyUs, latencyCount);
        Arrays.sort(sorted);
        latency = (sorted[latencyCount / 2] / 1000) + " ms";
      }
      text = Math.round(renderedFrames / seconds) + " fps · " + latency;
    }
    // One line per second in logcat (tag EasyControlStats) so a session can be analysed afterwards
    long p50 = -1, p95 = -1, max = -1;
    if (latencyCount > 0) {
      long[] sorted = Arrays.copyOf(latencyUs, latencyCount);
      Arrays.sort(sorted);
      p50 = sorted[latencyCount / 2] / 1000;
      p95 = sorted[Math.min(latencyCount - 1, latencyCount * 95 / 100)] / 1000;
      max = sorted[latencyCount - 1] / 1000;
    }
    Log.i("EasyControlStats", "fps=" + Math.round(renderedFrames / seconds) + " recv=" + Math.round(receivedFrames / seconds)
      + " kbps=" + Math.round(receivedBytes * 8 / seconds / 1000) + " lat_p50=" + p50 + " lat_p95=" + p95 + " lat_max=" + max
      + " rtt=" + (lastRttUs / 1000) + " recv_gap_max=" + maxReceiveGapNs / 1_000_000 + " render_gap_max=" + maxRenderGapNs / 1_000_000
      + " gaps50=" + renderGaps50 + " gaps100=" + renderGaps100 + " buf=" + bufferMs + " via=" + transport + (reconnecting ? " reconnecting" : ""));
    maxReceiveGapNs = 0;
    maxRenderGapNs = 0;
    renderGaps50 = 0;
    renderGaps100 = 0;
    windowStartNs = nowNs;
    renderedFrames = 0;
    receivedFrames = 0;
    receivedBytes = 0;
    latencyCount = 0;
    return text;
  }
}
