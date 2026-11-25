package com.shiyunjin.easycontrolnext.server.helper;

import com.shiyunjin.easycontrolnext.server.entity.Options;

// 视频流控：防止延迟在网络缓冲区里越积越多，同时不因Wi-Fi的短暂卡顿而折腾画面。
//
// 客户端每把一帧送进解码器就回报累计帧数，服务端据此得知“最早一个未确认的帧已发出多久”(lag)。
// lag变大有两种完全不同的原因，处理方式也不同：
//
// 1. Wi-Fi短暂卡顿(省电唤醒、干扰重传)：几百毫秒到一秒多什么都送不到，之后积压的数据一下子全部送达。
//    这时什么都不用做：丢帧并不能让已经在路上的数据更快到达，反而要重建编码器、发一个大关键帧，
//    大关键帧又容易引发下一次卡顿(实测会形成“卡顿-丢帧-关键帧-卡顿”的循环，并把画质一路降到最低)。
// 2. 持续积压(网络吞吐低于视频码率)：数据一直在送达，只是比发送得慢，lag持续上升且不会自行回落。
//    这时降低数据量：测量这段时间实际送达的速度，按其80%调整码率/档位(VideoEncode.LEVELS)。
//
// 区分方法：lag持续高于BACKLOG_LAG_MS达BACKLOG_HOLD_MS，且这段时间内确认一直在推进 -> 持续积压；
// 确认停滞 -> 卡顿，只等待。只有lag超过HARD_LAG_MS(长时间断流)才丢帧并从关键帧恢复，作为延迟的最后上限。
//
// 档位：有的编码器不理会码率(redroid的OMX编码器是固定画质)。持续统计实际输出码率，
// 明显超过目标时降档，网络持续正常且有余量后再逐级恢复。
public final class FlowControl {
  public static final int SEND = 0;
  public static final int DROP = 1;
  // 网络已排空：从关键帧恢复发送
  public static final int RESUME = 2;

  private static final long BACKLOG_LAG_MS = 400;
  private static final long BACKLOG_HOLD_MS = 700;
  // 判断“确认一直在推进”：最近一次确认推进距今不超过该值
  private static final long ACK_PROGRESS_MS = 300;
  private static final long CLEAR_LAG_MS = 200;
  // 实测手机Wi-Fi卡顿最长约1.3秒，留出余量
  private static final long HARD_LAG_MS = 2000;

  private static final int RING = 1024;
  private static final long[] sentAt = new long[RING];
  private static final int[] sentBytes = new int[RING];
  private static int generation = -1;
  private static int sent = 0;
  private static int acked = 0;
  private static long ackedBytes = 0;
  private static long sentBytesTotal = 0;
  private static long lastAckProgressMs = 0;
  // 收到过客户端确认才启用流控(兼容不发确认的客户端，例如测试工具)
  private static boolean ackSeen = false;
  private static boolean paused = false;
  private static long pausedSinceMs = 0;

  // 持续积压的观察窗口
  private static long backlogSinceMs = 0;
  private static long backlogAckedBytes0 = 0;
  private static long backlogSentBytes0 = 0;
  private static int backlogAckEvents = 0;

  // 码率自适应
  private static final long RAISE_AFTER_MS = 5000;
  private static final long RAISE_INTERVAL_MS = 1000;
  // 测得的吞吐在这么久没有积压后作废
  private static final long CAPACITY_TTL_MS = 30_000;
  private static volatile int targetBitrate = 0;
  private static int capacityBps = 0;
  private static long lastBacklogMs = 0;
  private static long lastRaiseMs = 0;

  // 实际输出码率统计与档位
  private static final long OUTPUT_WINDOW_MS = 3000;
  // 恢复一档前至少稳定这么久。刚恢复一档(15秒内)又积压，说明网络带不动那一档：下次等待时间加倍，最多40秒，
  // 减少反复试探造成的延迟尖峰；60秒没有积压后回到10秒。
  // (试过3秒：网络恢复后画质回来得快很多，但信号一直差时频繁试探，延迟超过300 ms的帧翻倍。延迟优先)
  private static final long LEVEL_UP_DWELL_MIN_MS = 10_000;
  private static final long LEVEL_UP_DWELL_MAX_MS = 40_000;
  private static final long LEVEL_UP_FAILED_MS = 15_000;
  private static final long LEVEL_UP_DWELL_RESET_MS = 60_000;
  // 一次积压最多降这么多档：信号快断时测得的送达速度很低(例如459 kbps)，并不代表网络恢复后的能力
  private static final int MAX_LEVELS_DOWN = 3;
  private static long levelUpDwellMs = LEVEL_UP_DWELL_MIN_MS;
  private static long lastLevelUpMs = 0;
  // 各档位相对于0档的大致数据量；编码器决定可用的档位(见VideoEncode.LEVELS)
  private static int[] LEVEL_PERCENT = {100, 68, 48, 25};
  private static long windowStartMs = 0;
  private static long windowBytes = 0;
  private static boolean windowDisturbed = false;
  private static int outputBps = 0;
  private static volatile int level = 0;
  private static long lastLevelChangeMs = 0;

  private FlowControl() {
  }

  public static synchronized void setLevelPercent(int[] percent) {
    LEVEL_PERCENT = percent;
  }

  // 新连接(持有视频写锁时调用)
  public static synchronized void reset(int newGeneration) {
    generation = newGeneration;
    sent = 0;
    acked = 0;
    ackedBytes = 0;
    sentBytesTotal = 0;
    lastAckProgressMs = 0;
    ackSeen = false;
    paused = false;
    backlogSinceMs = 0;
    targetBitrate = Options.maxVideoBit;
    capacityBps = 0;
    lastBacklogMs = 0;
    lastRaiseMs = System.currentTimeMillis();
    level = 0;
    lastLevelChangeMs = 0;
    levelUpDwellMs = LEVEL_UP_DWELL_MIN_MS;
    lastLevelUpMs = 0;
    windowStartMs = 0;
    windowBytes = 0;
    windowDisturbed = false;
    outputBps = 0;
  }

  // 一个视频包已写入socket(持有视频写锁时调用)
  public static synchronized void onSent(int bytes) {
    sentAt[sent & (RING - 1)] = System.currentTimeMillis();
    sentBytes[sent & (RING - 1)] = bytes;
    sent++;
    windowBytes += bytes;
    sentBytesTotal += bytes;
  }

  public static synchronized void onAck(int count, int gen) {
    if (gen != generation) return;
    ackSeen = true;
    if (count > sent) count = sent;
    if (count <= acked) return;
    for (int i = acked; i < count; i++) ackedBytes += sentBytes[i & (RING - 1)];
    acked = count;
    lastAckProgressMs = System.currentTimeMillis();
    if (backlogSinceMs != 0) backlogAckEvents++;
  }

  // 每个待发送的视频帧调用一次
  public static synchronized int check() {
    long now = System.currentTimeMillis();
    if (capacityBps > 0 && now - lastBacklogMs > CAPACITY_TTL_MS) capacityBps = 0;
    measureOutput(now);
    if (!ackSeen) return SEND;
    if (paused) {
      if (acked < sent) return DROP;
      paused = false;
      log("网络已恢复：断流 " + (now - pausedSinceMs) + " ms 后从关键帧继续");
      return RESUME;
    }
    long lag = acked < sent ? now - sentAt[acked & (RING - 1)] : 0;
    if (lag > HARD_LAG_MS || sent - acked >= RING) {
      // 长时间断流：不再往网络里塞数据，恢复后直接跳到最新画面
      paused = true;
      pausedSinceMs = now;
      backlogSinceMs = 0;
      windowDisturbed = true;
      log("长时间断流(最早未确认帧已发出 " + lag + " ms，在途 " + (sent - acked) + " 帧)：暂停发送视频");
      return DROP;
    }
    if (lag > BACKLOG_LAG_MS) {
      windowDisturbed = true;
      if (backlogSinceMs == 0) startBacklogWindow(now);
      else if (now - backlogSinceMs >= BACKLOG_HOLD_MS) {
        boolean delivering = backlogAckEvents >= 3 && now - lastAckProgressMs <= ACK_PROGRESS_MS;
        if (delivering) onSustainedBacklog(now, lag);
        // 卡顿(确认停滞)时只等待；无论哪种情况都开始新的观察窗口
        startBacklogWindow(now);
      }
    } else if (lag < CLEAR_LAG_MS) backlogSinceMs = 0;
    maybeRaise(now);
    return SEND;
  }

  private static void startBacklogWindow(long now) {
    backlogSinceMs = now;
    backlogAckedBytes0 = ackedBytes;
    backlogSentBytes0 = sentBytesTotal;
    backlogAckEvents = 0;
  }

  // 数据一直在送达但跟不上：用这段时间里“发出”与“送达”的实际速度决定降到哪一档。
  // 不能用平时的输出统计：画面突然变复杂(例如快速滚动)时，旧统计来自安静的画面，偏低很多
  private static void onSustainedBacklog(long now, long lag) {
    long duration = Math.max(1, now - backlogSinceMs);
    int delivered = (int) Math.min(Integer.MAX_VALUE, (ackedBytes - backlogAckedBytes0) * 8_000 / duration);
    int sending = (int) Math.min(Integer.MAX_VALUE, (sentBytesTotal - backlogSentBytes0) * 8_000 / duration);
    if (delivered <= 0) return;
    capacityBps = delivered;
    lastBacklogMs = now;
    lastRaiseMs = now;
    int floor = Math.min(Options.maxVideoBit, Math.max(Options.maxVideoBit / 8, 300_000));
    int before = targetBitrate;
    targetBitrate = Math.max(floor, Math.min(targetBitrate, delivered * 4 / 5));
    int levelBefore = level;
    // 刚恢复一档就积压：那一档网络带不动，下次多等一会再试
    if (lastLevelUpMs != 0 && now - lastLevelUpMs < LEVEL_UP_FAILED_MS) {
      levelUpDwellMs = Math.min(LEVEL_UP_DWELL_MAX_MS, levelUpDwellMs * 2);
      lastLevelUpMs = 0;
    }
    int max = Math.min(LEVEL_PERCENT.length - 1, level + MAX_LEVELS_DOWN);
    int newLevel = level;
    // 预计发送速度降到送达速度的80%以内的最低档位
    while (newLevel < max && (long) sending * LEVEL_PERCENT[newLevel] / LEVEL_PERCENT[level] > delivered * 4L / 5) newLevel++;
    // 发出仍多于送达：至少降一档。发出已少于送达说明积压正在排空(刚降的档已经生效)，只需等待；
    // 以前这里也会再降一档，一次积压连降三档(0->3->4->5)
    if (newLevel == level && level < max && sending > delivered) newLevel = level + 1;
    if (newLevel != level) setLevel(newLevel, now);
    log("持续积压(延迟 " + lag + " ms)：发出 " + sending / 1000 + " kbps，送达 " + delivered / 1000 + " kbps，码率 " + before / 1000
      + " -> " + targetBitrate / 1000 + " kbps，档位 " + levelBefore + " -> " + level);
  }

  private static void maybeRaise(long now) {
    if (now - lastBacklogMs < RAISE_AFTER_MS || now - lastRaiseMs < RAISE_INTERVAL_MS) return;
    if (targetBitrate < Options.maxVideoBit) {
      // 接近上次测得的吞吐时慢慢试探(每秒+3%)，否则较快恢复(每秒+10%)
      boolean nearCapacity = capacityBps > 0 && targetBitrate >= capacityBps * 9L / 10 && targetBitrate < capacityBps * 5L / 4;
      int step = nearCapacity ? Math.max(targetBitrate * 3 / 100, 20_000) : Math.max(targetBitrate / 10, 100_000);
      targetBitrate = Math.min(Options.maxVideoBit, targetBitrate + step);
    }
    lastRaiseMs = now;
  }

  // 每个统计窗口结束时计算实际输出码率，并据此调整档位
  private static void measureOutput(long now) {
    if (windowStartMs == 0) {
      windowStartMs = now;
      windowBytes = 0;
      windowDisturbed = false;
      return;
    }
    long elapsed = now - windowStartMs;
    if (elapsed < OUTPUT_WINDOW_MS) return;
    // 窗口内有过卡顿/积压/断流时数值不可靠，不用于判断
    boolean clean = !paused && !windowDisturbed;
    int bps = (int) Math.min(Integer.MAX_VALUE, windowBytes * 8_000 / elapsed);
    windowStartMs = now;
    windowBytes = 0;
    windowDisturbed = false;
    if (!clean) return;
    outputBps = bps;
    adjustLevel(now, false);
  }

  // networkLimited: 刚确认瓶颈在网络。平时编码器短时间超出码率是正常的，超过1.5倍才认为它不按码率工作
  private static void adjustLevel(long now, boolean networkLimited) {
    if (outputBps <= 0) return;
    int max = LEVEL_PERCENT.length - 1;
    int newLevel = level;
    if (outputBps > targetBitrate * (networkLimited ? 11L : 15L) / 10) {
      // 选出预计能降到目标以内的最低档位
      while (newLevel < max && (long) outputBps * LEVEL_PERCENT[newLevel] / LEVEL_PERCENT[level] > targetBitrate * 11L / 10) newLevel++;
    } else if (level > 0 && now - lastLevelChangeMs > levelUpDwellMs && now - lastBacklogMs > levelUpDwellMs
      && (long) outputBps * LEVEL_PERCENT[level - 1] / LEVEL_PERCENT[level] < Math.min(targetBitrate, capacityBps > 0 ? capacityBps : Integer.MAX_VALUE) * 9L / 10) {
      // 恢复上一档后预计仍有余量：逐级恢复
      newLevel = level - 1;
    }
    if (now - lastBacklogMs > LEVEL_UP_DWELL_RESET_MS) levelUpDwellMs = LEVEL_UP_DWELL_MIN_MS;
    if (newLevel == level) return;
    lastLevelUpMs = newLevel < level ? now : 0;
    log("实际输出 " + outputBps / 1000 + " kbps，目标 " + targetBitrate / 1000 + " kbps：档位 " + level + " -> " + newLevel
      + (newLevel < level ? "(下次恢复间隔 " + levelUpDwellMs / 1000 + " 秒)" : ""));
    setLevel(newLevel, now);
  }

  private static void setLevel(int newLevel, long now) {
    level = newLevel;
    lastLevelChangeMs = now;
    // 换档后输出码率会变，旧数值不能再用
    outputBps = 0;
    windowStartMs = 0;
  }

  private static final java.text.SimpleDateFormat TIME = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT);

  private static void log(String msg) {
    System.out.println(TIME.format(new java.util.Date()) + " " + msg);
    System.out.flush();
  }

  // Index the next sent frame will get (frames are numbered from 0 per connection)
  public static synchronized int sentCount() {
    return sent;
  }

  // 0表示尚未有连接
  public static int targetBitrate() {
    return targetBitrate;
  }

  // 0为原始帧率与分辨率，数值越大数据量越小
  public static int level() {
    return level;
  }
}
