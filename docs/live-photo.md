# Live Photo 支持范围

当前支持微博 `pic_infos` 提供的静态封面和独立短视频：宫格标识、大图长按播放、按钮循环播放，以及保存到系统相册。

## 保存格式

所有设备目前使用同一条保存路径：标准 JPEG Motion Photo，并保留旧版 MicroVideo 属性供旧阅读器使用。文件名以 `_MP.jpg` 结尾。JPEG 封面保留图像编码和 EXIF；其他系统可解码的封面转为 JPEG（质量 95）。视频不重新编码，保存前校验 MP4 容器边界。

格式依据：[Android Motion Photo format 1.0](https://developer.android.com/media/platform/motion-photo-format)。符合容器格式不等于任意系统相册都已验证兼容；相册版本和视频编码也会影响识别、播放及编辑。

## 厂商适配状态

目前没有按品牌选择私有格式的导出器，也没有集成厂商动态照片 SDK。旧代码中的 `LivePhotoDeviceProfile` 未被保存逻辑使用，已移除；原先“全厂商兼容”的注释也已纠正。

| 设备/相册 | 当前导出路径 | 验证状态 |
| --- | --- | --- |
| Google / 支持标准 Motion Photo 的阅读器 | 标准 JPEG + XMP + MP4 | 自动化验证文件结构；尚未真机验证 |
| 小米 / Redmi | 同一标准格式 | 尚未接入小米动态照片 SDK，未保证原生编辑能力 |
| OPPO / OnePlus / realme、vivo / iQOO、华为 / 荣耀、三星 | 同一标准格式 | 尚未验证各相册/系统版本的识别与编辑能力 |

小米官方提供[动态照片 SDK 的合成能力及版本要求](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2000)。后续接入厂商导出器时，需要同时检查系统/相册版本，并用对应设备验证识别、播放、封面方向、编辑和分享后的效果。

## 解析与测试

`MotionPhotoParser` 当前用于 JPEG 内嵌视频检测/提取及视频容器校验，尚未接入本地图片导入。解析器不承诺读取 HEIC/AVIF 或厂商私有尾部格式。兜底只接受紧跟 JPEG EOI 的结构完整 MP4，避免扫描图片元数据时误识别 `ftyp`。

自动化测试覆盖 XML 命名空间、目录属性、视频提取、重复合成、EXIF 保留、非 JPEG 转换、截断视频、元数据中的伪 MP4，以及向源文件自身提取时的保护。MP4 测试夹具验证容器结构，不替代真实编码视频和真机相册验证。长按松手、切页、重复播放淡入和后台暂停仍需设备交互验证。
