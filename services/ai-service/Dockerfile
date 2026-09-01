# ai-service. The trained model is built during the image build, so the container is
# self-contained and does not train on first request.

FROM python:3.11-slim AS build
COPY --from=ghcr.io/astral-sh/uv:latest /uv /usr/local/bin/uv
WORKDIR /build
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy

# Dependencies first, so a code change does not reinstall them.
COPY pyproject.toml ./
RUN uv venv /opt/venv && VIRTUAL_ENV=/opt/venv uv pip install -r pyproject.toml

COPY app app
COPY data data
COPY training training
RUN VIRTUAL_ENV=/opt/venv PATH="/opt/venv/bin:$PATH" python -m training.train_noshow

FROM python:3.11-slim
LABEL org.opencontainers.image.title="medsync-ai-service"
RUN useradd --system --uid 10001 --create-home medsync
WORKDIR /app
COPY --from=build /opt/venv /opt/venv
COPY --from=build --chown=medsync:medsync /build/app app
COPY --from=build --chown=medsync:medsync /build/data data
COPY --from=build --chown=medsync:medsync /build/models models
USER 10001

ENV PATH="/opt/venv/bin:$PATH" PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
EXPOSE 8000
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=5 \
    CMD ["python", "-c", "import urllib.request,sys; sys.exit(0 if b'UP' in urllib.request.urlopen('http://127.0.0.1:8000/actuator/health', timeout=2).read() else 1)"]
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
