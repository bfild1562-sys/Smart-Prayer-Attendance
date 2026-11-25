FROM python:3.12-slim
WORKDIR /app
COPY server.py ./server.py
RUN mkdir -p /data
ENV SPA_DATABASE=/data/central.sqlite3
EXPOSE 8080
CMD ["python", "server.py", "serve", "--host", "0.0.0.0", "--port", "8080"]
