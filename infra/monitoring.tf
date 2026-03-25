# ─── Static IP ───────────────────────────────────────────
resource "google_compute_address" "monitoring_external" {
  name   = "coredisc-mon-external"
  region = var.region
}

# ─── Monitoring VM ───────────────────────────────────────
resource "google_compute_instance" "monitoring" {
  name         = "coredisc-monitoring"
  machine_type = var.monitoring_machine_type
  zone         = var.zone
  tags         = ["monitoring"]

  boot_disk {
    initialize_params {
      image = "ubuntu-os-cloud/ubuntu-2204-lts"
      size  = 20
      type  = "pd-ssd"
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.subnet.id

    access_config {
      nat_ip = google_compute_address.monitoring_external.address
    }
  }

  metadata_startup_script = templatefile("${path.module}/scripts/monitoring-startup.sh", {
    app_internal_ip = google_compute_address.app_internal.address
  })

  service_account {
    scopes = ["cloud-platform"]
  }
}