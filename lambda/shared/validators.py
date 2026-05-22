"""Input validation helpers with field-level error details."""

from __future__ import annotations

import re
from typing import Any

from .errors import ValidationError

_EAN_PATTERN = re.compile(r"^\d{8}$|^\d{13}$")


def validate_required_fields(
    body: dict[str, Any] | None, fields: list[str]
) -> dict[str, Any]:
    """Ensure body is a dict and all required fields are present.

    Returns the validated body dict for convenience.
    """
    if not isinstance(body, dict):
        raise ValidationError(
            "Request body must be a JSON object",
            details={"fields": fields},
        )

    missing = [f for f in fields if f not in body or body[f] is None]
    if missing:
        raise ValidationError(
            "Missing required fields",
            details={"fields": missing},
        )
    return body


def validate_string_length(
    value: Any, field: str, *, min_len: int = 1, max_len: int = 100
) -> str:
    """Validate that a value is a non-empty string within length bounds."""
    if not isinstance(value, str):
        raise ValidationError(
            f"{field} must be a string",
            details={"field": field},
        )

    stripped = value.strip()
    if len(stripped) < min_len:
        raise ValidationError(
            f"{field} must be at least {min_len} character(s)",
            details={"field": field, "min": min_len},
        )
    if len(stripped) > max_len:
        raise ValidationError(
            f"{field} must be at most {max_len} characters",
            details={"field": field, "max": max_len},
        )
    return stripped


def validate_integer_range(
    value: Any, field: str, *, min_val: int, max_val: int
) -> int:
    """Validate that a value is an integer within the given range."""
    if not isinstance(value, int) or isinstance(value, bool):
        raise ValidationError(
            f"{field} must be an integer",
            details={"field": field},
        )
    if value < min_val or value > max_val:
        raise ValidationError(
            f"{field} must be between {min_val} and {max_val}",
            details={"field": field, "min": min_val, "max": max_val},
        )
    return value


def validate_ean(value: Any, field: str = "ean") -> str:
    """Validate that a value is a valid EAN-8 or EAN-13 string."""
    if not isinstance(value, str):
        raise ValidationError(
            f"{field} must be a string",
            details={"field": field},
        )
    if not _EAN_PATTERN.match(value):
        raise ValidationError(
            f"{field} must be an 8 or 13 digit number",
            details={"field": field},
        )
    return value
