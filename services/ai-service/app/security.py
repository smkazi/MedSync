"""
Bearer-token authentication.

This service validates the same RS256 access tokens as every Java service, against
identity-service's JWKS. It never sees a password and never issues a token.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass

import jwt
from fastapi import Depends, HTTPException, Request, status
from jwt import PyJWKClient

from app.config import Settings, get_settings

logger = logging.getLogger(__name__)

_jwk_client: PyJWKClient | None = None


def _jwks(settings: Settings) -> PyJWKClient:
    """The JWKS client, created once. It caches keys and refetches on an unknown key id."""
    global _jwk_client  # noqa: PLW0603 - a single shared client is the point
    if _jwk_client is None:
        _jwk_client = PyJWKClient(settings.jwks_uri, cache_keys=True, lifespan=600)
    return _jwk_client


@dataclass(frozen=True)
class Caller:
    """The authenticated user, as the token describes them."""

    user_id: str
    username: str
    roles: frozenset[str]

    def has_any_role(self, *roles: str) -> bool:
        return bool(self.roles.intersection(roles))


def _unauthorised(detail: str) -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail=detail,
        headers={"WWW-Authenticate": "Bearer"},
    )


def authenticate(request: Request, settings: Settings = Depends(get_settings)) -> Caller:
    """Resolves the caller from the Authorization header, or rejects the request."""
    if settings.auth_disabled:
        logger.warning("Authentication is disabled - this must never be a deployed configuration")
        return Caller(user_id="local-dev", username="local-dev", roles=frozenset({"ADMIN"}))

    header = request.headers.get("Authorization", "")
    if not header.startswith("Bearer "):
        raise _unauthorised("A bearer token is required")
    token = header.removeprefix("Bearer ").strip()

    try:
        signing_key = _jwks(settings).get_signing_key_from_jwt(token)
        claims = jwt.decode(
            token,
            signing_key.key,
            # RS256 only. Accepting a wider set is how "alg: none" and HMAC-confusion
            # attacks get in.
            algorithms=["RS256"],
            audience=settings.jwt_audience,
            issuer=settings.jwt_issuer,
            options={"require": ["exp", "iat", "sub"]},
        )
    except jwt.ExpiredSignatureError as exc:
        raise _unauthorised("Token has expired") from exc
    except (jwt.InvalidTokenError, jwt.PyJWKClientError) as exc:
        # The reason is logged but never returned: it would tell an attacker which part failed.
        logger.info("Rejected token: %s", exc)
        raise _unauthorised("Token is not valid") from exc

    roles = claims.get("roles") or []
    return Caller(
        user_id=str(claims.get("sub", "")),
        username=str(claims.get("preferred_username", "unknown")),
        roles=frozenset(str(role) for role in roles),
    )


def require_roles(*allowed: str):
    """Dependency factory enforcing that the caller holds one of the given roles."""

    def dependency(caller: Caller = Depends(authenticate)) -> Caller:
        if allowed and not caller.has_any_role(*allowed):
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="This action requires one of: " + ", ".join(sorted(allowed)),
            )
        return caller

    return dependency
