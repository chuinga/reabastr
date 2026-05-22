variable "aws_region" {
  description = "AWS region for all resources"
  type        = string
  default     = "eu-west-1"
}

variable "github_org" {
  description = "GitHub organization or user that owns the repository"
  type        = string
}

variable "github_repo" {
  description = "GitHub repository name (without the org prefix)"
  type        = string
}

variable "state_bucket_name" {
  description = "Name of the S3 bucket for Terraform remote state"
  type        = string
  default     = "reabastr-terraform-state"
}

variable "deploy_role_name" {
  description = "Name of the IAM role assumed by GitHub Actions"
  type        = string
  default     = "reabastr-github-deploy"
}
