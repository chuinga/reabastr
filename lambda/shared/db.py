"""DynamoDB table client, initialized outside the handler for warm reuse."""

import os

import boto3

# Initialized at module load time so the connection is reused across
# invocations within the same Lambda execution environment.
_dynamodb = boto3.resource("dynamodb")
table = _dynamodb.Table(os.environ["TABLE_NAME"])
