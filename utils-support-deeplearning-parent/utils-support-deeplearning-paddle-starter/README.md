# utils-support-deeplearning-paddle-starter

PaddlePaddle / DJL 推理引擎模块：OCR、人脸、检测、分类、NLP

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-deeplearning-paddle-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `AnimalTranslator` | Paddle 动物分类 Translator。 |
| `DishesTranslator` | Paddle 菜品分类 Translator。 |
| `PedestrianTranslator` | Paddle 行人检测 Translator。 |
| `TrafficTranslator` | Paddle 交通目标检测 Translator（与车辆检测同预处理/后处理协议）。 |
| `VehicleTranslator` | Paddle 车辆检测 Translator。 |
| `FaceLandmarkTranslator` | Paddle 人脸关键点 Translator。 |
| `PaddleFaceDetectorTranslator` | Paddle 人脸检测 Translator。 |
| `PaddleFaceFeatureTranslator` | Paddle 人脸特征 Translator。 |
| `LacTranslator` | Paddle LAC 中文分词/词性标注 Translator。 输出 [token, label] 二维数组。 |
| `ReviewTranslator` | Paddle 评论情感/审核 Translator（与 Senta 同协议）。 |
| ... | 共 17 个类 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-deeplearning-paddle-starter
├── utils-support-common-starter
├── utils-support-deeplearning-starter
```