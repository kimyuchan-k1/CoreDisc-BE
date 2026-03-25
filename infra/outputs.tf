output "app_external_ip" {
  description = "App 서버 외부 IP"
  value       = google_compute_address.app_external.address
}

output "app_internal_ip" {
  description = "App 서버 내부 IP"
  value       = google_compute_address.app_internal.address
}

output "monitoring_external_ip" {
  description = "모니터링 서버 외부 IP"
  value       = google_compute_address.monitoring_external.address
}

output "redis_internal_ip" {
  description = "Redis 서버 내부 IP"
  value       = google_compute_address.redis_internal.address
}

output "grafana_url" {
  description = "Grafana 대시보드 URL"
  value       = "http://${google_compute_address.monitoring_external.address}:3000"
}

output "prometheus_url" {
  description = "Prometheus URL"
  value       = "http://${google_compute_address.monitoring_external.address}:9090"
}

output "app_url" {
  description = "Spring Boot App URL"
  value       = "http://${google_compute_address.app_external.address}:8080"
}

output "zone" {
  value = var.zone
}

output "project_id" {
  value = var.project_id
}

# ─── 편의 명령어 출력 ────────────────────────────────────
output "quick_commands" {
  description = "자주 쓰는 명령어"
  value = <<-EOT

    ══════════════════════════════════════════════════
      CoreDisc Performance Test Environment
    ══════════════════════════════════════════════════
    App:        http://${google_compute_address.app_external.address}:8080
    Redis:      ${google_compute_address.redis_internal.address}:6379 (internal)
    Grafana:    http://${google_compute_address.monitoring_external.address}:3000 (admin/admin)
    Prometheus: http://${google_compute_address.monitoring_external.address}:9090
    ──────────────────────────────────────────────────
    SSH App:    gcloud compute ssh coredisc-app --zone=${var.zone}
    SSH Redis:  gcloud compute ssh coredisc-redis --zone=${var.zone}
    SSH Mon:    gcloud compute ssh coredisc-monitoring --zone=${var.zone}
    ══════════════════════════════════════════════════
  EOT
}