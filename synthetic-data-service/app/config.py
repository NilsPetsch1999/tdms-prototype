"""Application configuration."""

from functools import lru_cache
import os
from pathlib import Path

from pydantic import BaseModel
from dotenv import load_dotenv


PROJECT_ROOT = Path(__file__).resolve().parents[1]
load_dotenv(PROJECT_ROOT / ".env")


class Settings(BaseModel):
    """Runtime settings loaded from environment variables."""

    project_root: Path = PROJECT_ROOT
    app_name: str = os.getenv("APP_NAME", "Synthetic Data Service")
    data_path: str = os.getenv("DATA_PATH", "data/real_data.csv")
    model_path: str = os.getenv("MODEL_PATH", "models/ctgan_synthesizer.pkl")
    max_generate_rows: int = int(os.getenv("MAX_GENERATE_ROWS", "10000"))
    default_sample_rows: int = int(os.getenv("DEFAULT_SAMPLE_ROWS", "100"))
    random_state: int = int(os.getenv("RANDOM_STATE", "42"))
    database_url: str = os.getenv("DATABASE_URL", "mysql+pymysql://user:pass@localhost/db")

    def resolve_path(self, configured_path: str) -> Path:
        """Resolve relative paths from the service project root."""

        path = Path(configured_path)
        if path.is_absolute():
            return path
        return self.project_root / path


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Return cached application settings."""

    return Settings()
