variable "aws_region" {
  description = "AWS region for all resources"
  type        = string
  default     = "eu-west-1"
}

variable "google_client_id" {
  description = "Google OAuth 2.0 client ID for Cognito federation"
  type        = string
  sensitive   = true
}

variable "google_client_secret" {
  description = "Google OAuth 2.0 client secret for Cognito federation"
  type        = string
  sensitive   = true
}
