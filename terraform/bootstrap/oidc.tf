# GitHub Actions OIDC provider
resource "aws_iam_openid_connect_provider" "github" {
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"]

  tags = {
    Project = "reabastr"
  }
}

# IAM role assumed by GitHub Actions via OIDC
resource "aws_iam_role" "github_deploy" {
  name = var.deploy_role_name

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Federated = aws_iam_openid_connect_provider.github.arn
      }
      Action = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
        }
        StringLike = {
          "token.actions.githubusercontent.com:sub" = "repo:${var.github_org}/${var.github_repo}:*"
        }
      }
    }]
  })

  tags = {
    Project = "reabastr"
  }
}

# Policy granting permissions for the main Terraform to manage infrastructure
resource "aws_iam_role_policy" "deploy_permissions" {
  name = "reabastr-deploy-permissions"
  role = aws_iam_role.github_deploy.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "TerraformState"
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:DeleteObject",
          "s3:ListBucket",
        ]
        Resource = [
          aws_s3_bucket.terraform_state.arn,
          "${aws_s3_bucket.terraform_state.arn}/*",
        ]
      },
      {
        Sid    = "DynamoDB"
        Effect = "Allow"
        Action = [
          "dynamodb:CreateTable",
          "dynamodb:DeleteTable",
          "dynamodb:DescribeTable",
          "dynamodb:DescribeTimeToLive",
          "dynamodb:DescribeContinuousBackups",
          "dynamodb:ListTagsOfResource",
          "dynamodb:TagResource",
          "dynamodb:UntagResource",
          "dynamodb:UpdateTable",
          "dynamodb:UpdateTimeToLive",
          "dynamodb:UpdateContinuousBackups",
        ]
        Resource = "arn:aws:dynamodb:${var.aws_region}:*:table/reabastr-*"
      },
      {
        Sid    = "Cognito"
        Effect = "Allow"
        Action = [
          "cognito-idp:*",
        ]
        Resource = "arn:aws:cognito-idp:${var.aws_region}:*:userpool/*"
      },
      {
        Sid    = "CognitoCreate"
        Effect = "Allow"
        Action = [
          "cognito-idp:CreateUserPool",
          "cognito-idp:ListUserPools",
        ]
        Resource = "*"
      },
      {
        Sid    = "APIGateway"
        Effect = "Allow"
        Action = [
          "apigateway:*",
        ]
        Resource = "arn:aws:apigateway:${var.aws_region}::*"
      },
      {
        Sid    = "Lambda"
        Effect = "Allow"
        Action = [
          "lambda:*",
        ]
        Resource = "arn:aws:lambda:${var.aws_region}:*:function:reabastr-*"
      },
      {
        Sid    = "LambdaLayers"
        Effect = "Allow"
        Action = [
          "lambda:ListFunctions",
          "lambda:GetAccountSettings",
        ]
        Resource = "*"
      },
      {
        Sid    = "IAM"
        Effect = "Allow"
        Action = [
          "iam:CreateRole",
          "iam:DeleteRole",
          "iam:GetRole",
          "iam:UpdateRole",
          "iam:PassRole",
          "iam:AttachRolePolicy",
          "iam:DetachRolePolicy",
          "iam:PutRolePolicy",
          "iam:DeleteRolePolicy",
          "iam:GetRolePolicy",
          "iam:ListRolePolicies",
          "iam:ListAttachedRolePolicies",
          "iam:ListInstanceProfilesForRole",
          "iam:TagRole",
          "iam:UntagRole",
          "iam:CreatePolicy",
          "iam:DeletePolicy",
          "iam:GetPolicy",
          "iam:GetPolicyVersion",
          "iam:ListPolicyVersions",
          "iam:CreatePolicyVersion",
          "iam:DeletePolicyVersion",
        ]
        Resource = [
          "arn:aws:iam::*:role/reabastr-*",
          "arn:aws:iam::*:policy/reabastr-*",
        ]
      },
      {
        Sid    = "CloudWatchLogs"
        Effect = "Allow"
        Action = [
          "logs:CreateLogGroup",
          "logs:DeleteLogGroup",
          "logs:DescribeLogGroups",
          "logs:PutRetentionPolicy",
          "logs:TagLogGroup",
          "logs:ListTagsLogGroup",
        ]
        Resource = "arn:aws:logs:${var.aws_region}:*:log-group:/aws/lambda/reabastr-*"
      },
    ]
  })
}
