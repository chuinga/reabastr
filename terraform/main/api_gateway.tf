# -----------------------------------------------------------------------------
# API Gateway REST API — Cognito authorizer, all endpoint resources & methods
# -----------------------------------------------------------------------------

# --- REST API ---

resource "aws_api_gateway_rest_api" "main" {
  name        = "reabastr-api"
  description = "Reabastr household inventory API"

  endpoint_configuration {
    types = ["REGIONAL"]
  }

  tags = {
    Project = "reabastr"
  }
}

# --- Cognito Authorizer ---

resource "aws_api_gateway_authorizer" "cognito" {
  name            = "cognito-authorizer"
  rest_api_id     = aws_api_gateway_rest_api.main.id
  type            = "COGNITO_USER_POOLS"
  identity_source = "method.request.header.Authorization"
  provider_arns   = [aws_cognito_user_pool.main.arn]
}

# =============================================================================
# Resource tree
# =============================================================================

# --- /products ---

resource "aws_api_gateway_resource" "products" {
  rest_api_id = aws_api_gateway_rest_api.main.id
  parent_id   = aws_api_gateway_rest_api.main.root_resource_id
  path_part   = "products"
}

resource "aws_api_gateway_resource" "products_id" {
  rest_api_id = aws_api_gateway_rest_api.main.id
  parent_id   = aws_api_gateway_resource.products.id
  path_part   = "{productId}"
}

# --- /products/{productId}/eans ---

resource "aws_api_gateway_resource" "products_id_eans" {
  rest_api_id = aws_api_gateway_rest_api.main.id
  parent_id   = aws_api_gateway_resource.products_id.id
  path_part   = "eans"
}

resource "aws_api_gateway_resource" "products_id_eans_ean" {
  rest_api_id = aws_api_gateway_rest_api.main.id
  parent_id   = aws_api_gateway_resource.products_id_eans.id
  path_part   = "{ean}"
}

# --- /eans/{ean} ---

resource "aws_api_gateway_resource" "eans" {
  rest_api_id = aws_api_gateway_rest_api.main.id
  parent_id   = aws_api_gateway_rest_api.main.root_resource_id
  path_part   = "eans"
}

resource "aws_api_gateway_resource" "eans_ean" {
  rest_api_id = aws_api_gateway_rest_api.main.id
  parent_id   = aws_api_gateway_resource.eans.id
  path_part   = "{ean}"
}

# --- /adjust ---

resource "aws_api_gateway_resource" "adjust" {
  rest_api_id = aws_api_gateway_rest_api.main.id
  parent_id   = aws_api_gateway_rest_api.main.root_resource_id
  path_part   = "adjust"
}

# --- /categories ---

resource "aws_api_gateway_resource" "categories" {
  rest_api_id = aws_api_gateway_rest_api.main.id
  parent_id   = aws_api_gateway_rest_api.main.root_resource_id
  path_part   = "categories"
}

resource "aws_api_gateway_resource" "categories_id" {
  rest_api_id = aws_api_gateway_rest_api.main.id
  parent_id   = aws_api_gateway_resource.categories.id
  path_part   = "{categoryId}"
}

resource "aws_api_gateway_resource" "categories_reorder" {
  rest_api_id = aws_api_gateway_rest_api.main.id
  parent_id   = aws_api_gateway_resource.categories.id
  path_part   = "reorder"
}

# --- /household ---

resource "aws_api_gateway_resource" "household" {
  rest_api_id = aws_api_gateway_rest_api.main.id
  parent_id   = aws_api_gateway_rest_api.main.root_resource_id
  path_part   = "household"
}

resource "aws_api_gateway_resource" "household_share_code" {
  rest_api_id = aws_api_gateway_rest_api.main.id
  parent_id   = aws_api_gateway_resource.household.id
  path_part   = "share-code"
}

resource "aws_api_gateway_resource" "household_join" {
  rest_api_id = aws_api_gateway_rest_api.main.id
  parent_id   = aws_api_gateway_resource.household.id
  path_part   = "join"
}

resource "aws_api_gateway_resource" "household_leave" {
  rest_api_id = aws_api_gateway_rest_api.main.id
  parent_id   = aws_api_gateway_resource.household.id
  path_part   = "leave"
}

# --- /history ---

resource "aws_api_gateway_resource" "history" {
  rest_api_id = aws_api_gateway_rest_api.main.id
  parent_id   = aws_api_gateway_rest_api.main.root_resource_id
  path_part   = "history"
}

# --- /sync ---

resource "aws_api_gateway_resource" "sync" {
  rest_api_id = aws_api_gateway_rest_api.main.id
  parent_id   = aws_api_gateway_rest_api.main.root_resource_id
  path_part   = "sync"
}

resource "aws_api_gateway_resource" "sync_batch" {
  rest_api_id = aws_api_gateway_rest_api.main.id
  parent_id   = aws_api_gateway_resource.sync.id
  path_part   = "batch"
}

# =============================================================================
# Methods + Lambda integrations
# =============================================================================

locals {
  # Each entry: resource_id, http_method, lambda_function_ref
  api_methods = {
    # Products
    products_get       = { resource_id = aws_api_gateway_resource.products.id, method = "GET", lambda = "products" }
    products_post      = { resource_id = aws_api_gateway_resource.products.id, method = "POST", lambda = "products" }
    products_id_put    = { resource_id = aws_api_gateway_resource.products_id.id, method = "PUT", lambda = "products" }
    products_id_delete = { resource_id = aws_api_gateway_resource.products_id.id, method = "DELETE", lambda = "products" }
    # Stock Adjustments
    adjust_post = { resource_id = aws_api_gateway_resource.adjust.id, method = "POST", lambda = "adjust" }
    # Categories
    categories_get       = { resource_id = aws_api_gateway_resource.categories.id, method = "GET", lambda = "categories" }
    categories_post      = { resource_id = aws_api_gateway_resource.categories.id, method = "POST", lambda = "categories" }
    categories_id_put    = { resource_id = aws_api_gateway_resource.categories_id.id, method = "PUT", lambda = "categories" }
    categories_id_delete = { resource_id = aws_api_gateway_resource.categories_id.id, method = "DELETE", lambda = "categories" }
    categories_reorder   = { resource_id = aws_api_gateway_resource.categories_reorder.id, method = "PUT", lambda = "categories" }
    # EAN Mappings
    eans_post   = { resource_id = aws_api_gateway_resource.products_id_eans.id, method = "POST", lambda = "eans" }
    eans_delete = { resource_id = aws_api_gateway_resource.products_id_eans_ean.id, method = "DELETE", lambda = "eans" }
    eans_get    = { resource_id = aws_api_gateway_resource.eans_ean.id, method = "GET", lambda = "eans" }
    # Households & Sharing
    household_get        = { resource_id = aws_api_gateway_resource.household.id, method = "GET", lambda = "households" }
    household_post       = { resource_id = aws_api_gateway_resource.household.id, method = "POST", lambda = "households" }
    household_share_code = { resource_id = aws_api_gateway_resource.household_share_code.id, method = "POST", lambda = "share_code" }
    household_join       = { resource_id = aws_api_gateway_resource.household_join.id, method = "POST", lambda = "share_code" }
    household_leave      = { resource_id = aws_api_gateway_resource.household_leave.id, method = "POST", lambda = "households" }
    # History
    history_get = { resource_id = aws_api_gateway_resource.history.id, method = "GET", lambda = "history" }
    # Sync
    sync_get        = { resource_id = aws_api_gateway_resource.sync.id, method = "GET", lambda = "sync" }
    sync_batch_post = { resource_id = aws_api_gateway_resource.sync_batch.id, method = "POST", lambda = "sync" }
  }
}

# --- Methods (authorized) ---

resource "aws_api_gateway_method" "methods" {
  for_each = local.api_methods

  rest_api_id   = aws_api_gateway_rest_api.main.id
  resource_id   = each.value.resource_id
  http_method   = each.value.method
  authorization = "COGNITO_USER_POOLS"
  authorizer_id = aws_api_gateway_authorizer.cognito.id

}

# --- Lambda invoke ARN lookup ---

locals {
  lambda_invoke_arns = {
    products   = aws_lambda_function.functions["products"].invoke_arn
    adjust     = aws_lambda_function.functions["adjust"].invoke_arn
    categories = aws_lambda_function.functions["categories"].invoke_arn
    households = aws_lambda_function.functions["households"].invoke_arn
    share_code = aws_lambda_function.functions["share_code"].invoke_arn
    history    = aws_lambda_function.functions["history"].invoke_arn
    sync       = aws_lambda_function.functions["sync"].invoke_arn
  }
}

# --- Integrations (AWS_PROXY → Lambda) ---

resource "aws_api_gateway_integration" "integrations" {
  for_each = local.api_methods

  rest_api_id             = aws_api_gateway_rest_api.main.id
  resource_id             = each.value.resource_id
  http_method             = aws_api_gateway_method.methods[each.key].http_method
  type                    = "AWS_PROXY"
  integration_http_method = "POST"
  uri                     = local.lambda_invoke_arns[each.value.lambda]
}

# =============================================================================
# CORS — OPTIONS method on every resource (no authorizer)
# =============================================================================

locals {
  cors_resources = {
    products             = aws_api_gateway_resource.products.id
    products_id          = aws_api_gateway_resource.products_id.id
    products_id_eans     = aws_api_gateway_resource.products_id_eans.id
    products_id_eans_ean = aws_api_gateway_resource.products_id_eans_ean.id
    eans_ean             = aws_api_gateway_resource.eans_ean.id
    adjust               = aws_api_gateway_resource.adjust.id
    categories           = aws_api_gateway_resource.categories.id
    categories_id        = aws_api_gateway_resource.categories_id.id
    categories_reorder   = aws_api_gateway_resource.categories_reorder.id
    household            = aws_api_gateway_resource.household.id
    household_share_code = aws_api_gateway_resource.household_share_code.id
    household_join       = aws_api_gateway_resource.household_join.id
    household_leave      = aws_api_gateway_resource.household_leave.id
    history              = aws_api_gateway_resource.history.id
    sync                 = aws_api_gateway_resource.sync.id
    sync_batch           = aws_api_gateway_resource.sync_batch.id
  }
}

resource "aws_api_gateway_method" "options" {
  for_each = local.cors_resources

  rest_api_id   = aws_api_gateway_rest_api.main.id
  resource_id   = each.value
  http_method   = "OPTIONS"
  authorization = "NONE"
}

resource "aws_api_gateway_integration" "options" {
  for_each = local.cors_resources

  rest_api_id = aws_api_gateway_rest_api.main.id
  resource_id = each.value
  http_method = aws_api_gateway_method.options[each.key].http_method
  type        = "MOCK"

  request_templates = {
    "application/json" = "{\"statusCode\": 200}"
  }
}

resource "aws_api_gateway_method_response" "options_200" {
  for_each = local.cors_resources

  rest_api_id = aws_api_gateway_rest_api.main.id
  resource_id = each.value
  http_method = aws_api_gateway_method.options[each.key].http_method
  status_code = "200"

  response_parameters = {
    "method.response.header.Access-Control-Allow-Headers" = true
    "method.response.header.Access-Control-Allow-Methods" = true
    "method.response.header.Access-Control-Allow-Origin"  = true
  }

  response_models = {
    "application/json" = "Empty"
  }
}

resource "aws_api_gateway_integration_response" "options_200" {
  for_each = local.cors_resources

  rest_api_id = aws_api_gateway_rest_api.main.id
  resource_id = each.value
  http_method = aws_api_gateway_method.options[each.key].http_method
  status_code = aws_api_gateway_method_response.options_200[each.key].status_code

  response_parameters = {
    "method.response.header.Access-Control-Allow-Headers" = "'Content-Type,Authorization'"
    "method.response.header.Access-Control-Allow-Methods" = "'GET,POST,PUT,DELETE,OPTIONS'"
    "method.response.header.Access-Control-Allow-Origin"  = "'*'"
  }
}

# =============================================================================
# Deployment + Stage
# =============================================================================

resource "aws_api_gateway_deployment" "main" {
  rest_api_id = aws_api_gateway_rest_api.main.id

  # Redeploy when any method or integration changes
  triggers = {
    redeployment = sha1(jsonencode([
      aws_api_gateway_method.methods,
      aws_api_gateway_integration.integrations,
      aws_api_gateway_method.options,
      aws_api_gateway_integration.options,
    ]))
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_api_gateway_stage" "v1" {
  deployment_id = aws_api_gateway_deployment.main.id
  rest_api_id   = aws_api_gateway_rest_api.main.id
  stage_name    = "v1"

  tags = {
    Project = "reabastr"
  }
}

# =============================================================================
# Lambda permissions — allow API Gateway to invoke each function
# =============================================================================

resource "aws_lambda_permission" "apigw_products" {
  statement_id  = "AllowAPIGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.functions["products"].function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_api_gateway_rest_api.main.execution_arn}/*/*"
}

resource "aws_lambda_permission" "apigw_adjust" {
  statement_id  = "AllowAPIGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.functions["adjust"].function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_api_gateway_rest_api.main.execution_arn}/*/*"
}

resource "aws_lambda_permission" "apigw_categories" {
  statement_id  = "AllowAPIGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.functions["categories"].function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_api_gateway_rest_api.main.execution_arn}/*/*"
}

resource "aws_lambda_permission" "apigw_households" {
  statement_id  = "AllowAPIGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.functions["households"].function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_api_gateway_rest_api.main.execution_arn}/*/*"
}

resource "aws_lambda_permission" "apigw_share_code" {
  statement_id  = "AllowAPIGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.functions["share_code"].function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_api_gateway_rest_api.main.execution_arn}/*/*"
}

resource "aws_lambda_permission" "apigw_history" {
  statement_id  = "AllowAPIGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.functions["history"].function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_api_gateway_rest_api.main.execution_arn}/*/*"
}

resource "aws_lambda_permission" "apigw_sync" {
  statement_id  = "AllowAPIGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.functions["sync"].function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_api_gateway_rest_api.main.execution_arn}/*/*"
}
