# 📱 ARMeasure - Android AR测距 App

纯 Camera2 API + dToF 深度传感器实现的测距应用，**零 Google/ARCore 依赖**。

## ✨ 功能

| 模式 | 说明 |
|------|------|
| **单点测距** | 点击屏幕任意位置，显示该点到手机的距离 |
| **两点测距** | 点击两个点，计算3D空间直线距离 |
| **面积测量** | 点击3个以上点围合区域，计算3D表面积 |
| **扫掠测距** | 滑动屏幕实时扫掠，显示距离变化曲线 |

### 高级特性

- **深度融合** — DEPTH16 + ToF 传感器逆方差加权融合
- **IMU 运动检测** — 陀螺仪 + 加速度计检测设备晃动，超阈值自动预警
- **自适应 Kalman 滤波** — 中值 → 尖峰抑制 → 自适应 Kalman，三阶段降噪
- **自适应邻域核** — 近距 3×3 / 中距 5×5 / 远距 7×7，根据深度自动调整
- **RANSAC 平面拟合** — 面积测量支持 RANSAC 最佳平面投影
- **自动对焦联动** — 对焦距离变化时自动重置滤波器
- **校准模式** — 已知距离反向校准，持久化保存
- **测量历史** — 最近 50 条记录，本地 SharedPreferences 存储
- **单位切换** — 长按距离文本切换 cm / inch / m
- **截图保存** — 一键保存测量结果到相册
- **手势缩放** — 双指捏合缩放预览画面

## 🔧 技术原理

```
手机摄像头 (Camera2 API)
├── 主摄 → SurfaceView 相机预览
└── dToF 深度摄像头 → ImageReader (DEPTH16 格式)
                          ↓
                 每帧输出深度图 (mm 精度)
                          ↓
         ┌────────────────┴────────────────┐
         │  自适应邻域核加权采样 (3×3~7×7)    │
         │         ↓                        │
         │  DistanceFilter:                 │
         │  尖峰抑制 → 中值滤波 → Kalman 滤波  │
         │         ↓                        │
         │  5 次采样 + MAD 离群值剔除         │
         │  → 稳健中位数 + 标准差 (不确定性)    │
         └────────────────┬────────────────┘
                          ↓
        ┌─── ToF 传感器 (可选融合) ───┐
        │  逆方差加权: 1/σ² 加权平均   │
        │  ToF σ≈5cm, DEPTH16 σ≈8-12cm │
        └──────────────┬──────────────┘
                       ↓
        ┌─── IMU 运动补偿 (可选) ───┐
        │  互补滤波 pitch/roll       │
        │  倾斜角余弦补偿 + 边缘惩罚  │
        │  运动积分超阈值 → 预警      │
        └──────────────┬──────────────┘
                       ↓
              屏幕坐标 → 3D 坐标转换
              intrinsic 校准 / FOV 估算
                       ↓
                    距离 / 面积结果
```

### 核心技术点

- **Camera2 API** 同时打开主摄 + dToF 深度摄像头
- 自动检测 `REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT` 能力
- DEPTH16 格式解析：0 = 无效，1-65533 = 深度(mm)，65534 = 过远，65535 = 过近
- Pinhole camera model + FOV 估算实现 3D 距离计算
- 相机内参 (fx, fy, cx, cy) 优先，FOV 近似兜底
- Kalman 滤波器线性 R 自适应，快速跟踪目标切换

### 项目结构

```
app/src/main/java/com/armeasure/app/
├── MainActivity.kt         # 主 Activity，UI 交互 + 测量流程
├── CameraController.kt     # Camera2 生命周期 + 深度流管理
├── TofSensorHelper.kt      # ToF 传感器检测、预热、滤波
├── MeasurementEngine.kt    # 3D 距离、面积、RANSAC 平面拟合
├── DistanceFilter.kt       # 自适应 Kalman 滤波（中值 + 尖峰抑制）
├── ImuFusionHelper.kt      # IMU 融合（互补滤波 + 运动检测）
├── MeasureOverlayView.kt   # 测量标记绘制 + 动画系统
└── AppConstants.kt         # 全局常量集中管理

app/src/test/java/com/armeasure/app/
├── DistanceFilterTest.kt   # 滤波器单元测试
├── MeasurementEngineTest.kt # 距离/面积计算测试
└── NewFeaturesTest.kt       # 常量合理性 + RANSAC 测试
```

## 📦 依赖

```
- androidx.core:core-ktx:1.15.0
- androidx.appcompat:appcompat:1.7.0
- com.google.android.material:material:1.12.0
- androidx.constraintlayout:constraintlayout:2.2.1
- kotlinx-coroutines-android:1.10.1
```

**零 Google Play Services / ARCore 依赖。**

## 🚀 使用

1. Android Studio 打开项目
2. Sync Gradle
3. 连接支持 dToF 的 Android 设备
4. Run

## ⚠️ 设备要求

- Android 8.0+ (API 26+)
- 支持 dToF 深度传感器的设备（推荐有独立深度摄像头）
- 如果设备没有独立 dToF 摄像头，会自动降级为 AF + ToF 模式
- 没有 ToF 的设备仅支持自动对焦估算（精度较低）

## 🧪 测试

```bash
./gradlew testDebugUnitTest
```

单元测试覆盖：
- `DistanceFilter` — Kalman 滤波、尖峰抑制、目标切换跟踪
- `MeasurementEngine` — 3D 距离、面积计算、FOV 边界安全、RANSAC
- `AppConstants` — 常量合理性校验（阈值顺序、范围约束）

## 📐 3D 距离计算

### 优先：相机内参投影

```kotlin
// Pinhole model: 像素 → 3D
wx = depth × (pixel_x - cx) / fx
wy = depth × (pixel_y - cy) / fy
wz = depth
distance = √(Δwx² + Δwy² + Δwz²)
```

### 兜底：FOV 近似

```kotlin
// 屏幕坐标归一化后投影 (带 tan 钳位防溢出)
x = depth × tan(clamp(nx) × FOV_x / 2)
y = depth × tan(clamp(ny) × FOV_y / 2)
z = depth
distance = √(Δx² + Δy² + Δz²)
```

### 深度融合

```
fused = (depth/σ_depth² + tof/σ_tof²) / (1/σ_depth² + 1/σ_tof²)
```

### 面积计算

- 有深度：Newell 方法计算 3D 多边形真实表面积
- 无深度：平面近似 shoelace 公式 × FOV 缩放
- RANSAC：50 次随机采样找最佳拟合平面，投影后计算面积

## 🔧 构建

```bash
# Debug
./gradlew assembleDebug

# Release (需要签名配置)
./gradlew assembleRelease
```

CI 自动构建：push 到 `main` 分支触发 lint → 单元测试 → APK 构建 → GitHub Release。

## 📄 License

MIT
