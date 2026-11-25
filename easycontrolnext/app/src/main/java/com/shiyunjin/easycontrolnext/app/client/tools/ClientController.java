package com.shiyunjin.easycontrolnext.app.client.tools;

import static android.content.ClipDescription.MIMETYPE_TEXT_PLAIN;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.provider.Settings;
import android.util.Pair;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.shiyunjin.easycontrolnext.app.R;
import com.shiyunjin.easycontrolnext.app.client.Client;
import com.shiyunjin.easycontrolnext.app.client.view.FullActivity;
import com.shiyunjin.easycontrolnext.app.client.view.MiniView;
import com.shiyunjin.easycontrolnext.app.client.view.SmallView;
import com.shiyunjin.easycontrolnext.app.entity.AppData;
import com.shiyunjin.easycontrolnext.app.entity.Device;
import com.shiyunjin.easycontrolnext.app.entity.MyInterface;
import com.shiyunjin.easycontrolnext.app.helper.PublicTools;

public class ClientController implements TextureView.SurfaceTextureListener {
  private final Device device;
  private final ClientStream clientStream;
  private final MyInterface.MyFunction handle;
  private final TextureView textureView = new TextureView(AppData.applicationContext);
  private SurfaceTexture surfaceTexture;
  // 视频容器：TextureView + 左上角的统计浮层(帧率/延迟)。各窗口添加/移除的是这个容器
  private final FrameLayout videoView = new FrameLayout(AppData.applicationContext);
  private final TextView statsView = new TextView(AppData.applicationContext);
  public final StreamStats stats = new StreamStats();
  private volatile boolean isClosed = false;

  private SmallView smallView;
  private MiniView miniView;
  private FullActivity fullView;

  private Pair<Integer, Integer> videoSize;
  private Pair<Integer, Integer> maxSize;
  private Pair<Integer, Integer> surfaceSize;

  // 执行线程
  private final HandlerThread mainThread = new HandlerThread("easycontrol_client_main");
  private Handler mainHandler;
  // 控制包专用线程：触控/按键不会被主线程上的shell命令、剪贴板检查等阻塞
  private final HandlerThread controlThread = new HandlerThread("easycontrol_client_control", Process.THREAD_PRIORITY_DISPLAY);
  private Handler controlHandler;

  public ClientController(Device device, ClientStream clientStream, MyInterface.MyFunction handle) {
    this.device = device;
    this.clientStream = clientStream;
    this.handle = handle;
    mainThread.start();
    mainHandler = new Handler(mainThread.getLooper());
    controlThread.start();
    controlHandler = new Handler(controlThread.getLooper());
    textureView.setSurfaceTextureListener(this);
    setTouchListener();
    setVideoView();
    // 启动子服务
    mainHandler.post(this::otherService);
    controlHandler.post(this::keepAlive);
    AppData.uiHandler.postDelayed(this::updateStats, 1000);
  }

  private void setVideoView() {
    videoView.addView(textureView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    // 尽量小且半透明，减少对画面的遮挡
    int padding = PublicTools.dp2px(1.5f);
    statsView.setPadding(padding * 2, padding, padding * 2, padding);
    statsView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
    statsView.setTextColor(0xDDFFFFFF);
    statsView.setBackgroundColor(0x55000000);
    statsView.setTypeface(Typeface.MONOSPACE);
    statsView.setSingleLine(true);
    // 浮层不接收触摸，触摸照常交给下面的画面
    statsView.setClickable(false);
    statsView.setFocusable(false);
    statsView.setVisibility(View.GONE);
    videoView.addView(statsView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
  }

  // 每秒刷新一次统计浮层(UI线程)
  private void updateStats() {
    if (isClosed) return;
    String text = stats.snapshot();
    if (AppData.setting.getShowStats()) {
      statsView.setText(text);
      statsView.setVisibility(View.VISIBLE);
    } else statsView.setVisibility(View.GONE);
    AppData.uiHandler.postDelayed(this::updateStats, 1000);
  }

  // 心跳放在控制线程：主线程上可能正在执行耗时的shell命令，以前心跳会被一起卡住，
  // 服务端因此误判断线。写入失败不在这里处理，由读线程/看门狗发现断线并触发重连。
  // 心跳使用ping包：服务端回复后可同时得到RTT与两端时钟差(用于计算画面延迟)
  private void keepAlive() {
    try {
      clientStream.writeToMain(ControlPacket.createPing(System.nanoTime()));
    } catch (Exception ignored) {
    }
    controlHandler.postDelayed(this::keepAlive, 1000);
  }

  // 在控制线程上执行(写入可能阻塞，不能放在读线程或UI线程)
  public void runOnControlThread(Runnable runnable, long delayMs) {
    Handler handler = controlHandler;
    if (handler == null) return;
    if (delayMs <= 0) handler.post(runnable);
    else handler.postDelayed(runnable, delayMs);
  }

  public void handleAction(String action, ByteBuffer byteBuffer, int delay) {
    Handler handler = "writeByteBuffer".equals(action) ? controlHandler : mainHandler;
    if (delay == 0) handler.post(() -> handleAction(action, byteBuffer));
    else handler.postDelayed(() -> handleAction(action, byteBuffer), delay);
  }

  private void handleAction(String action, ByteBuffer byteBuffer) {
    try {
      switch (action) {
        case "changeToSmall":
          changeToSmall();
          break;
        case "changeToFull":
          changeToFull();
          break;
        case "changeToMini":
          changeToMini(byteBuffer);
          break;
        case "changeToApp":
          changeToApp();
          break;
        case "buttonPower":
          clientStream.writeToMain(ControlPacket.createPowerEvent(-1));
          break;
        case "buttonWake":
          clientStream.writeToMain(ControlPacket.createPowerEvent(1));
          break;
        case "buttonLock":
          clientStream.writeToMain(ControlPacket.createPowerEvent(0));
          break;
        case "buttonLight":
          clientStream.writeToMain(ControlPacket.createLightEvent(Display.STATE_ON));
          clientStream.writeToMain(ControlPacket.createLightEvent(Display.STATE_OFF));
          break;
        case "buttonLightOff":
          clientStream.writeToMain(ControlPacket.createLightEvent(Display.STATE_UNKNOWN));
          break;
        case "buttonBack":
          clientStream.writeToMain(ControlPacket.createKeyEvent(4, 0));
          break;
        case "buttonHome":
          clientStream.writeToMain(ControlPacket.createKeyEvent(3, 0));
          break;
        case "buttonSwitch":
          clientStream.writeToMain(ControlPacket.createKeyEvent(187, 0));
          break;
        case "buttonRotate":
          clientStream.writeToMain(ControlPacket.createRotateEvent());
          break;
        case "keepAlive":
          clientStream.writeToMain(ControlPacket.createKeepAlive());
          break;
        case "checkSizeAndSite":
          checkSizeAndSite();
          break;
        case "checkClipBoard":
          checkClipBoard();
          break;
        case "updateSite":
          updateSite(byteBuffer);
          break;
        default:
          if (byteBuffer == null) break;
        case "writeByteBuffer":
          // 例如剪贴板内容为空或超过5000字节时createClipboardEvent返回null，不应被当作断线
          if (byteBuffer != null) clientStream.writeToMain(byteBuffer);
          break;
        case "updateMaxSize":
          updateMaxSize(byteBuffer);
          break;
        case "updateVideoSize":
          updateVideoSize(byteBuffer);
          break;
        case "runShell":
          runShell(byteBuffer);
          break;
        case "setClipBoard":
          setClipBoard(byteBuffer);
          break;
      }
    } catch (Exception e) {
      // shell命令失败与视频连接无关，不应断开整个会话
      if (action.equals("runShell") || action.equals("changeToApp")) {
        PublicTools.logToast("controller", e.toString(), true);
        return;
      }
      // 其余失败(写入控制通道)交给Client：先尝试接回服务端上的原会话，失败才关闭
      byte[] err = ("controller" + AppData.applicationContext.getString(R.string.toast_stream_closed) + action).getBytes(StandardCharsets.UTF_8);
      Client.sendAction(device.uuid, "connectionLost", ByteBuffer.wrap(err), 0);
    }
  }

  private void otherService() {
    handleAction("checkClipBoard", null, 0);
    handleAction("checkSizeAndSite", null, 0);
    mainHandler.postDelayed(this::otherService, 2000);
  }

  public void setFullView(FullActivity fullView) {
    this.fullView = fullView;
  }

  public TextureView getTextureView() {
    return textureView;
  }

  // 放进窗口的是这个容器(画面+统计浮层)，尺寸仍由textureView的LayoutParams决定
  public FrameLayout getVideoView() {
    return videoView;
  }

  // 取出视频容器以放入新窗口：先从原窗口脱离(一个View只能有一个父布局，否则addView直接抛异常导致崩溃)，
  // 并使用包裹内容的布局参数，与以前直接放入TextureView时的尺寸行为一致
  public FrameLayout takeVideoView() {
    if (videoView.getParent() instanceof ViewGroup) ((ViewGroup) videoView.getParent()).removeView(videoView);
    videoView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    return videoView;
  }

  public SurfaceTexture getSurfaceTexture() {
    return surfaceTexture;
  }

  private synchronized void changeToFull() {
    hide();
    Intent intent = new Intent(AppData.mainActivity, FullActivity.class);
    intent.putExtra("uuid", device.uuid);
    AppData.mainActivity.startActivity(intent);
  }

  private synchronized void changeToSmall() {
    hide();
    if (noFloatPermission()) {
      PublicTools.logToast("controller", AppData.applicationContext.getString(R.string.toast_float_per), true);
      changeToFull();
    } else {
      if (smallView == null) smallView = new SmallView(device.uuid);
      AppData.uiHandler.post(smallView::show);
      updateSite(null);
    }
  }

  private synchronized void changeToMini(ByteBuffer byteBuffer) {
    hide();
    if (noFloatPermission()) {
      PublicTools.logToast("controller", AppData.applicationContext.getString(R.string.toast_float_per), true);
      changeToFull();
    } else {
      if (miniView == null) miniView = new MiniView(device.uuid);
      AppData.uiHandler.post(() -> miniView.show(byteBuffer));
    }
  }

  // 检查悬浮窗权限
  private boolean noFloatPermission() {
    // 检查悬浮窗权限，防止某些设备如鸿蒙不兼容
    try {
      return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(AppData.applicationContext);
    } catch (Exception ignored) {
      return false;
    }
  }

  // 不加synchronized：其中的shell命令可能耗时很久，持有锁会让UI线程上的hide()等方法一起卡住(ANR)
  private void changeToApp() throws Exception {
    if (noFloatPermission()) {
      PublicTools.logToast("controller", AppData.applicationContext.getString(R.string.toast_float_per), true);
      return;
    }
    // 获取当前APP
    String output = clientStream.runShell("dumpsys window | grep mCurrentFocus=Window");
    // 创建匹配器
    Matcher matcher = Pattern.compile(" ([a-zA-Z0-9.]+)/").matcher(output);
    // 进行匹配
    if (matcher.find()) {
      Device tempDevice = device.clone(String.valueOf(UUID.randomUUID()));
      tempDevice.name = "----";
      tempDevice.startApp = matcher.group(1);
      // 服务端在整个会话期间都占用端口(用于断线重连)，单应用会话需要另用一个端口
      tempDevice.serverPort = device.serverPort + 1 + new java.util.Random().nextInt(500);
      // 为了错开界面
      tempDevice.smallX += 200;
      tempDevice.smallY += 200;
      tempDevice.smallLength -= 200;
      tempDevice.miniY += 200;
      Client.startDevice(tempDevice);
    }
  }

  private synchronized void hide() {
    if (fullView != null) AppData.uiHandler.post(fullView::hide);
    fullView = null;
    if (smallView != null) AppData.uiHandler.post(smallView::hide);
    if (miniView != null) AppData.uiHandler.post(miniView::hide);
  }

  public void close() {
    isClosed = true;
    hide();
    mainThread.quitSafely();
    controlThread.quitSafely();
    if (surfaceTexture != null) surfaceTexture.release();
  }

  // 等待已排队的操作(例如关闭时的锁屏)执行完毕
  public void awaitThreads(long timeoutMs) {
    long deadline = System.currentTimeMillis() + timeoutMs;
    for (Thread thread : new Thread[]{mainThread, controlThread}) {
      long remaining = deadline - System.currentTimeMillis();
      if (remaining <= 0 || Thread.currentThread() == thread) continue;
      try {
        thread.join(remaining);
      } catch (InterruptedException e) {
        return;
      }
    }
  }

  private static final int minLength = PublicTools.dp2px(200f);

  private void updateMaxSize(ByteBuffer byteBuffer) {
    int width = Math.max(byteBuffer.getInt(), minLength);
    int height = Math.max(byteBuffer.getInt(), minLength);
    this.maxSize = new Pair<>(width, height);
    AppData.uiHandler.post(this::reCalculateTextureViewSize);
  }

  private void updateVideoSize(ByteBuffer byteBuffer) {
    int width = byteBuffer.getInt();
    int height = byteBuffer.getInt();
    if (width <= 100 || height <= 100) return;
    this.videoSize = new Pair<>(width, height);
    updateSite(null);
    AppData.uiHandler.post(this::reCalculateTextureViewSize);
  }

  private void updateSite(ByteBuffer byteBuffer) {
    if (smallView == null || videoSize == null || !smallView.isShow()) return;
    int x;
    int y;
    boolean isAuto = byteBuffer == null;
    if (videoSize.first < videoSize.second) {
      x = isAuto ? device.smallX : byteBuffer.getInt();
      y = isAuto ? device.smallY : byteBuffer.getInt();
      device.smallX = x;
      device.smallY = y;
    } else {
      x = isAuto ? device.smallXLan : byteBuffer.getInt();
      y = isAuto ? device.smallYLan : byteBuffer.getInt();
      device.smallXLan = x;
      device.smallYLan = y;
    }
    AppData.uiHandler.post(() -> smallView.updateView(x, y));
  }

  // 重新计算TextureView大小
  private void reCalculateTextureViewSize() {
    if (maxSize == null || videoSize == null) return;
    Pair<Integer, Integer> maxSize = this.maxSize;
    if (smallView != null && smallView.isShow()) {
      if (videoSize.first < videoSize.second) maxSize = new Pair<>(this.maxSize.first, this.maxSize.first);
      else maxSize = new Pair<>(this.maxSize.second, this.maxSize.second);
    }
    // 根据原画面大小videoSize计算在maxSize空间内的最大缩放大小
    int tmp1 = videoSize.second * maxSize.first / videoSize.first;
    // 横向最大不会超出
    if (maxSize.second > tmp1) surfaceSize = new Pair<>(maxSize.first, tmp1);
      // 竖向最大不会超出
    else surfaceSize = new Pair<>(videoSize.first * maxSize.second / videoSize.second, maxSize.second);
    // 更新大小
    ViewGroup.LayoutParams layoutParams = textureView.getLayoutParams();
    layoutParams.width = surfaceSize.first;
    layoutParams.height = surfaceSize.second;
    textureView.setLayoutParams(layoutParams);
  }

  // 检查画面是否超出
  private void checkSizeAndSite() {
    // 碎碎念，感谢 波瑠卡 的关爱，今天一家四口一起去医院进年货去了，每人提了一袋子(´；ω；`)
    if (smallView != null) AppData.uiHandler.post(smallView::checkSizeAndSite);
  }

  // 设置视频区域触摸监听
  @SuppressLint("ClickableViewAccessibility")
  private void setTouchListener() {
    textureView.setOnTouchListener((view, event) -> {
      if (surfaceSize == null) return true;
      int action = event.getActionMasked();
      // 让系统立即派发后续触摸事件，而不是按vsync批量合并(可减少最多一帧的输入延迟)
      if (action == MotionEvent.ACTION_DOWN) view.requestUnbufferedDispatch(event);
      if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
        int i = event.getActionIndex();
        // 按pointerId记录按下时间：pointer index会在其他手指抬起后变化
        pointerDownTime[event.getPointerId(i)] = event.getEventTime();
        createTouchPacket(event, MotionEvent.ACTION_DOWN, i);
      } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) createTouchPacket(event, MotionEvent.ACTION_UP, event.getActionIndex());
      else for (int i = 0; i < event.getPointerCount(); i++) createTouchPacket(event, MotionEvent.ACTION_MOVE, i);
      return true;
    });
  }

  // Android的pointerId范围是0~31
  private static final int MAX_POINTER_ID = 32;
  private final int[] pointerList = new int[MAX_POINTER_ID * 2];
  private final long[] pointerDownTime = new long[MAX_POINTER_ID];

  private void createTouchPacket(MotionEvent event, int action, int i) {
    int p = event.getPointerId(i);
    if (p >= MAX_POINTER_ID) return;
    int offsetTime = (int) (event.getEventTime() - pointerDownTime[p]);
    int x = (int) event.getX(i);
    int y = (int) event.getY(i);
    // 只跳过完全没有变化的移动。旧代码丢弃4px以内的移动：小窗缩放后对应被控端十几像素，
    // 摇杆/拖动类操作会变得一顿一顿的；一个触控包只有15字节，带宽可以忽略
    if (action == MotionEvent.ACTION_MOVE && pointerList[p] == x && pointerList[MAX_POINTER_ID + p] == y) return;
    pointerList[p] = x;
    pointerList[MAX_POINTER_ID + p] = y;
    handleAction("writeByteBuffer", ControlPacket.createTouchEvent(action, p, (float) x / surfaceSize.first, (float) y / surfaceSize.second, offsetTime), 0);
  }

  // 剪切板
  private String nowClipboardText = "";

  private void checkClipBoard() {
    if (!device.listenClip) return;
    ClipData clipBoard = AppData.clipBoard.getPrimaryClip();
    if (clipBoard != null && clipBoard.getItemCount() > 0) {
      String newClipBoardText = String.valueOf(clipBoard.getItemAt(0).getText());
      if (!Objects.equals(nowClipboardText, newClipBoardText)) {
        nowClipboardText = newClipBoardText;
        ByteBuffer clipboardEvent = ControlPacket.createClipboardEvent(nowClipboardText);
        if (clipboardEvent != null) handleAction("writeByteBuffer", clipboardEvent, 0);
      }
    }
  }

  private void setClipBoard(ByteBuffer byteBuffer) {
    nowClipboardText = new String(byteBuffer.array());
    AppData.clipBoard.setPrimaryClip(ClipData.newPlainText(MIMETYPE_TEXT_PLAIN, nowClipboardText));
  }

  private void runShell(ByteBuffer byteBuffer) throws Exception {
    String cmd = new String(byteBuffer.array());
    clientStream.runShell(cmd);
  }

  @Override
  public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int i, int i1) {
    // 初始化
    if (this.surfaceTexture == null) {
      this.surfaceTexture = surfaceTexture;
      handle.run();
    } else textureView.setSurfaceTexture(this.surfaceTexture);
  }

  @Override
  public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int i, int i1) {
  }

  @Override
  public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
    return false;
  }

  @Override
  public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {
  }

}
