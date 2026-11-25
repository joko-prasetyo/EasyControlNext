package com.shiyunjin.easycontrolnext.app.client;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Pair;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Objects;

import com.shiyunjin.easycontrolnext.app.R;
import com.shiyunjin.easycontrolnext.app.client.tools.AdbTools;
import com.shiyunjin.easycontrolnext.app.client.tools.ClientController;
import com.shiyunjin.easycontrolnext.app.client.tools.ClientPlayer;
import com.shiyunjin.easycontrolnext.app.client.tools.ClientStream;
import com.shiyunjin.easycontrolnext.app.client.tools.ControlPacket;
import com.shiyunjin.easycontrolnext.app.databinding.ItemLoadingBinding;
import com.shiyunjin.easycontrolnext.app.entity.AppData;
import com.shiyunjin.easycontrolnext.app.entity.Device;
import com.shiyunjin.easycontrolnext.app.helper.PublicTools;
import com.shiyunjin.easycontrolnext.app.helper.ViewTools;

public class Client {
  private static final HashMap<String, Client> allClient = new HashMap<>();
  private volatile boolean isClosed = false;

  // 组件
  private ClientStream clientStream = null;
  private ClientController clientController = null;
  private volatile ClientPlayer clientPlayer = null;
  private Device device;

  // 断线重连：服务端断线后会保留会话(默认60秒)，这里在该时间内反复尝试接回
  // 超过服务端的等待时间后服务端会自行退出并恢复设备设置；此时由restartServerIfDead重新启动它，
  // 所以这里可以等得更久(网络中断几分钟后仍能自动恢复，用户也可以随时手动关闭)
  private static final int RESUME_WINDOW = 1000 * 180;
  private static final int RESUME_ATTEMPT_TIMEOUT = 1000 * 4;
  // 防止“连上->立刻失败”的死循环(例如解码器无法创建)：1分钟内最多自动恢复5次
  private static final int MAX_RESUMES_PER_MINUTE = 5;
  private final ArrayDeque<Long> resumeHistory = new ArrayDeque<>();
  private final Object reconnectLock = new Object();
  private boolean isReconnecting = false;

  private WifiManager.WifiLock wifiLock = null;

  public Client(Device device) {
    if (allClient.containsKey(device.uuid)) return;
    this.device = device;
    Pair<ItemLoadingBinding, Dialog> loading = ViewTools.createLoading(AppData.mainActivity);
    loading.second.show();
    // 连接
    clientStream = new ClientStream(device, bool -> {
      if (bool) {
        allClient.put(device.uuid, this);
        acquireWifiLock();
        // 控制器、播放器
        clientController = new ClientController(device, clientStream, this::startPlayer);
        // 临时设备
        boolean isTempDevice = device.isTempDevice();
        // 启动界面
        clientController.handleAction(device.changeToFullOnConnect ? "changeToFull" : "changeToSmall", null, 0);
        // 运行启动时操作
        if (device.customResolutionOnConnect) clientController.handleAction("writeByteBuffer", ControlPacket.createChangeResolutionEvent(device.customResolutionWidth, device.customResolutionHeight), 0);
        if (!isTempDevice && device.wakeOnConnect) clientController.handleAction("buttonWake", null, 0);
        if (!isTempDevice && device.lightOffOnConnect) clientController.handleAction("buttonLightOff", null, 2000);
      }
      if (loading.second.isShowing()) loading.second.cancel();
    });
  }

  // 播放器只从这里创建：首次显示画面时(Surface就绪)和断线重连成功后都会调用，
  // 保证同一时刻只有一个ClientPlayer在读取数据流
  private final Object playerLock = new Object();

  private void startPlayer() {
    synchronized (playerLock) {
      if (isClosed || clientPlayer != null || clientController == null) return;
      if (clientController.getSurfaceTexture() == null || !clientStream.isConnected()) return;
      clientPlayer = new ClientPlayer(device.uuid, clientStream);
    }
  }

  private ClientPlayer takePlayer() {
    synchronized (playerLock) {
      ClientPlayer player = clientPlayer;
      clientPlayer = null;
      return player;
    }
  }

  public static void startDevice(Device device) {
    if (device == null) return;
    if (!device.changeToFullOnConnect && requestFloatPermission()) return;
    new Client(device);
  }

  // 小窗/迷你窗需要“显示在其他应用的上层”权限。以前缺少权限时只提示一句就退回全屏，用户不知道去哪里开启
  // (重新安装或更换签名后该权限会被清除)。本次进程内第一次连接时直接打开该权限的设置页，授权后返回再连接即可；
  // 用户不授权则之后照旧以全屏模式连接
  private static boolean floatPermissionAsked = false;

  private static boolean requestFloatPermission() {
    if (floatPermissionAsked || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
    try {
      if (Settings.canDrawOverlays(AppData.applicationContext)) return false;
      floatPermissionAsked = true;
      Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + AppData.applicationContext.getPackageName()));
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      AppData.applicationContext.startActivity(intent);
      PublicTools.logToast("client", AppData.applicationContext.getString(R.string.toast_float_per_request), true);
      return true;
    } catch (Exception ignored) {
      // 个别系统(如部分鸿蒙)没有该设置页：保持旧行为
      return false;
    }
  }

  public static Device getDevice(String uuid) {
    Client client = allClient.get(uuid);
    if (client == null) return null;
    return client.device;
  }

  public static ClientController getClientController(String uuid) {
    Client client = allClient.get(uuid);
    if (client == null) return null;
    return client.clientController;
  }

  public static void sendAction(String uuid, String action, ByteBuffer byteBuffer, int delay) {
    if (action == null || uuid == null) return;
    if (action.equals("start")) {
      for (Device device : AdbTools.devicesList) if (Objects.equals(device.uuid, uuid)) startDevice(device);
    } else {
      Client client = allClient.get(uuid);
      if (client == null) return;
      if (action.equals("close")) {
        client.close(byteBuffer, true);
      } else if (action.equals("fatal")) {
        // 本机故障(如解码器无法创建)：关闭且不自动重启，否则会无限循环
        client.close(byteBuffer, false);
      } else if (action.equals("connectionLost")) {
        client.onConnectionLost(byteBuffer);
      } else {
        if (client.clientController == null) return;
        client.clientController.handleAction(action, byteBuffer, delay);
      }
    }
  }

  // 连接中断：保留窗口与控制器，只重建数据连接和解码器
  private void onConnectionLost(ByteBuffer reason) {
    synchronized (reconnectLock) {
      if (isClosed || isReconnecting || clientStream == null) return;
      long now = System.currentTimeMillis();
      while (!resumeHistory.isEmpty() && now - resumeHistory.peekFirst() > 60_000) resumeHistory.pollFirst();
      if (resumeHistory.size() >= MAX_RESUMES_PER_MINUTE) {
        isReconnecting = false;
        // close可能触发自动重连并弹出加载框，必须在UI线程执行
        AppData.uiHandler.post(() -> close(reason, true));
        return;
      }
      resumeHistory.addLast(now);
      isReconnecting = true;
    }
    if (clientController != null) clientController.stats.setReconnecting(true);
    new Thread(() -> {
      PublicTools.logToast("Client", AppData.applicationContext.getString(R.string.toast_reconnecting), true);
      ClientPlayer oldPlayer = takePlayer();
      if (oldPlayer != null) oldPlayer.close();
      // 关闭socket以唤醒阻塞的读线程，再等它们释放解码器(新解码器要绑定同一个Surface)
      clientStream.closeStreams();
      if (oldPlayer != null) oldPlayer.join(10_000);
      long deadline = System.currentTimeMillis() + RESUME_WINDOW;
      int failures = 0;
      while (!isClosed && System.currentTimeMillis() < deadline) {
        try {
          // 第一次失败后，以及之后每隔几次，检查服务端是否还活着，不在了就重新启动
          if (failures == 1 || (failures > 1 && failures % 4 == 0)) {
            try {
              clientStream.restartServerIfDead(device);
            } catch (Exception ignored) {
              // ADB此时也连不上(网络仍中断)：继续等待
            }
          }
          failures++;
          clientStream.reconnect(device, RESUME_ATTEMPT_TIMEOUT);
          if (isClosed) break;
          // 若此时画面Surface尚未就绪，则由控制器在Surface就绪时创建播放器
          startPlayer();
          synchronized (reconnectLock) {
            isReconnecting = false;
          }
          if (clientController != null) clientController.stats.setReconnecting(false);
          PublicTools.logToast("Client", AppData.applicationContext.getString(R.string.toast_reconnected), true);
          return;
        } catch (Exception ignored) {
          try {
            Thread.sleep(500);
          } catch (InterruptedException e) {
            break;
          }
        }
      }
      synchronized (reconnectLock) {
        isReconnecting = false;
      }
      // 接回失败：按原逻辑关闭(若设置了自动重连，会重新启动整个会话)
      if (!isClosed) AppData.uiHandler.post(() -> close(reason, true));
    }, "easycontrol_reconnect").start();
  }

  // 无线连接时保持Wi-Fi处于低延迟/高性能模式：
  // 省电模式下网卡会周期性休眠，表现为几十到几百毫秒的突发延迟，甚至因此掉线
  private void acquireWifiLock() {
    if (device.isLinkDevice()) return;
    try {
      WifiManager wifiManager = (WifiManager) AppData.applicationContext.getSystemService(Context.WIFI_SERVICE);
      if (wifiManager == null) return;
      int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
      wifiLock = wifiManager.createWifiLock(mode, "easycontrol:" + device.uuid);
      wifiLock.setReferenceCounted(false);
      wifiLock.acquire();
    } catch (Exception ignored) {
      wifiLock = null;
    }
  }

  private void releaseWifiLock() {
    try {
      if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
    } catch (Exception ignored) {
    }
    wifiLock = null;
  }

  private void close(ByteBuffer byteBuffer, boolean allowRestart) {
    synchronized (playerLock) {
      if (isClosed) return;
      isClosed = true;
    }
    // 临时设备
    boolean isTempDevice = device.isTempDevice();
    // 更新数据库
    if (!isTempDevice) AppData.dbHelper.update(device);
    allClient.remove(device.uuid);
    releaseWifiLock();
    // 运行断开时操作
    if (clientController != null) {
      if (!isTempDevice && device.lockOnClose) clientController.handleAction("buttonLock", null, 0);
      else if (!isTempDevice && device.lightOnClose) clientController.handleAction("buttonLight", null, 0);
    }
    // 关闭组件
    ClientPlayer player = takePlayer();
    if (player != null) player.close();
    if (clientController != null) clientController.close();
    // 主动关闭(byteBuffer为空)：等关闭时的操作(锁屏等)发送完，再通知服务端立即退出；
    // 异常关闭则不发送，服务端会在等待期内保留会话
    ClientStream stream = clientStream;
    ClientController controller = clientController;
    boolean isUserClose = byteBuffer == null;
    if (stream != null) {
      new Thread(() -> {
        if (controller != null) controller.awaitThreads(1000);
        if (isUserClose) stream.sendQuit();
        stream.close();
      }, "easycontrol_close").start();
    }
    // 如果设置了自动重连
    if (byteBuffer != null) {
      PublicTools.logToast("Client", new String(byteBuffer.array()), true);
      if (allowRestart && device.reconnectOnClose) startDevice(device);
    }
  }

}
