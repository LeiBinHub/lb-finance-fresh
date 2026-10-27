# 国内拉不动时可：
#   docker compose build --build-arg PYTHON_IMAGE=docker.m.daocloud.io/library/python:3.12-slim
ARG PYTHON_IMAGE=python:3.12-slim
FROM ${PYTHON_IMAGE}
WORKDIR /app
ENV TZ=Asia/Shanghai PYTHONUNBUFFERED=1
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY listen.py .
CMD ["python", "listen.py"]
