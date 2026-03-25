# ─── VPC & Subnet ────────────────────────────────────────
resource "google_compute_network" "vpc" {
  name                    = "coredisc-vpc"
  auto_create_subnetworks = false

  depends_on = [google_project_service.compute]
}

resource "google_compute_subnetwork" "subnet" {
  name          = "coredisc-subnet"
  ip_cidr_range = "10.0.1.0/24"
  region        = var.region
  network       = google_compute_network.vpc.id
}

# ─── Firewall Rules ─────────────────────────────────────
resource "google_compute_firewall" "allow_ssh" {
  name    = "coredisc-allow-ssh"
  network = google_compute_network.vpc.name

  allow {
    protocol = "tcp"
    ports    = ["22"]
  }

  source_ranges = ["0.0.0.0/0"]
}

resource "google_compute_firewall" "allow_app" {
  name    = "coredisc-allow-app"
  network = google_compute_network.vpc.name

  allow {
    protocol = "tcp"
    ports    = ["8080"]
  }

  source_ranges = ["0.0.0.0/0"]
  target_tags   = ["app-server"]
}

resource "google_compute_firewall" "allow_monitoring" {
  name    = "coredisc-allow-monitoring"
  network = google_compute_network.vpc.name

  allow {
    protocol = "tcp"
    ports    = ["3000", "9090"]
  }

  source_ranges = ["0.0.0.0/0"]
  target_tags   = ["monitoring"]
}

resource "google_compute_firewall" "allow_internal" {
  name    = "coredisc-allow-internal"
  network = google_compute_network.vpc.name

  allow {
    protocol = "tcp"
    ports    = ["0-65535"]
  }

  source_ranges = ["10.0.1.0/24"]
}