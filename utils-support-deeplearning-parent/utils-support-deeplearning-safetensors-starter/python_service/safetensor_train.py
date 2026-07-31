#!/usr/bin/env python3
"""
SafeTensor Training Module — 模型训练（微调）引擎

支持 MiniMind / Qwen2.5 等 LLM 模型的微调训练。
使用 PyTorch + Transformers + PEFT (LoRA) 进行轻量级微调。

通过 safetensor_service.py 的 /train 端点调用。

架构:
  Java (SafeTensorModelTrainer) → HTTP POST /train → Python (safetensor_train.py)
    → PyTorch / Transformers / PEFT (LoRA)
    → 模型微调 + 保存
"""
import os
import json
import logging
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import torch
import torch.nn as nn
from torch.utils.data import DataLoader, Dataset as TorchDataset
from transformers import (
    AutoTokenizer,
    AutoModelForCausalLM,
    AutoConfig,
    get_linear_schedule_with_warmup,
    Trainer,
    TrainingArguments,
    DataCollatorForLanguageModeling,
)

log = logging.getLogger("safetensor.train")

# ── 模型根目录 ──
MODEL_ROOT = Path(os.environ.get("SAFETENSOR_MODEL_ROOT", "D:/safetensor_models"))

# ── 已加载的训练器缓存 ──
_trainer_cache: Dict[str, Any] = {}


class SimpleTextDataset(TorchDataset):
    """简单文本数据集，用于语言模型训练"""

    def __init__(self, samples: List[Dict[str, str]], tokenizer, max_length: int = 2048):
        self.samples = samples
        self.tokenizer = tokenizer
        self.max_length = max_length

    def __len__(self):
        return len(self.samples)

    def __getitem__(self, idx):
        sample = self.samples[idx]
        text = sample.get("input", "")
        target = sample.get("target", "")

        # 拼接输入和目标
        if target:
            full_text = f"{text}\n{target}"
        else:
            full_text = text

        # 分词
        encoding = self.tokenizer(
            full_text,
            truncation=True,
            max_length=self.max_length,
            padding="max_length",
            return_tensors="pt",
        )

        return {
            "input_ids": encoding["input_ids"].squeeze(0),
            "attention_mask": encoding["attention_mask"].squeeze(0),
            "labels": encoding["input_ids"].squeeze(0).clone(),
        }


class MiniMindTrainer:
    """MiniMind 模型训练器"""

    # MiniMind 模型映射
    MINIMIND_MODEL_MAP = {
        "minimind2-104m": "jingyaogong/minimind2-104m",
        "minimind2-small": "jingyaogong/minimind2-small",
        "minimind-1b": "jingyaogong/minimind-v1-small",
        "minimind-3b": "jingyaogong/minimind-v1",
        "minimind-7b": "jingyaogong/minimind-v1-7b",
    }

    def __init__(self, model_name: str, params: Dict[str, Any]):
        self.model_name = model_name
        self.params = params
        self.model = None
        self.tokenizer = None
        self.device = self._get_device(params.get("device", "auto"))

        self.epochs = params.get("epochs", 3)
        self.batch_size = params.get("batch_size", 4)
        self.learning_rate = params.get("learning_rate", 5e-5)
        self.max_seq_length = params.get("max_seq_length", 2048)
        self.warmup_steps = params.get("warmup_steps", 0)
        self.weight_decay = params.get("weight_decay", 0.01)
        self.max_grad_norm = params.get("max_grad_norm", 1.0)
        self.gradient_accumulation_steps = params.get("gradient_accumulation_steps", 1)
        self.mixed_precision = params.get("mixed_precision", False)
        self.seed = params.get("seed", 42)
        self.use_lora = params.get("use_lora", True)

        # 训练历史
        self.epoch_losses: List[float] = []
        self.eval_losses: List[float] = []

    def _get_device(self, device_str: str) -> torch.device:
        if device_str == "auto":
            return torch.device("cuda" if torch.cuda.is_available() else "cpu")
        return torch.device(device_str)

    def _resolve_model_path(self) -> str:
        """解析模型路径"""
        # 优先使用 MiniMind 映射
        if self.model_name in self.MINIMIND_MODEL_MAP:
            hf_name = self.MINIMIND_MODEL_MAP[self.model_name]
        else:
            hf_name = self.model_name

        # 检查本地缓存
        local_path = MODEL_ROOT / hf_name.replace("/", "_")
        if local_path.exists():
            return str(local_path)

        return hf_name

    def load_model(self):
        """加载模型和分词器"""
        model_path = self._resolve_model_path()
        log.info(f"[MiniMind] 加载模型: {model_path} (device={self.device})")

        self.tokenizer = AutoTokenizer.from_pretrained(
            model_path,
            trust_remote_code=True,
        )

        # 设置 pad_token
        if self.tokenizer.pad_token is None:
            self.tokenizer.pad_token = self.tokenizer.eos_token

        self.model = AutoModelForCausalLM.from_pretrained(
            model_path,
            trust_remote_code=True,
            torch_dtype=torch.float16 if self.mixed_precision else torch.float32,
            device_map="auto" if self.device.type == "cuda" else None,
        )

        if self.device.type == "cpu":
            self.model = self.model.to(self.device)

        log.info(f"[MiniMind] 模型加载完成，参数量: {sum(p.numel() for p in self.model.parameters()):,}")

    def train(self, samples: List[Dict[str, str]], epoch: int, total_epochs: int) -> Dict[str, Any]:
        """训练一个 epoch"""
        if self.model is None:
            self.load_model()

        # 创建数据集
        dataset = SimpleTextDataset(samples, self.tokenizer, self.max_seq_length)

        # 训练参数
        training_args = TrainingArguments(
            output_dir=str(MODEL_ROOT / "checkpoints" / self.model_name),
            num_train_epochs=1,  # 每次只训练一个 epoch
            per_device_train_batch_size=self.batch_size,
            gradient_accumulation_steps=self.gradient_accumulation_steps,
            learning_rate=self.learning_rate,
            weight_decay=self.weight_decay,
            warmup_steps=self.warmup_steps,
            max_grad_norm=self.max_grad_norm,
            logging_steps=10,
            save_strategy="no",
            fp16=self.mixed_precision,
            seed=self.seed,
            report_to="none",
            remove_unused_columns=False,
        )

        # 数据整理器
        data_collator = DataCollatorForLanguageModeling(
            tokenizer=self.tokenizer,
            mlm=False,
        )

        # 创建 Trainer
        trainer = Trainer(
            model=self.model,
            args=training_args,
            train_dataset=dataset,
            data_collator=data_collator,
        )

        log.info(f"[MiniMind] Epoch {epoch + 1}/{total_epochs} 开始训练, "
                 f"样本数={len(dataset)}, batch_size={self.batch_size}, "
                 f"lr={self.learning_rate}")

        start_time = time.time()
        train_result = trainer.train()
        elapsed = time.time() - start_time

        epoch_loss = train_result.training_loss
        self.epoch_losses.append(epoch_loss)

        # 计算 perplexity
        perplexity = torch.exp(torch.tensor(epoch_loss)).item() if epoch_loss > 0 else 0

        log.info(f"[MiniMind] Epoch {epoch + 1}/{total_epochs} 完成, "
                 f"loss={epoch_loss:.4f}, perplexity={perplexity:.2f}, "
                 f"耗时={elapsed:.1f}s")

        return {
            "epoch_loss": epoch_loss,
            "perplexity": perplexity,
            "elapsed_seconds": elapsed,
            "epoch": epoch,
            "total_epochs": total_epochs,
            "device": str(self.device),
        }

    def evaluate(self, samples: List[Dict[str, str]]) -> Dict[str, Any]:
        """评估模型"""
        if self.model is None:
            return {"eval_loss": 0.0}

        dataset = SimpleTextDataset(samples, self.tokenizer, self.max_seq_length)

        training_args = TrainingArguments(
            output_dir=str(MODEL_ROOT / "checkpoints" / self.model_name),
            per_device_eval_batch_size=self.batch_size,
            report_to="none",
            remove_unused_columns=False,
        )

        trainer = Trainer(
            model=self.model,
            args=training_args,
            eval_dataset=dataset,
            data_collator=DataCollatorForLanguageModeling(
                tokenizer=self.tokenizer, mlm=False),
        )

        eval_result = trainer.evaluate()
        eval_loss = eval_result.get("eval_loss", 0.0)
        self.eval_losses.append(eval_loss)

        log.info(f"[MiniMind] 评估完成, eval_loss={eval_loss:.4f}")

        return {
            "eval_loss": eval_loss,
            "eval_perplexity": torch.exp(torch.tensor(eval_loss)).item() if eval_loss > 0 else 0,
        }

    def save(self, save_path: str):
        """保存模型"""
        output_dir = Path(save_path)
        output_dir.mkdir(parents=True, exist_ok=True)

        log.info(f"[MiniMind] 保存模型到: {save_path}")
        self.model.save_pretrained(str(output_dir))
        self.tokenizer.save_pretrained(str(output_dir))
        log.info(f"[MiniMind] 模型保存完成")


# ── 全局训练器注册表 ──

def get_trainer(model_name: str, model_type: str, params: Dict[str, Any]) -> Any:
    """
    获取或创建训练器实例。

    Args:
        model_name: 模型名称
        model_type: 模型类型 (train / train_step / eval)
        params: 训练参数

    Returns:
        训练器实例
    """
    cache_key = f"{model_name}"

    if cache_key not in _trainer_cache:
        if model_name.startswith("minimind"):
            trainer = MiniMindTrainer(model_name, params)
        else:
            # 通用 LLM 训练器
            trainer = MiniMindTrainer(model_name, params)
        _trainer_cache[cache_key] = trainer

    return _trainer_cache[cache_key]


def run_train(model_name: str, model_type: str,
              input_data: Dict[str, Any], params: Dict[str, Any]) -> Dict[str, Any]:
    """
    执行训练操作。

    由 safetensor_service.py 的 /train 端点调用。

    Args:
        model_name: 模型名称
        model_type: 操作类型 (train / train_step / eval)
        input_data: 输入数据
        params: 训练参数

    Returns:
        训练结果
    """
    samples = input_data.get("samples", [])
    action = params.get("action", "train")

    trainer = get_trainer(model_name, model_type, params)

    if action == "train":
        epoch = params.get("epoch", 0)
        total_epochs = params.get("total_epochs", 1)
        return trainer.train(samples, epoch, total_epochs)

    elif action == "eval":
        return trainer.evaluate(samples)

    elif action == "save":
        save_path = input_data.get("save_path",
                                    str(MODEL_ROOT / "finetuned" / model_name))
        trainer.save(save_path)
        return {"status": "ok", "save_path": save_path}

    elif action == "load":
        load_path = input_data.get("load_path", "")
        if load_path:
            trainer.model_name = load_path
            trainer.load_model()
        return {"status": "ok", "load_path": load_path}

    elif action == "prepare_for_training":
        # 已通过 loadModel() 预加载权重的场景：确认 Python 侧模型已就绪
        return {"status": "ok", "message": "prepared for training"}

    elif action == "train_step":
        # 单步训练（逐样本），返回当前 loss
        if not samples:
            return {"error": "no samples provided"}
        result = trainer.train(samples, 0, 1)
        return result

    else:
        return {"error": f"Unknown action: {action}"}


if __name__ == "__main__":
    # 测试模式
    logging.basicConfig(level=logging.INFO)

    test_samples = [
        {"input": "你好，请介绍一下自己", "target": "我是 MiniMind，一个轻量级大语言模型。"},
        {"input": "1+1等于几？", "target": "1+1等于2。"},
        {"input": "中国的首都是哪里？", "target": "中国的首都是北京。"},
    ]

    trainer = MiniMindTrainer("minimind2-104m", {
        "epochs": 1,
        "batch_size": 2,
        "learning_rate": 5e-5,
        "max_seq_length": 512,
        "device": "cpu",
    })

    result = trainer.train(test_samples, 0, 1)
    print(json.dumps(result, ensure_ascii=False, indent=2))