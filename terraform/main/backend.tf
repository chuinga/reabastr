terraform {
  backend "s3" {
    bucket       = "reabastr-terraform-state"
    key          = "main/terraform.tfstate"
    region       = "eu-west-1"
    use_lockfile = true
  }
}
