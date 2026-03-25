SHELL := /bin/bash
ZONE  := asia-northeast3-a

.PHONY: help prereq init plan apply destroy \
        ssh-app ssh-redis ssh-mon status \
        deploy deploy-stop \
        seed-generate seed-upload seed-load seed \
        seed-v2 \
        k6-upload test test-local \
        test-v2 test-hotkey test-circle

# ═══════════════════════════════════════════════════════════
# 도움말
# ═══════════════════════════════════════════════════════════
help:
	@echo ""
	@echo "  CoreDisc GCP Performance Test Infrastructure"
	@echo "  ─────────────────────────────────────────────"
	@echo ""
	@echo "  1. 사전 준비"
	@echo "     make prereq          사전 조건 확인"
	@echo "     make init            Terraform 초기화"
	@echo ""
	@echo "  2. 인프라 생성/삭제"
	@echo "     make apply           GCP 인프라 생성 (약 2분)"
	@echo "     make destroy         GCP 인프라 삭제"
	@echo ""
	@echo "  3. 앱 배포"
	@echo "     make deploy          JAR 빌드 → 업로드 → 실행"
	@echo "     make deploy-stop     앱 프로세스 종료"
	@echo ""
	@echo "  4. 시드 데이터"
	@echo "     make seed-generate   시드 데이터 생성 (로컬)"
	@echo "     make seed            시드 업로드 + DB 로드"
	@echo "     make seed-v2         v2 시드 (power-law) 생성 + DB 로드"
	@echo ""
	@echo "  5. 부하 테스트"
	@echo "     make test            모니터링 VM에서 k6 실행"
	@echo "     make test-v2         v2 부하 테스트 (read 80% + write 20%)"
	@echo "     make test-hotkey     Hot key 스트레스 테스트"
	@echo "     make test-circle     Circle 가시성 검증 테스트"
	@echo "     make test-local      로컬에서 GCP 서버로 k6 실행"
	@echo ""
	@echo "  6. 접속"
	@echo "     make ssh-app         App 서버 SSH"
	@echo "     make ssh-redis       Redis 서버 SSH"
	@echo "     make ssh-mon         모니터링 서버 SSH"
	@echo "     make status          IP/URL 확인"
	@echo ""

# ═══════════════════════════════════════════════════════════
# 사전 준비
# ═══════════════════════════════════════════════════════════
prereq:
	@echo "Checking prerequisites..."
	@which gcloud > /dev/null 2>&1 || (echo "gcloud CLI 필요: brew install google-cloud-sdk" && exit 1)
	@which terraform > /dev/null 2>&1 || (echo "Terraform 필요: brew install terraform" && exit 1)
	@which k6 > /dev/null 2>&1 || echo "[optional] k6 로컬 실행: brew install k6"
	@echo "All prerequisites met!"

# ═══════════════════════════════════════════════════════════
# Terraform
# ═══════════════════════════════════════════════════════════
init:
	terraform init

plan:
	terraform plan

apply:
	terraform apply -auto-approve
	@echo ""
	@terraform output quick_commands

destroy:
	terraform destroy

# ═══════════════════════════════════════════════════════════
# SSH & 상태
# ═══════════════════════════════════════════════════════════
ssh-app:
	gcloud compute ssh coredisc-app --zone=$(ZONE)

ssh-redis:
	gcloud compute ssh coredisc-redis --zone=$(ZONE)

ssh-mon:
	gcloud compute ssh coredisc-monitoring --zone=$(ZONE)

status:
	@terraform output quick_commands

# ═══════════════════════════════════════════════════════════
# 앱 배포
# ═══════════════════════════════════════════════════════════
deploy:
	@echo "=== Building JAR ==="
	cd .. && ./gradlew bootJar -x test
	@echo ""
	@echo "=== Uploading JAR to GCP ==="
	gcloud compute scp ../build/libs/coredisc-0.0.1-SNAPSHOT.jar \
		coredisc-app:~/app.jar --zone=$(ZONE)
	gcloud compute ssh coredisc-app --zone=$(ZONE) --command='\
		sudo mv ~/app.jar /opt/coredisc/app.jar'
	@echo ""
	@echo "=== Starting App ==="
	gcloud compute ssh coredisc-app --zone=$(ZONE) --command='\
		sudo pkill -f "java.*app.jar" || true && \
		sleep 1 && \
		cd /opt/coredisc && \
		sudo nohup java -Xmx4g -jar app.jar \
			--spring.profiles.active=local,cloud \
			> app.log 2>&1 & \
		echo "App starting... check with: tail -f /opt/coredisc/app.log"'
	@echo ""
	@echo "App deployed! URL: http://$$(terraform output -raw app_external_ip):8080"

deploy-stop:
	gcloud compute ssh coredisc-app --zone=$(ZONE) --command='\
		sudo pkill -f "java.*app.jar" || echo "No app process found"'

# ═══════════════════════════════════════════════════════════
# 시드 데이터 (VM에서 직접 생성 + 로드)
# ═══════════════════════════════════════════════════════════
seed:
	@echo "=== [1/3] Uploading seed script to VM ==="
	gcloud compute scp ../k6/generate_seed_data.py \
		coredisc-app:~/generate_seed_data.py --zone=$(ZONE)
	@echo ""
	@echo "=== [2/3] Generating seed data on VM ==="
	gcloud compute ssh coredisc-app --zone=$(ZONE) --command='\
		python3 ~/generate_seed_data.py'
	@echo ""
	@echo "=== [3/3] Loading seed data into MySQL ==="
	gcloud compute ssh coredisc-app --zone=$(ZONE) --command='\
		cd /tmp/coredisc-seed && \
		for f in $$(ls *.sql | sort); do \
			echo "Loading $$f..."; \
			sudo docker exec -i coredisc-mysql mysql -uroot -pcoredisc2024 coredisc < "$$f"; \
		done && \
		echo "=== Seed data loaded! ==="'

seed-local:
	cd .. && python3 k6/generate_seed_data.py

# v2 시드 데이터 (power-law 분포)
seed-v2:
	@echo "=== [1/3] Uploading v2 seed script to VM ==="
	gcloud compute scp ../k6/generate_seed_data_v2.py \
		coredisc-app:~/generate_seed_data_v2.py --zone=$(ZONE)
	@echo ""
	@echo "=== [2/3] Generating v2 seed data on VM ==="
	gcloud compute ssh coredisc-app --zone=$(ZONE) --command='\
		python3 ~/generate_seed_data_v2.py'
	@echo ""
	@echo "=== [3/3] Loading v2 seed data into MySQL ==="
	gcloud compute ssh coredisc-app --zone=$(ZONE) --command='\
		cd /tmp/coredisc-seed-v2 && \
		for f in $$(ls *.sql | sort); do \
			echo "Loading $$f..."; \
			sudo docker exec -i coredisc-mysql mysql -uroot -pcoredisc2024 coredisc < "$$f"; \
		done && \
		echo "=== v2 seed data loaded! ==="'
	@echo ""
	@echo "=== Downloading manifest.json ==="
	gcloud compute scp coredisc-app:/tmp/coredisc-seed-v2/manifest.json \
		../k6/manifest.json --zone=$(ZONE)
	@echo "Manifest saved to k6/manifest.json"

# ═══════════════════════════════════════════════════════════
# 부하 테스트
# ═══════════════════════════════════════════════════════════
k6-upload:
	@echo "=== Uploading k6 scripts ==="
	gcloud compute scp ../k6/load-test.js ../k6/smoke-test.js \
		coredisc-monitoring:/opt/monitoring/k6/ --zone=$(ZONE)

test-smoke: k6-upload
	@echo "=== Running smoke test ==="
	gcloud compute ssh coredisc-monitoring --zone=$(ZONE) --command='\
		cd /opt/monitoring/k6 && \
		BASE_URL=http://10.0.1.10:8080 k6 run smoke-test.js'

test: k6-upload
	@echo "=== Running load test (results -> InfluxDB -> Grafana) ==="
	gcloud compute ssh coredisc-monitoring --zone=$(ZONE) --command='\
		cd /opt/monitoring/k6 && \
		BASE_URL=http://10.0.1.10:8080 \
		k6 run --out influxdb=http://localhost:8086/k6 load-test.js'

test-local:
	BASE_URL=http://$$(terraform output -raw app_external_ip):8080 \
		k6 run ../k6/load-test.js

# ─── v2 부하 테스트 ──────────────────────────────────────
k6-upload-v2:
	@echo "=== Uploading v2 k6 scripts ==="
	gcloud compute scp \
		../k6/load-test-v2.js ../k6/hot-key-test.js ../k6/circle-visibility-test.js \
		../k6/smoke-test.js \
		coredisc-monitoring:/opt/monitoring/k6/ --zone=$(ZONE)
	@echo "=== Uploading manifest ==="
	gcloud compute scp ../k6/manifest.json \
		coredisc-monitoring:/opt/monitoring/k6/manifest.json --zone=$(ZONE) 2>/dev/null || \
		echo "manifest.json not found locally, will use VM-generated one"

test-v2: k6-upload-v2
	@echo "=== Running v2 load test (results -> InfluxDB -> Grafana) ==="
	gcloud compute ssh coredisc-monitoring --zone=$(ZONE) --command='\
		cd /opt/monitoring/k6 && \
		BASE_URL=http://10.0.1.10:8080 \
		MANIFEST_PATH=/opt/monitoring/k6/manifest.json \
		k6 run --out influxdb=http://localhost:8086/k6 load-test-v2.js'

test-hotkey: k6-upload-v2
	@echo "=== Running hot key stress test ==="
	gcloud compute ssh coredisc-monitoring --zone=$(ZONE) --command='\
		cd /opt/monitoring/k6 && \
		BASE_URL=http://10.0.1.10:8080 \
		MANIFEST_PATH=/opt/monitoring/k6/manifest.json \
		k6 run --out influxdb=http://localhost:8086/k6 hot-key-test.js'

test-circle: k6-upload-v2
	@echo "=== Running circle visibility verification test ==="
	gcloud compute ssh coredisc-monitoring --zone=$(ZONE) --command='\
		cd /opt/monitoring/k6 && \
		BASE_URL=http://10.0.1.10:8080 \
		MANIFEST_PATH=/opt/monitoring/k6/manifest.json \
		k6 run circle-visibility-test.js'