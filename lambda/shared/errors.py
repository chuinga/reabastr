"""Structured error responses for API Gateway proxy integration."""

from __future__ import annotations

import json
from typing import Any


class AppError(Exception):
    """Base class for application errors that map to HTTP responses."""

    status_code: int = 500
    error_code: str = "INTERNAL_ERROR"

    def __init__(self, message: str, details: dict[str, Any] | None = None) -> None:
        super().__init__(message)
        self.message = message
        self.details = details or {}


class ValidationError(AppError):
    status_code = 400
    error_code = "VALIDATION_ERROR"


class UnauthorizedError(AppError):
    status_code = 401
    error_code = "UNAUTHORIZED"


class ForbiddenError(AppError):
    status_code = 403
    error_code = "FORBIDDEN"


class NotFoundError(AppError):
    status_code = 404
    error_code = "NOT_FOUND"


class ConflictError(AppError):
    """Base for 409 conflicts — subclass to set the specific error_code."""

    status_code = 409
    error_code = "CONFLICT"


class InsufficientStockError(ConflictError):
    error_code = "INSUFFICIENT_STOCK"


class DuplicateNameError(ConflictError):
    error_code = "DUPLICATE_NAME"


class EanInUseError(ConflictError):
    error_code = "EAN_IN_USE"


class ShareCodeExpiredError(ConflictError):
    error_code = "SHARE_CODE_EXPIRED"


class ShareCodeRedeemedError(ConflictError):
    error_code = "SHARE_CODE_REDEEMED"


class AlreadyInHouseholdError(ConflictError):
    error_code = "ALREADY_IN_HOUSEHOLD"


class InvalidCategoryError(AppError):
    status_code = 422
    error_code = "INVALID_CATEGORY"


class ThrottledError(AppError):
    status_code = 429
    error_code = "THROTTLED"


def error_response(err: AppError) -> dict[str, Any]:
    """Convert an AppError into an API Gateway proxy response dict."""
    body: dict[str, Any] = {
        "error": err.error_code,
        "message": err.message,
    }
    if err.details:
        body["details"] = err.details

    return {
        "statusCode": err.status_code,
        "headers": {"Content-Type": "application/json"},
        "body": json.dumps(body),
    }


def internal_error_response() -> dict[str, Any]:
    """Generic 500 response — never leaks stack traces."""
    return error_response(AppError("An unexpected error occurred"))
