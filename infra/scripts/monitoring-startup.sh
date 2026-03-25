#!/bin/bash
set -euo pipefail
exec > /var/log/coredisc-monitoring-startup.log 2>&1

echo "=== CoreDisc Monitoring Server Setup Start ==="

# ─── Docker 설치 ─────────────────────────────────────────
apt-get update
apt-get install -y ca-certificates curl gnupg jq
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
chmod a+r /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" | tee /etc/apt/sources.list.d/docker.list > /dev/null
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable docker
systemctl start docker

# ─── k6 설치 ─────────────────────────────────────────────
gpg -k
gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg \
    --keyserver hkp://keyserver.ubuntu.com:80 \
    --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" \
    | tee /etc/apt/sources.list.d/k6.list
apt-get update
apt-get install -y k6

# ─── 디렉토리 구조 생성 ──────────────────────────────────
mkdir -p /opt/monitoring/{prometheus,grafana/provisioning/datasources,grafana/provisioning/dashboards,grafana/dashboards,k6}

# ─── Prometheus 설정 ─────────────────────────────────────
cat > /opt/monitoring/prometheus/prometheus.yml << 'EOF'
global:
  scrape_interval: 10s
  evaluation_interval: 10s

scrape_configs:
  - job_name: 'coredisc-app'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['${app_internal_ip}:8080']
        labels:
          application: 'coredisc'

  - job_name: 'prometheus'
    static_configs:
      - targets: ['localhost:9090']
EOF

# ─── Grafana Datasource 자동 프로비저닝 ──────────────────
cat > /opt/monitoring/grafana/provisioning/datasources/datasources.yml << 'DSEOF'
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
    editable: true

  - name: InfluxDB-k6
    type: influxdb
    access: proxy
    url: http://influxdb:8086
    database: k6
    editable: true
DSEOF

# ─── Grafana Dashboard 자동 프로비저닝 ───────────────────
cat > /opt/monitoring/grafana/provisioning/dashboards/dashboards.yml << 'DBEOF'
apiVersion: 1
providers:
  - name: 'CoreDisc'
    orgId: 1
    folder: 'CoreDisc'
    type: file
    disableDeletion: false
    updateIntervalSeconds: 30
    allowUiUpdates: true
    options:
      path: /var/lib/grafana/dashboards
      foldersFromFilesStructure: false
DBEOF

# ─── Grafana 대시보드 다운로드 ───────────────────────────
# JVM Micrometer 대시보드
curl -sL "https://grafana.com/api/dashboards/4701/revisions/latest/download" \
    -o /opt/monitoring/grafana/dashboards/jvm-micrometer.json 2>/dev/null || true

# k6 Load Testing 대시보드
curl -sL "https://grafana.com/api/dashboards/2587/revisions/latest/download" \
    -o /opt/monitoring/grafana/dashboards/k6-load-testing.json 2>/dev/null || true

# 대시보드 ID null 처리 (프로비저닝 충돌 방지)
for f in /opt/monitoring/grafana/dashboards/*.json; do
  if [ -f "$f" ]; then
    jq '.id = null' "$f" > "$f.tmp" && mv "$f.tmp" "$f" 2>/dev/null || true
  fi
done

# ─── Docker Compose (Prometheus + Grafana + InfluxDB) ────
cat > /opt/monitoring/docker-compose.yml << 'EOF'
services:
  prometheus:
    image: prom/prometheus:latest
    container_name: prometheus
    restart: always
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml
      - prometheus-data:/prometheus
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.retention.time=15d'

  grafana:
    image: grafana/grafana:latest
    container_name: grafana
    restart: always
    ports:
      - "3000:3000"
    environment:
      GF_SECURITY_ADMIN_PASSWORD: admin
      GF_USERS_ALLOW_SIGN_UP: 'false'
    volumes:
      - grafana-data:/var/lib/grafana
      - ./grafana/provisioning:/etc/grafana/provisioning
      - ./grafana/dashboards:/var/lib/grafana/dashboards
    depends_on:
      - prometheus

  influxdb:
    image: influxdb:1.8
    container_name: influxdb
    restart: always
    ports:
      - "8086:8086"
    environment:
      INFLUXDB_DB: k6
      INFLUXDB_HTTP_MAX_BODY_SIZE: '0'
    volumes:
      - influxdb-data:/var/lib/influxdb

volumes:
  prometheus-data:
  grafana-data:
  influxdb-data:
EOF

# ─── 모니터링 스택 시작 ──────────────────────────────────
cd /opt/monitoring
docker compose up -d

echo "=== CoreDisc Monitoring Server Setup Complete ==="
echo "Grafana:    http://$(curl -s ifconfig.me):3000 (admin/admin)"
echo "Prometheus: http://$(curl -s ifconfig.me):9090"
echo "InfluxDB:   localhost:8086 (k6 output)"