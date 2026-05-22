output "api_gateway_url" {
  description = "Base URL of the API Gateway REST API"
  value       = aws_api_gateway_stage.v1.invoke_url
}

output "cognito_user_pool_id" {
  description = "Cognito User Pool ID"
  value       = aws_cognito_user_pool.main.id
}

output "cognito_app_client_id" {
  description = "Cognito App Client ID (public, PKCE)"
  value       = aws_cognito_user_pool_client.app.id
}

output "dynamodb_table_name" {
  description = "DynamoDB main table name"
  value       = aws_dynamodb_table.main.name
}
