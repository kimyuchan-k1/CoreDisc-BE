# ─── Static IPs ──────────────────────────────────────────
resource "google_compute_address" "app_internal" {
  name         = "coredisc-app-internal"
  subnetwork   = google_compute_subnetwork.subnet.id
  address_type = "INTERNAL"
  address      = "10.0.1.10"
  region       = var.region
}

resource "google_compute_address" "app_external" {
  name   = "coredisc-app-external"
  region = var.region
}

# ─── App VM ──────────────────────────────────────────────
resource "google_compute_instance" "app" {
  name         = "coredisc-app"
  machine_type = var.app_machine_type
  zone         = var.zone
  tags         = ["app-server"]

  boot_disk {
    initialize_params {
      image = "ubuntu-os-cloud/ubuntu-2204-lts"
      size  = 30
      type  = "pd-ssd"
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.subnet.id
    network_ip = google_compute_address.app_internal.address

    access_config {
      nat_ip = google_compute_address.app_external.address
    }
  }

  metadata_startup_script = templatefile("${path.module}/scripts/app-startup.sh", {
    db_password = var.db_password
    db_name     = var.db_name
  })

  service_account {
    scopes = ["cloud-platform"]
  }

  allow_stopping_for_update = true

  lifecycle {
    ignore_changes = [metadata_startup_script]
  }
}
