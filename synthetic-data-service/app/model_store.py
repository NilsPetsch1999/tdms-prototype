"""Persistence helpers for trained synthesizers."""

from __future__ import annotations

import pickle
from pathlib import Path
from typing import Any


def model_exists(path: str) -> bool:
    """Return whether a serialized model file exists."""

    return Path(path).is_file()


def save_synthesizer(synthesizer: Any, path: str) -> None:
    """Persist a synthesizer using SDV helpers when available."""

    destination = Path(path)
    destination.parent.mkdir(parents=True, exist_ok=True)

    save_method = getattr(synthesizer, "save", None)
    if callable(save_method):
        save_method(filepath=str(destination))
        return

    with destination.open("wb") as file_obj:
        pickle.dump(synthesizer, file_obj)


def load_synthesizer(path: str) -> Any:
    """Load a synthesizer using SDV helpers when available."""

    source = Path(path)

    try:
        from sdv.single_table import CTGANSynthesizer

        load_method = getattr(CTGANSynthesizer, "load", None)
        if callable(load_method):
            return load_method(filepath=str(source))
    except ImportError:
        pass

    with source.open("rb") as file_obj:
        return pickle.load(file_obj)
