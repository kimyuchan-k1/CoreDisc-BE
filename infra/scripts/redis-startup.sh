#!/bin/bash
set -euo pipefail
exec > /var/log/coredisc-redis-startup.log 2>&1

echo "=== CoreDisc Redis Server Setup Start ==="

# ─── Docker 설치 ─────────────────────────────────────────
apt-get update
apt-get install -y ca-certificates curl gnupg
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
chmod a+r /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" | tee /etc/apt/sources.list.d/docker.list > /dev/null
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable docker
systemctl start docker

# ─── Redis 설정 ──────────────────────────────────────────
mkdir -p /opt/redis

cat > /opt/redis/redis.conf << 'EOF'
# 네트워크 — 내부 VPC에서 접근 허용
bind 0.0.0.0
protected-mode no
port 6379

# 메모리 — VM 메모리의 75% 할당
maxmemory 1536mb
maxmemory-policy allkeys-lru

# 성능
tcp-backlog 511
tcp-keepalive 300
timeout 0

# 지속성 — 캐시 용도이므로 RDB만 (최소한의 백업)
save 900 1
save 300 10

# 로깅
loglevel notice
logfile ""
EOF

# ─── Docker Compose ──────────────────────────────────────
cat > /opt/redis/docker-compose.yml << 'EOF'
services:
  redis:
    image: redis:7-alpine
    container_name: coredisc-redis
    restart: always
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data
      - ./redis.conf:/usr/local/etc/redis/redis.conf
    command: redis-server /usr/local/etc/redis/redis.conf

volumes:
  redis-data:
EOF

# ─── Redis 시작 ──────────────────────────────────────────
cd /opt/redis
docker compose up -d

# ─── 준비 대기 ───────────────────────────────────────────
echo "Waiting for Redis to be ready..."
for i in $(seq 1 15); do
  if docker exec coredisc-redis redis-cli ping 2>/dev/null | grep -q PONG; then
    echo "Redis is ready!"
    break
  fi
  echo "Waiting... ($i/15)"
  sleep 2
done

echo "=== CoreDisc Redis Server Setup Complete ==="
echo "Redis: 0.0.0.0:6379 (accessible from 10.0.1.0/24)"
