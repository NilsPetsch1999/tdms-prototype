from fastapi.testclient import TestClient

from app.config import get_settings
from app.main import app, service


client = TestClient(app)


def setup_function():
    service.synthesizer = None
    service.trained_on_rows = None
    service.trained_on_columns = None


def test_generate_rejects_invalid_num_rows():
    response = client.post("/generate", json={"num_rows": 0, "randomize_seed": False})

    assert response.status_code == 422


def test_generate_rejects_when_model_not_loaded():
    response = client.post("/generate", json={"num_rows": 5, "randomize_seed": False})

    assert response.status_code == 409
    assert response.json()["detail"] == "No trained model is available. Train or load a model first."


def test_generate_rejects_when_num_rows_exceeds_limit():
    settings = get_settings()

    response = client.post(
        "/generate",
        json={"num_rows": settings.max_generate_rows + 1, "randomize_seed": False},
    )

    assert response.status_code == 422
