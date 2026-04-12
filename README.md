# 📱 ARMeasure - Android AR测距 App

纯 Camera2 API + dToF 深度传感器实现的测距应用，**零 Google/ARCore 依赖**。

## ✨ 功能

| 模式 | 说明 |
|------|------|
| **单点测距** | 点击屏幕任意位置，显示该点到手机的距离 |
| **两点测距** | 点击两个点，计算3D空间直线距离 |
| **面积测量** | 点击3个以上点围合区域，计算面积 |

## 🔧 技术原理

```
手机摄像头 (Camera2 API)
├── 主摄 → TextureView 相机预览
└── dToF 深度摄像头 → ImageReader (DEPTH16 格式)
                          ↓
                 每帧输出深度图 (mm 精度)
                          ↓
                 屏幕坐标映射 → 3×3 区域均值 → 距离值 (m)
```

### 核心技术点

- **Camera2 API** 同时打开主摄 + dToF 深度摄像头
- 自动检测 `REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT` 能力
- DEPTH16 格式解析：0 = 无效，1-65533 = 深度(mm)，65534 = 过远，65535 = 过近
- Pinhole camera model + FOV 估算实现 3D 距离计算
- 3×3 深度核均值抗噪

## 📦 依赖

```
- androidx.core:core-ktx:1.12.0
- androidx.appcompat:appcompat:1.6.1
- com.google.android.material:material:1.11.0
- androidx.constraintlayout:constraintlayout:2.1.4
- kotlinx-coroutines-android:1.7.3
```

**零 Google Play Services / ARCore 依赖。**

## 🚀 使用

1. Android Studio 打开项目
2. Sync Gradle
3. 连接支持 dToF 的 Android 设备（如小米 17 Pro Max）
4. Run

## ⚠️ 设备要求

- Android 8.0+ (API 26+)
- 支持 dToF 深度传感器的设备
- 如果设备没有独立 dToF 摄像头，界面会提示「主摄 (无ToF)」

## 📐 3D 距离计算

两点间距离通过深度值 + 屏幕坐标 + 相机 FOV 估算：

```
x = depth × tan(normalized_x × FOV_x / 2)
y = depth × tan(normalized_y × FOV_y / 2)
z = depth
distance = √(Δx² + Δy² + Δz²)
```

## 📄 License

MIT
