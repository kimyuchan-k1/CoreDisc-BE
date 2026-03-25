#!/bin/bash
set -euo pipefail
exec > /var/log/coredisc-startup.log 2>&1

echo "=== CoreDisc App Server Setup Start ==="

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

# ─── Java 17 설치 ────────────────────────────────────────
apt-get install -y openjdk-17-jdk-headless

# ─── 디렉토리 생성 ───────────────────────────────────────
mkdir -p /opt/coredisc/seed

# ─── Docker Compose (MySQL + Redis) ─────────────────────
cat > /opt/coredisc/docker-compose.yml << 'EOF'
services:
  mysql:
    image: mysql:8.0
    container_name: coredisc-mysql
    restart: always
    environment:
      MYSQL_ROOT_PASSWORD: ${db_password}
      MYSQL_DATABASE: ${db_name}
    ports:
      - "3306:3306"
    volumes:
      - mysql-data:/var/lib/mysql
    command: >
      --character-set-server=utf8mb4
      --collation-server=utf8mb4_unicode_ci
      --max-connections=200
      --innodb-buffer-pool-size=1073741824

  redis:
    image: redis:7-alpine
    container_name: coredisc-redis
    restart: always
    ports:
      - "6379:6379"

volumes:
  mysql-data:
EOF

# ─── 인프라 시작 ─────────────────────────────────────────
cd /opt/coredisc
docker compose up -d

# ─── MySQL 준비 대기 ─────────────────────────────────────
echo "Waiting for MySQL to be ready..."
for i in $(seq 1 30); do
  if docker exec coredisc-mysql mysqladmin ping -uroot -p'${db_password}' --silent 2>/dev/null; then
    echo "MySQL is ready!"
    break
  fi
  echo "Waiting... ($i/30)"
  sleep 2
done

echo "=== CoreDisc App Server Setup Complete ==="
echo "MySQL: localhost:3306"
echo "Redis: localhost:6379"
echo "Next: deploy app JAR and load seed data"