"""Persistence helpers for trained synthesizers."""

from __future__ import annotations

import pickle
from pathlib import Path
from typing import Any


def model_exists(path: str) -> bool:
    return Path(path).is_file()


def save_synthesizer(synthesizer: Any, path: str) -> None:
    destination = Path(path)
    destination.parent.mkdir(parents=True, exist_ok=True)

    save_method = getattr(synthesizer, "save", None)
    if callable(save_method):
        save_method(filepath=str(destination))
        return

    with destination.open("wb") as file_obj:
        pickle.dump(synthesizer, file_obj)


def load_synthesizer(path: str) -> Any:
    source = Path(path)

    for loader in (_try_load_hma, _try_load_ctgan):
        synthesizer = loader(source)
        if synthesizer is not None:
            return synthesizer

    with source.open("rb") as file_obj:
        return pickle.load(file_obj)


def _try_load_hma(path: Path) -> Any | None:
    try:
        from sdv.multi_table import HMASynthesizer

        return HMASynthesizer.load(filepath=str(path))
    except Exception:
        return None


def _try_load_ctgan(path: Path) -> Any | None:
    try:
        from sdv.single_table import CTGANSynthesizer

        return CTGANSynthesizer.load(filepath=str(path))
    except Exception:
        return None
