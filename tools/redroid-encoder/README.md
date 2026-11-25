# Redroid hardware encoder tuning

Redroid's H.264 encoder (`OMX.redroid.h264.encoder`) is FFmpeg `h264_vaapi` compiled into
`/vendor/lib/libOmxCore.so`. Its settings are hard-coded and ignore everything Android asks for
(logcat: `RedroidAVCEnc: TODO unsupported setBitRate()`):

| Setting | Hard-coded value | Effect |
|---|---|---|
| `rc_mode` | `CQP` | fixed quality, no bitrate control |
| `global_quality` | 29 | QP 29, about 2.1 Mbit/s for a busy 720x1280 60 fps game scene |
| `gop_size` | 150 | a 36-57 KB keyframe every 150 frames |

The files here are byte-patched copies of that library (3 to 9 bytes changed, see `make_patches.py`).
All variants set the keyframe distance to 3600 frames.

| File | Change | Expected bitrate, busy scene at 60 fps (estimate, not measured) |
|---|---|---|
| `libOmxCore_gop3600.so` | QP 29 unchanged, only the keyframe distance | ~1.95 Mbit/s busy (-7%), up to -90% on calm screens; picture identical |
| `libOmxCore_qp31.so` | QP 31 | ~1.5 Mbit/s (-30%); no visible difference in `quality_compare_qp31_32.png` |
| `libOmxCore_qp32.so` | QP 32 | ~1.3 Mbit/s |
| `libOmxCore_qp34.so` | QP 34 | ~1.2 Mbit/s |
| `libOmxCore_qp36.so` | QP 36 | ~0.95 Mbit/s |
| `libOmxCore_qp38.so` | QP 38 | ~0.75 Mbit/s |
| `libOmxCore_qp40.so` | QP 40 | ~0.6 Mbit/s |
| `libOmxCore_vbr500k.so` | VBR, 500 kbit/s target | 0.5 Mbit/s average |
| `libOmxCore_vbr800k.so` | VBR, 800 kbit/s target | 0.8 Mbit/s average |
| `libOmxCore_vbr1200k.so` | VBR, 1200 kbit/s target | 1.2 Mbit/s average |

The estimates use the rule of thumb that +6 QP halves the bitrate. Higher QP means a softer picture.
The QP variants only change a number on the code path that already works. The VBR variants also switch
the rate-control mode, which the GPU driver must support; if it does not, the encoder fails to start
and you should go back to a QP variant or the original.

None of these has been run yet: installing one needs access to the container.

## Install (on the machine running the container)

```sh
docker cp libOmxCore_qp34.so <container>:/vendor/lib/libOmxCore.so
docker exec <container> sh -c 'chmod 644 /vendor/lib/libOmxCore.so; kill $(pidof android.hardware.media.omx@1.0-service)'
```

Android restarts the codec service by itself. Reconnect the app afterwards. If `docker cp` is refused,
recreate the container with `-v /path/libOmxCore_qp34.so:/vendor/lib/libOmxCore.so:ro`.

## Roll back

```sh
docker cp libOmxCore_original.so <container>:/vendor/lib/libOmxCore.so
docker exec <container> sh -c 'kill $(pidof android.hardware.media.omx@1.0-service)'
```

`libOmxCore_original.so` sha256: `ed26720e445b166797960327a53e15825d9534071b10ce7773dcceda3db9dab5`.
The patches apply only to that exact file; `make_patches.py` refuses any other.

## Check the result

```sh
python tools/bench/bench_stream.py --serial <ip:port> --jar easycontrolnext/app/src/main/res/raw/easycontrolnext_server.jar --seconds 10 --device-port 25191 --local-port 27198
```

Compare `kbps`, `idr.count` and `frame_bytes` with the unpatched numbers above.
