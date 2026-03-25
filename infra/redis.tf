# ─── Static IPs ──────────────────────────────────────────
resource "google_compute_address" "redis_internal" {
  name         = "coredisc-redis-internal"
  subnetwork   = google_compute_subnetwork.subnet.id
  address_type = "INTERNAL"
  address      = "10.0.1.20"
  region       = var.region
}

# ─── Redis VM ──────────────────────────────────────────
resource "google_compute_instance" "redis" {
  name         = "coredisc-redis"
  machine_type = var.redis_machine_type
  zone         = var.zone
  tags         = ["redis-server"]

  boot_disk {
    initialize_params {
      image = "ubuntu-os-cloud/ubuntu-2204-lts"
      size  = 10
      type  = "pd-ssd"
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.subnet.id
    network_ip = google_compute_address.redis_internal.address

    access_config {
      # SSH 접속용 외부 IP (임시)
    }
  }

  metadata_startup_script = file("${path.module}/scripts/redis-startup.sh")

  service_account {
    scopes = ["cloud-platform"]
  }
}
