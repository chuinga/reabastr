# -----------------------------------------------------------------------------
# Lambda function definitions — placeholder zip until real handlers are deployed
# -----------------------------------------------------------------------------

locals {
  lambda_functions = toset([
    "products",
    "adjust",
    "categories",
    "eans",
    "households",
    "share_code",
    "history",
    "sync",
  ])
}

# Placeholder handler used until CI deploys real code
data "archive_file" "lambda_placeholder" {
  type        = "zip"
  output_path = "${path.module}/placeholder.zip"

  source {
    content  = "def handler(event, context): return {'statusCode': 200, 'body': '{}'}"
    filename = "handler.py"
  }
}

resource "aws_lambda_function" "functions" {
  for_each = local.lambda_functions

  function_name = "reabastr-${each.key}"
  role          = aws_iam_role.lambda_exec.arn
  handler       = "handler.handler"
  runtime       = "python3.12"
  timeout       = 30
  memory_size   = 256

  filename         = data.archive_file.lambda_placeholder.output_path
  source_code_hash = data.archive_file.lambda_placeholder.output_base64sha256

  environment {
    variables = {
      TABLE_NAME = aws_dynamodb_table.main.name
    }
  }

  tags = {
    Project = "reabastr"
  }

  lifecycle {
    ignore_changes = [filename, source_code_hash]
  }
}
