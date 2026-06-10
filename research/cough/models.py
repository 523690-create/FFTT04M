"""
4-class cough-diagnosis model scaffolds: a TDNN over MFCC sequences and an EAT-style audio
Transformer over mel patches. Classes: [bronchitis, pneumonia, croup, habit_cough].

Minimal, production-shaped starting points — not trained weights. See README.md / label_mapping.md.
"""
from __future__ import annotations
import torch
import torch.nn as nn

CLASSES = ["bronchitis", "pneumonia", "croup", "habit_cough"]


class TDNN4(nn.Module):
    """Time-Delay NN over MFCC sequences (B, T, F). Tier-2: classical, reproducible, deployable."""

    def __init__(self, in_feats: int = 40, num_classes: int = 4):
        super().__init__()
        self.t1 = nn.Conv1d(in_feats, 128, kernel_size=5, dilation=1)
        self.t2 = nn.Conv1d(128, 128, kernel_size=3, dilation=2)
        self.t3 = nn.Conv1d(128, 128, kernel_size=3, dilation=3)
        self.pool = nn.AdaptiveAvgPool1d(1)
        self.fc = nn.Linear(128, num_classes)

    def forward(self, x: torch.Tensor) -> torch.Tensor:  # x: (B, T, F)
        x = x.transpose(1, 2)                            # → (B, F, T)
        x = torch.relu(self.t1(x))
        x = torch.relu(self.t2(x))
        x = torch.relu(self.t3(x))
        x = self.pool(x).squeeze(-1)
        return self.fc(x)


class EAT4(nn.Module):
    """EAT-style audio Transformer over log-mel patches (B, P, M). Tier-3: highest ceiling.

    Captures croup stridor patterns and habit-cough repetitiveness via self-attention; pair with
    a device-invariance branch + augmentation for robustness across phones.
    """

    def __init__(self, patch_dim: int = 64, d_model: int = 256, nhead: int = 4,
                 layers: int = 4, num_classes: int = 4, max_patches: int = 512):
        super().__init__()
        self.embed = nn.Linear(patch_dim, d_model)
        self.pos = nn.Parameter(torch.randn(1, max_patches, d_model))
        enc = nn.TransformerEncoderLayer(d_model=d_model, nhead=nhead, batch_first=True)
        self.encoder = nn.TransformerEncoder(enc, num_layers=layers)
        self.fc = nn.Linear(d_model, num_classes)

    def forward(self, x: torch.Tensor) -> torch.Tensor:  # x: (B, P, M)
        x = self.embed(x) + self.pos[:, : x.size(1)]
        x = self.encoder(x)
        x = x.mean(dim=1)                                # global pool over patches
        return self.fc(x)


def class_weighted_ce(counts: dict[str, int]) -> nn.CrossEntropyLoss:
    """Confusion-aware loss for the croup/habit-cough imbalance: weight ∝ 1/sqrt(class count)."""
    w = torch.tensor([1.0 / max(1, counts.get(c, 1)) ** 0.5 for c in CLASSES], dtype=torch.float32)
    return nn.CrossEntropyLoss(weight=w / w.sum() * len(CLASSES))


if __name__ == "__main__":
    b = 2
    print("TDNN4:", TDNN4()(torch.randn(b, 120, 40)).shape)       # (2, 4)
    print("EAT4 :", EAT4()(torch.randn(b, 50, 64)).shape)          # (2, 4)
