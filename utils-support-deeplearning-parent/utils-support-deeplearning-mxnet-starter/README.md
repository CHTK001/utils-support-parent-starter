# utils-support-deeplearning-mxnet-starter

MXNet / DJL 推理引擎模块：图像分类

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-deeplearning-mxnet-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `MxnetModelRegistrar` | MXNet 模块模型集中注册器。 |
| `InceptionV3ActionTranslator` | MXNet InceptionV3 图像分类 Translator。 输入图像将被缩放至 299x299 并归一化，输出 ImageNet 类别概率分布。 |
| `Vgg16ActionTranslator` | MXNet VGG16 图像分类 Translator。 输入图像将被缩放至 224x224 并归一化，输出 ImageNet 类别概率分布。 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-deeplearning-mxnet-starter
├── utils-support-common-starter
├── utils-support-deeplearning-starter
```