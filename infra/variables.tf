variable "project_id" {
  description = "GCP 프로젝트 ID"
  type        = string
}

variable "region" {
  description = "GCP 리전"
  type        = string
  default     = "asia-northeast3" # Seoul
}

variable "zone" {
  description = "GCP 존"
  type        = string
  default     = "asia-northeast3-a"
}

variable "app_machine_type" {
  description = "App 서버 머신 타입"
  type        = string
  default     = "e2-standard-4" # 4 vCPU, 16GB RAM
}

variable "monitoring_machine_type" {
  description = "모니터링 서버 머신 타입"
  type        = string
  default     = "e2-medium" # 2 vCPU, 4GB RAM
}

variable "redis_machine_type" {
  description = "Redis 서버 머신 타입"
  type        = string
  default     = "e2-small" # 2 vCPU (shared), 2GB RAM
}

variable "db_password" {
  description = "MySQL root 비밀번호"
  type        = string
  sensitive   = true
  default     = "coredisc2024"
}

variable "db_name" {
  description = "MySQL 데이터베이스 이름"
  type        = string
  default     = "coredisc"
}
