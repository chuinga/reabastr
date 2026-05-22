output "state_bucket_name" {
  description = "Name of the S3 bucket storing Terraform state"
  value       = aws_s3_bucket.terraform_state.bucket
}

output "state_bucket_arn" {
  description = "ARN of the S3 state bucket"
  value       = aws_s3_bucket.terraform_state.arn
}

output "deploy_role_arn" {
  description = "ARN of the GitHub Actions deploy role"
  value       = aws_iam_role.github_deploy.arn
}

output "oidc_provider_arn" {
  description = "ARN of the GitHub OIDC provider"
  value       = aws_iam_openid_connect_provider.github.arn
}
