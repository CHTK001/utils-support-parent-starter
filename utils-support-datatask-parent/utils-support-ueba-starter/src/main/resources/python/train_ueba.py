#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""UEBA 训练脚本（AutoEncoder + LSTM/GRU + Attention）。

用法:
    python train_ueba.py --config ueba-config-derived.yaml \
        --data access.csv --output ./models \
        --epochs 50 --batch-size 64 --learning-rate 0.001

依赖:
    pip install torch numpy pyyaml

输入 CSV 列:
    timestamp,ip,path,method,status,user_agent,response_time,response_size

产物（输出到 --output）:
    autoencoder_ip.onnx             IP 异常检测 AutoEncoder
    lstm_attention_behavior.onnx    行为序列分类 GRU+Attention
    ueba-config-derived.yaml        回写 scaler / vocab / threshold 后的配置
                                        （复制回 ueba-config.yaml 即实现训练与识别兼容）
"""
import argparse
import csv
import math
import random
from collections import defaultdict
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn
import yaml

# 训练数据缺席的字段默认值
DEFAULT_SENSITIVE = ("/admin", "/api/internal", "/config", "/backup", "/.env")
METHOD_CODES = {"GET": 0, "POST": 1, "PUT": 2, "DELETE": 3}
METHOD_CODE_MAX = 4.0


def load_config(path):
    """加载纯结构 YAML 配置。"""
    with open(path, "r", encoding="utf-8") as f:
        return yaml.safe_load(f)


def load_rows(csv_path):
    """读取访问日志 CSV，按时间排序。"""
    rows = []
    with open(csv_path, "r", encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        for r in reader:
            rows.append(r)
    rows.sort(key=lambda r: int(r.get("timestamp", 0)))
    return rows


# ---------- 原始指标（与 Java FeatureExtractor 保持一致） ----------

def shannon_entropy(paths):
    """路径分布 Shannon 熵（比特）。"""
    total = len(paths)
    if total == 0:
        return 0.0
    counts = defaultdict(int)
    for p in paths:
        counts[p if p else ""] += 1
    entropy = 0.0
    for c in counts.values():
        p = c / total
        entropy -= p * math.log(p, 2)
    return entropy


def raw_feature(name, seq, window_sec):
    """按特征名计算窗口内原始指标。"""
    if not seq:
        return 0.0
    n = len(seq)
    errs = sum(1 for ev in seq if int(ev.get("status", 0) or 0) >= 400)
    methods = {ev.get("method") or "UNKNOWN" for ev in seq}
    uas = {(ev.get("user_agent") or "UNKNOWN") for ev in seq}
    last_ts = int(seq[-1].get("timestamp", 0) or 0)
    hr = (last_ts / 3600000.0) % 24.0
    if name == "request_rate":
        return n / max(1.0, float(window_sec))
    if name == "path_entropy":
        return shannon_entropy([ev.get("path") for ev in seq])
    if name == "unique_paths_count":
        return len({ev.get("path") or "" for ev in seq})
    if name == "error_rate":
        return errs / n
    if name == "method_diversity":
        return len(methods) / n
    if name == "ua_diversity":
        return len(uas) / n
    if name == "avg_response_time":
        return sum(int(ev.get("response_time", 0) or 0) for ev in seq) / n
    if name == "max_response_time":
        return max(int(ev.get("response_time", 0) or 0) for ev in seq)
    if name == "avg_body_size":
        return sum(int(ev.get("response_size", 0) or 0) for ev in seq) / n
    if name == "time_of_day":
        return hr / 24.0
    return 0.0


def normalize(v, scaler):
    """按 scaler 归一化；无 scaler 时透传。"""
    if not scaler:
        return v
    if scaler.get("min") is not None and scaler.get("max") is not None:
        rng = scaler["max"] - scaler["min"]
        return 0.0 if rng <= 0 else (v - scaler["min"]) / rng
    if scaler.get("mean") is not None and scaler.get("std"):
        return (v - scaler["mean"]) / scaler["std"]
    return v


def fit_scalers(features_cfg, raw_vectors):
    """从各特征原始值拟合归一化参数。"""
    scalers = {}
    for idx, fdef in enumerate(features_cfg):
        name = fdef["name"]
        values = [v[idx] for v in raw_vectors]
        if not values:
            continue
        if fdef.get("normalize") == "MINMAX":
            scalers[name] = {"min": float(min(values)), "max": float(max(values))}
        elif fdef.get("normalize") == "ZSCORE":
            mean = float(np.mean(values))
            std = float(np.std(values))
            scalers[name] = {"mean": mean, "std": std if std > 0 else 1.0}
    return scalers


def build_ip_vectors(rows_by_ip, features_cfg, scalers):
    """按 IP 聚合窗口计算特征向量。"""
    vectors, ips = [], []
    for ip, seq in rows_by_ip.items():
        vec = []
        for fdef in features_cfg:
            raw = raw_feature(fdef["name"], seq, int(fdef.get("window", 60)))
            vec.append(normalize(raw, scalers.get(fdef["name"])))
        vectors.append(vec)
        ips.append(ip)
    return ips, vectors


# ---------- 续训 / checkpoint ----------

def save_checkpoint(model, opt, epoch, path):
    """保存训练 checkpoint（模型权重 + 优化器状态 + 轮数）。"""
    ckpt = {"model": model.state_dict(), "epoch": int(epoch)}
    if opt is not None:
        ckpt["optimizer"] = opt.state_dict()
    path.parent.mkdir(parents=True, exist_ok=True)
    torch.save(ckpt, path)


def load_checkpoint(model, opt, path, device):
    """加载 checkpoint 权重并返回起始轮数。"""
    ckpt = torch.load(path, map_location=device, weights_only=False)
    model.load_state_dict(ckpt["model"])
    if opt is not None and "optimizer" in ckpt:
        opt.load_state_dict(ckpt["optimizer"])
    return int(ckpt.get("epoch", 0))


def resolve_checkpoint(resume, model_file, stem):
    """从续训来源定位模型 checkpoint 文件（.pt）。"""
    if not resume:
        return None
    p = Path(resume)
    if p.is_file():
        return p if str(p).endswith(".pt") else None
    candidates = [
        p / "checkpoint" / (stem + "_latest.pt"),
        p / (stem + ".pt"),
        p / (Path(model_file).with_suffix(".pt")),
    ]
    for cand in candidates:
        if cand.is_file():
            return cand
    return None


# ---------- AutoEncoder ----------

class AutoEncoder(nn.Module):
    """降维-重建 AutoEncoder。"""

    def __init__(self, n, hidden):
        super().__init__()
        dims = list(hidden or [32, 16])
        layers = [n] + dims
        enc, dec = [], []
        for a, b in zip(layers[:-1], layers[1:]):
            enc += [nn.Linear(a, b), nn.ReLU()]
        for a, b in zip(reversed(layers[1:]), reversed(layers[:-1])):
            dec += [nn.Linear(a, b), nn.ReLU()]
        if dec:
            dec = dec[:-1]
        self.encoder = nn.Sequential(*enc)
        self.decoder = nn.Sequential(*dec)

    def forward(self, x):
        return self.decoder(self.encoder(x))


def train_autoencoder(features_cfg, raw_vectors, ae_cfg, epochs, batch_size, lr, device, out, resume=None):
    """训练并导出 AutoEncoder，返回阈值。"""
    xs = torch.tensor(np.asarray(raw_vectors, dtype=np.float32), device=device)
    n = xs.shape[1]
    model = AutoEncoder(n, ae_cfg.get("hiddenDims") or [32, 16]).to(device)
    opt = torch.optim.Adam(model.parameters(), lr=lr)
    criterion = nn.MSELoss()
    model.train()
    model_file = ae_cfg.get("modelFile") or "autoencoder_ip.onnx"
    stem = Path(model_file).stem
    start_epoch = 0
    ckpt_path = resolve_checkpoint(resume, model_file, stem)
    if ckpt_path is not None:
        start_epoch = load_checkpoint(model, opt, ckpt_path, device)
        print(f"[AE] resumed from {ckpt_path} at epoch {start_epoch}")
    for ep in range(start_epoch, start_epoch + epochs):
        perm = torch.randperm(xs.shape[0], device=device)
        total_loss = 0.0
        for s in range(0, xs.shape[0], batch_size):
            idx = perm[s:s + batch_size]
            batch = xs[idx]
            rec = model(batch)
            loss = criterion(rec, batch)
            opt.zero_grad()
            loss.backward()
            opt.step()
            total_loss += loss.item() * batch.shape[0]
        if (ep + 1) % 10 == 0 or ep + 1 == start_epoch + epochs:
            print(f"[AE] epoch {ep + 1}/{start_epoch + epochs} mse={total_loss / xs.shape[0]:.6f}")
    model.eval()
    with torch.no_grad():
        errors = ((model(xs) - xs) ** 2).mean(dim=1).cpu().numpy()
    threshold = float(ae_cfg.get("threshold") or 0.0)
    if threshold <= 0.0:
        threshold = float(np.quantile(errors, 0.99))
    ae_path = Path(out) / model_file
    torch.onnx.export(
        model, xs[:1], str(ae_path),
        input_names=["features"], output_names=["reconstruction"],
        dynamic_axes=None,
    )
    save_checkpoint(model, opt, start_epoch + epochs, Path(out) / "checkpoint" / (stem + "_latest.pt"))
    print(f"[AE] exported {ae_path} threshold={threshold:.6f}")
    return threshold


# ---------- 行为序列模型 ----------

class BehaviorNet(nn.Module):
    """Embedding + GRU + Attention 序列分类器。"""

    def __init__(self, vocab_size, embed_dim, hidden, num_layers, num_numeric, num_classes):
        super().__init__()
        self.emb = nn.Embedding(vocab_size, embed_dim, padding_idx=0)
        self.gru = nn.GRU(embed_dim + num_numeric, hidden, num_layers,
                          batch_first=True, dropout=0.1 if num_layers > 1 else 0.0)
        self.attn = nn.Linear(hidden, 1)
        self.fc = nn.Linear(hidden, num_classes)

    def forward(self, ids, numeric):
        emb = self.emb(ids)
        x = torch.cat([emb, numeric], dim=-1)
        out, _ = self.gru(x)
        w = torch.softmax(self.attn(out).squeeze(-1), dim=1)
        ctx = (out * w.unsqueeze(-1)).sum(dim=1)
        return self.fc(ctx)


def seq_numeric(ev, scalers):
    """事件级数值特征：[错误, 小时相位, 响应耗时归一化, 方法编码归一化]。"""
    status = int(ev.get("status", 0) or 0)
    is_error = 1.0 if status >= 400 else 0.0
    ts = int(ev.get("timestamp", 0) or 0)
    hour_phase = ((ts / 3600000.0) % 24.0) / 24.0
    resp = normalize(int(ev.get("response_time", 0) or 0),
                     scalers.get("avg_response_time"))
    method = METHOD_CODES.get((ev.get("method") or "").upper(), 4)
    return [is_error, hour_phase, resp, method / METHOD_CODE_MAX]


def rule_label(seq):
    """启发式标签：敏感路径→attack(2)，高错误率→suspicious(1)，否则 normal(0)。"""
    paths = [ev.get("path") or "" for ev in seq]
    for p in paths:
        if any(k in p for k in DEFAULT_SENSITIVE):
            return 2
    if len(seq) > 0:
        errs = sum(1 for ev in seq if int(ev.get("status", 0) or 0) >= 400)
        if errs / len(seq) > 0.2:
            return 1
    return 0


def build_sequences(rows_by_ip, lstm_cfg, scalers, vocab):
    """按实体生成右对齐的 LSTM 序列样本。"""
    l = int(lstm_cfg.get("windowSize", 20))
    m = int(lstm_cfg.get("numNumericFeatures", 4))
    sample_ids, sample_num, labels = [], [], []
    for seq in rows_by_ip.values():
        for i, ev in enumerate(seq):
            window = seq[max(0, i - l + 1):i + 1]
            ids_row = [0] * l
            num_row = [[0.0] * m for _ in range(l)]
            offset = l - len(window)
            for j, w_ev in enumerate(window):
                ids_row[offset + j] = vocab.get(w_ev.get("path") or "", 0)
                vals = seq_numeric(w_ev, scalers)
                for k in range(min(m, len(vals))):
                    num_row[offset + j][k] = vals[k]
            sample_ids.append(ids_row)
            sample_num.append(num_row)
            labels.append(rule_label(window))
    return np.asarray(sample_ids, dtype=np.int64), np.asarray(sample_num, dtype=np.float32), labels


def train_sequence_model(rows_by_ip, lstm_cfg, scalers, vocab,
                         epochs, batch_size, lr, device, out, resume=None):
    """训练并导出 GRU+Attention 分类器。"""
    ids_arr, num_arr, labels = build_sequences(rows_by_ip, lstm_cfg, scalers, vocab)
    if len(labels) == 0:
        raise SystemExit("无可用序列样本，请检查训练数据")
    l = ids_arr.shape[1]
    m = num_arr.shape[2]
    num_classes = int(lstm_cfg.get("numClasses", 3))
    vocab_size = max(len(vocab) + 1, 2)
    ys = torch.tensor(labels, dtype=torch.long, device=device)
    model = BehaviorNet(vocab_size, int(lstm_cfg.get("embeddingDim", 64)),
                        int(lstm_cfg.get("hiddenDim", 64)),
                        int(lstm_cfg.get("numLayers", 1)), m, num_classes).to(device)
    opt = torch.optim.Adam(model.parameters(), lr=lr)
    criterion = nn.CrossEntropyLoss()
    model.train()
    model_file = lstm_cfg.get("modelFile") or "lstm_attention_behavior.onnx"
    stem = Path(model_file).stem
    start_epoch = 0
    ckpt_path = resolve_checkpoint(resume, model_file, stem)
    if ckpt_path is not None:
        start_epoch = load_checkpoint(model, opt, ckpt_path, device)
        print(f"[SEQ] resumed from {ckpt_path} at epoch {start_epoch}")
    n = ids_arr.shape[0]
    for ep in range(start_epoch, start_epoch + epochs):
        perm = np.random.permutation(n)
        total_loss, correct = 0.0, 0
        for s in range(0, n, batch_size):
            idx = perm[s:s + batch_size]
            ids = torch.tensor(ids_arr[idx], dtype=torch.long, device=device)
            numeric = torch.tensor(num_arr[idx], dtype=torch.float32, device=device)
            yb = ys[idx]
            out_logits = model(ids, numeric)
            loss = criterion(out_logits, yb)
            opt.zero_grad()
            loss.backward()
            opt.step()
            total_loss += loss.item() * len(idx)
            correct += int((out_logits.argmax(dim=1) == yb).sum().item())
        if (ep + 1) % 10 == 0 or ep + 1 == start_epoch + epochs:
            print(f"[SEQ] epoch {ep + 1}/{start_epoch + epochs} loss={total_loss / n:.4f} acc={correct / n:.4f}")
    model.eval()
    seq_path = Path(out) / model_file
    dummy_ids = torch.zeros(1, l, dtype=torch.long, device=device)
    dummy_num = torch.zeros(1, l, m, dtype=torch.float32, device=device)
    torch.onnx.export(
        model, (dummy_ids, dummy_num), str(seq_path),
        input_names=["sequence_ids", "sequence_numeric"], output_names=["logits"],
        dynamic_axes=None,
    )
    save_checkpoint(model, opt, start_epoch + epochs, Path(out) / "checkpoint" / (stem + "_latest.pt"))
    print(f"[SEQ] exported {seq_path}")
    return vocab


def write_back(config, scalers, threshold, vocab, out_path):
    """将 scaler / vocab / threshold 回写到训练配置，供推理端读取。"""
    pre = config.setdefault("preprocessing", {})
    pre["scalers"] = dict(pre.get("scalers") or {})
    pre["scalers"].update(scalers)
    pre["vocab"] = dict(vocab)
    if threshold > 0:
        config.setdefault("autoEncoder", {})["threshold"] = threshold
    with open(out_path, "w", encoding="utf-8") as f:
        yaml.safe_dump(config, f, allow_unicode=True, sort_keys=False)
    print(f"[CFG] scaler/vocab/threshold written to {out_path}")


def main():
    parser = argparse.ArgumentParser(description="UEBA 训练脚本")
    parser.add_argument("--config", required=True, help="训练配置（派生后的 ueba-config.yaml）")
    parser.add_argument("--data", required=True, help="访问日志 CSV 路径")
    parser.add_argument("--output", required=True, help="输出目录")
    parser.add_argument("--epochs", type=int, default=50)
    parser.add_argument("--batch-size", type=int, default=64)
    parser.add_argument("--learning-rate", type=float, default=1e-3)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--device", default="cpu")
    parser.add_argument("--resume", default=None,
                        help="已存在模型目录或 .pt checkpoint 路径，从该模型继续训练（续训/微调）")
    args = parser.parse_args()

    random.seed(args.seed)
    np.random.seed(args.seed)
    torch.manual_seed(args.seed)
    device = torch.device(args.device if torch.cuda.is_available() or args.device == "cpu" else "cpu")

    out_dir = Path(args.output)
    out_dir.mkdir(parents=True, exist_ok=True)
    config = load_config(args.config)
    rows = load_rows(args.data)

    by_ip = defaultdict(list)
    for r in rows:
        by_ip[r.get("ip") or "unknown"].append(r)

    features_cfg = config.get("features") or []
    if not features_cfg:
        raise SystemExit("配置缺少 features，无法训练")
    print(f"[DATA] entities={len(by_ip)} rows={len(rows)} features={len(features_cfg)}")

    # 训练 AutoEncoder：先拟合 scaler（用原始特征值）
    raw_vectors = [[raw_feature(f["name"], seq, int(f.get("window", 60)))
                    for f in features_cfg] for seq in by_ip.values()]
    scalers = fit_scalers(features_cfg, raw_vectors)
    _, clean_vectors = build_ip_vectors(by_ip, features_cfg, scalers)
    if args.epochs > 0:
        threshold = train_autoencoder(features_cfg, clean_vectors,
                                      config.get("autoEncoder") or {},
                                      args.epochs, args.batch_size, args.learning_rate,
                                      device, out_dir, resume=args.resume)
    else:
        threshold = float((config.get("autoEncoder") or {}).get("threshold") or 0.0)

    # 构建词表（0 保留给填充位）
    vocab = {}
    for seq in by_ip.values():
        for ev in seq:
            p = ev.get("path") or ""
            if p and p not in vocab:
                vocab[p] = len(vocab) + 1
    if not vocab:
        print("[WARN] 未发现路径字段，词表为空")

    # 训练行为序列模型
    lstm_cfg = config.get("lstm") or {}
    train_sequence_model(by_ip, lstm_cfg, scalers, vocab,
                         args.epochs, args.batch_size, args.learning_rate,
                         device, out_dir, resume=args.resume)

    write_back(config, scalers, threshold, vocab, out_dir / "ueba-config-derived.yaml")
    print("[DONE] 请将 ueba-config-derived.yaml 复制回业务模块的 ueba-config.yaml")


if __name__ == "__main__":
    main()