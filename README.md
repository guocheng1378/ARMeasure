# 📱 ARMeasure — Android AR测距工具

纯 Camera2 API + dToF 深度传感器实现的专业级测距应用，**零 Google/ARCore 依赖**。

## ✨ 测量模式

| 模式 | 图标 | 说明 |
|------|------|------|
| **单点测距** | ⊙ | 点击屏幕任意位置，显示该点到手机的距离 |
| **两点测距** | ↔ | 点击两个点，计算3D空间直线距离 |
| **面积测量** | ◇ | 点击3个以上点围合区域，计算3D表面积 |
| **扫掠测距** | ~ | 滑动屏幕实时扫掠，显示距离变化曲线 |
| **高度测量** | ↕ | 点击底部和顶部，测量物体垂直高度 |
| **角度测量** | ∠ | 点击3个点(顶点+两端)，计算夹角 |
| **水平仪** | ◎ | 实时气泡水平仪，利用IMU检测设备倾斜 |

## 🎨 全新 UI 设计

- **Material 3 深色主题** — 沉浸式全屏体验
- **浮动信息卡片** — 顶部半透明圆角卡片显示模式和传感器状态
- **悬浮操作栏** — 底部药丸形模式切换栏
- **气泡水平仪** — 实时可视化倾斜角度
- **设置页面** — 单位、触觉反馈、精度显示等配置
- **专业历史记录** — RecyclerView 列表，支持导出/分享

## 🔧 高级特性

- **深度融合** — DEPTH16 + ToF 传感器逆方差加权融合
- **IMU 运动检测** — 陀螺仪 + 加速度计检测设备晃动，超阈值自动预警
- **自适应 Kalman 滤波** — 中值 → 尖峰抑制 → 自适应 Kalman，三阶段降噪
- **自适应邻域核** — 近距 3×3 / 中距 5×5 / 远距 7×7，根据深度自动调整
- **RANSAC 平面拟合** — 面积测量支持 RANSAC 最佳平面投影
- **双边缘检测** — 深度图边界检测 + 双边滤波保边降噪
- **自动对焦联动** — 对焦距离变化时自动重置滤波器
- **校准模式** — 已知距离反向校准，持久化保存
- **测量历史** — 最近 50 条记录，支持导出为文本
- **单位切换** — cm / inch / m 三种单位
- **截图保存** — 一键保存测量结果到相册并分享
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
         │  双边滤波 (空间 + 深度相似度加权)   │
         │  自适应邻域核 (3×3~7×7)            │
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
        │  动态方差评估 (变异系数)      │
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
            距离 / 面积 / 高度 / 角度
```

## 📂 项目结构

```
app/src/main/java/com/armeasure/app/
├── MainActivity.kt          # 主 Activity，UI 交互 + 测量流程
├── SettingsActivity.kt      # 设置页面
├── HistoryActivity.kt       # 历史记录页面 (RecyclerView)
├── CameraController.kt      # Camera2 生命周期 + 深度流管理
├── TofSensorHelper.kt       # ToF 传感器检测、预热、滤波
├── MeasurementEngine.kt     # 3D 距离、面积、RANSAC 平面拟合
├── HeightMeasureHelper.kt   # 高度测量 (垂直距离计算)
├── AngleMeasureHelper.kt    # 角度测量 (三点夹角计算)
├── DistanceFilter.kt        # 自适应 Kalman 滤波
├── ImuFusionHelper.kt       # IMU 融合 (互补滤波 + 运动检测)
├── MeasureOverlayView.kt    # 测量标记绘制 + 动画系统 + 水平仪
└── AppConstants.kt          # 全局常量集中管理
```

## 📦 依赖

```
- androidx.core:core-ktx:1.15.0
- androidx.appcompat:appcompat:1.7.0
- com.google.android.material:material:1.12.0
- androidx.constraintlayout:constraintlayout:2.2.1
- androidx.recyclerview:recyclerview:1.3.2
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
- 水平仪功能需要陀螺仪传感器

## 🧪 测试

```bash
./gradlew testDebugUnitTest
```

## 📄 License

MIT
