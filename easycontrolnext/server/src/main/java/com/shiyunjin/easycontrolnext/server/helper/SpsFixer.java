package com.shiyunjin.easycontrolnext.server.helper;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

// 为H.264的SPS补上VUI bitstream_restriction：max_num_reorder_frames=0。
//
// 很多编码器不写这一项(实测redroid：Main Profile、POC type 0、没有VUI)。解码器因此只能假设码流里可能有B帧，
// 要按DPB大小先缓存若干帧再输出(720p@Level3.1为5帧)：60fps时约多80ms，画面变化少、帧率低时可达1秒。
// 写明“无重排序”后，解码器收到一帧就能输出一帧。Moonlight/Sunshine对H.264也做同样的处理。
//
// 只在SPS里没有bitstream_restriction时补写；编码器自己声明了重排序(说明真的有B帧)则不改动。
public final class SpsFixer {
  // 发现码流实际有B帧(输出pts倒退)时关闭
  public static volatile boolean enabled = true;

  private SpsFixer() {
  }

  // 返回修改后的完整数据包；不含SPS、无需修改或无法解析时返回null(调用方照常发送原包)
  public static byte[] fix(ByteBuffer packet) {
    if (!enabled) return null;
    try {
      byte[] data = new byte[packet.remaining()];
      packet.duplicate().get(data);
      int i = 0;
      while ((i = findStartCode(data, i)) >= 0) {
        int nalStart = i + 3;
        if (nalStart >= data.length) return null;
        int type = data[nalStart] & 0x1F;
        if (type >= 1 && type <= 5) return null; // SPS总在图像分片之前，到这里说明没有SPS
        int next = findStartCode(data, nalStart);
        int nalEnd = next < 0 ? data.length : next;
        // 下一个起始码若是4字节形式(00 00 00 01)，其前导0不属于本NAL
        while (nalEnd > nalStart + 1 && data[nalEnd - 1] == 0) nalEnd--;
        if (type == 7) {
          byte[] newNal = fixSpsNal(data, nalStart, nalEnd);
          if (newNal == null) return null;
          ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 16);
          out.write(data, 0, nalStart);
          out.write(newNal, 0, newNal.length);
          out.write(data, nalEnd, data.length - nalEnd);
          return out.toByteArray();
        }
        i = nalStart;
      }
    } catch (RuntimeException ignored) {
      // 解析失败(不认识的SPS)：保持原样
    }
    return null;
  }

  private static int findStartCode(byte[] data, int from) {
    for (int i = from; i + 2 < data.length; i++) {
      if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) return i;
    }
    return -1;
  }

  // nal[start]为NAL头。返回带NAL头、已重新加入防竞争字节的新SPS；无需修改时返回null
  private static byte[] fixSpsNal(byte[] data, int start, int end) {
    byte[] rbsp = unescape(data, start + 1, end);
    BitReader r = new BitReader(rbsp);
    int profileIdc = r.u(8);
    r.u(16); // constraint_set标志 + level_idc
    r.ue(); // seq_parameter_set_id
    if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 || profileIdc == 244 || profileIdc == 44
      || profileIdc == 83 || profileIdc == 86 || profileIdc == 118 || profileIdc == 128 || profileIdc == 138
      || profileIdc == 139 || profileIdc == 134 || profileIdc == 135) {
      int chromaFormatIdc = r.ue();
      if (chromaFormatIdc == 3) r.u(1);
      r.ue(); // bit_depth_luma_minus8
      r.ue(); // bit_depth_chroma_minus8
      r.u(1); // qpprime_y_zero_transform_bypass_flag
      if (r.u(1) == 1) { // seq_scaling_matrix_present_flag
        int lists = chromaFormatIdc != 3 ? 8 : 12;
        for (int k = 0; k < lists; k++) {
          if (r.u(1) == 1) skipScalingList(r, k < 6 ? 16 : 64);
        }
      }
    }
    r.ue(); // log2_max_frame_num_minus4
    int pocType = r.ue();
    if (pocType == 0) {
      r.ue();
    } else if (pocType == 1) {
      r.u(1);
      r.se();
      r.se();
      int cycle = r.ue();
      for (int k = 0; k < cycle; k++) r.se();
    }
    int maxNumRefFrames = r.ue();
    r.u(1); // gaps_in_frame_num_value_allowed_flag
    r.ue(); // pic_width_in_mbs_minus1
    r.ue(); // pic_height_in_map_units_minus1
    if (r.u(1) == 0) r.u(1); // frame_mbs_only_flag / mb_adaptive_frame_field_flag
    r.u(1); // direct_8x8_inference_flag
    if (r.u(1) == 1) { // frame_cropping_flag
      r.ue();
      r.ue();
      r.ue();
      r.ue();
    }
    BitWriter w = new BitWriter();
    int vuiFlagPos = r.pos();
    if (r.u(1) == 0) {
      // 没有VUI：写一个只含bitstream_restriction的最小VUI
      w.copy(rbsp, 0, vuiFlagPos);
      w.u(1, 1); // vui_parameters_present_flag
      // aspect_ratio / overscan / video_signal_type / chroma_loc / timing / nal_hrd / vcl_hrd / pic_struct 全部不存在
      w.u(0, 8);
      writeRestriction(w, maxNumRefFrames);
    } else {
      if (r.u(1) == 1 && r.u(8) == 255) r.u(32); // aspect_ratio_info -> sar_width/height
      if (r.u(1) == 1) r.u(1); // overscan
      if (r.u(1) == 1) { // video_signal_type
        r.u(4);
        if (r.u(1) == 1) r.u(24);
      }
      if (r.u(1) == 1) { // chroma_loc_info
        r.ue();
        r.ue();
      }
      if (r.u(1) == 1) { // timing_info
        r.u(32);
        r.u(32);
        r.u(1);
      }
      boolean nalHrd = r.u(1) == 1;
      if (nalHrd) skipHrd(r);
      boolean vclHrd = r.u(1) == 1;
      if (vclHrd) skipHrd(r);
      if (nalHrd || vclHrd) r.u(1); // low_delay_hrd_flag
      r.u(1); // pic_struct_present_flag
      int restrictionFlagPos = r.pos();
      if (r.u(1) == 1) return null; // 编码器已声明重排序信息：以它为准
      w.copy(rbsp, 0, restrictionFlagPos);
      writeRestriction(w, maxNumRefFrames);
    }
    w.trailingBits();
    byte[] newRbsp = w.toByteArray();
    ByteArrayOutputStream out = new ByteArrayOutputStream(newRbsp.length + 8);
    out.write(data[start]);
    escape(newRbsp, out);
    return out.toByteArray();
  }

  private static void writeRestriction(BitWriter w, int maxNumRefFrames) {
    w.u(1, 1); // bitstream_restriction_flag
    w.u(1, 1); // motion_vectors_over_pic_boundaries_flag
    w.ue(2); // max_bytes_per_pic_denom(默认值)
    w.ue(1); // max_bits_per_mb_denom(默认值)
    w.ue(16); // log2_max_mv_length_horizontal
    w.ue(16); // log2_max_mv_length_vertical
    w.ue(0); // max_num_reorder_frames：没有B帧，无需重排序
    // max_dec_frame_buffering不能小于参考帧数，否则部分解码器报错
    w.ue(Math.max(1, maxNumRefFrames));
  }

  private static void skipScalingList(BitReader r, int size) {
    int lastScale = 8;
    int nextScale = 8;
    for (int j = 0; j < size; j++) {
      if (nextScale != 0) nextScale = (lastScale + r.se() + 256) % 256;
      lastScale = nextScale == 0 ? lastScale : nextScale;
    }
  }

  private static void skipHrd(BitReader r) {
    int cpbCnt = r.ue() + 1;
    r.u(8); // bit_rate_scale + cpb_size_scale
    for (int k = 0; k < cpbCnt; k++) {
      r.ue();
      r.ue();
      r.u(1);
    }
    r.u(20); // 4个5位长度字段
  }

  // 去掉防竞争字节(00 00 03 -> 00 00)
  private static byte[] unescape(byte[] data, int from, int to) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(to - from);
    int zeros = 0;
    for (int i = from; i < to; i++) {
      int b = data[i] & 0xFF;
      if (zeros >= 2 && b == 3) {
        zeros = 0;
        continue;
      }
      out.write(b);
      zeros = b == 0 ? zeros + 1 : 0;
    }
    return out.toByteArray();
  }

  private static void escape(byte[] rbsp, ByteArrayOutputStream out) {
    int zeros = 0;
    for (byte value : rbsp) {
      int b = value & 0xFF;
      if (zeros >= 2 && b <= 3) {
        out.write(3);
        zeros = 0;
      }
      out.write(b);
      zeros = b == 0 ? zeros + 1 : 0;
    }
  }

  private static final class BitReader {
    private final byte[] data;
    private int pos = 0;

    BitReader(byte[] data) {
      this.data = data;
    }

    int pos() {
      return pos;
    }

    int u(int n) {
      int v = 0;
      for (int k = 0; k < n; k++) {
        if ((pos >> 3) >= data.length) throw new IllegalStateException("SPS too short");
        v = (v << 1) | ((data[pos >> 3] >> (7 - (pos & 7))) & 1);
        pos++;
      }
      return v;
    }

    int ue() {
      int zeros = 0;
      while (u(1) == 0) {
        if (++zeros > 31) throw new IllegalStateException("bad exp-golomb");
      }
      return (int) ((1L << zeros) - 1 + u(zeros));
    }

    int se() {
      int v = ue();
      return (v & 1) != 0 ? (v + 1) / 2 : -(v / 2);
    }
  }

  private static final class BitWriter {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private int current = 0;
    private int bits = 0;

    void u(int value, int n) {
      for (int k = n - 1; k >= 0; k--) bit((value >> k) & 1);
    }

    void ue(int value) {
      int v = value + 1;
      int len = 32 - Integer.numberOfLeadingZeros(v);
      u(0, len - 1);
      u(v, len);
    }

    // 原样复制前n位
    void copy(byte[] src, int fromBit, int n) {
      for (int p = fromBit; p < fromBit + n; p++) bit((src[p >> 3] >> (7 - (p & 7))) & 1);
    }

    void trailingBits() {
      bit(1);
      while (bits != 0) bit(0);
    }

    private void bit(int b) {
      current = (current << 1) | b;
      if (++bits == 8) {
        out.write(current);
        current = 0;
        bits = 0;
      }
    }

    byte[] toByteArray() {
      return out.toByteArray();
    }
  }
}
