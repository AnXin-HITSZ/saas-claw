"""Workspace path guards for filesystem tools."""

from __future__ import annotations

from pathlib import Path


class WorkspacePathError(PermissionError):
    """Raised when a path escapes the configured workspace."""


class WorkspaceMutationError(PermissionError):
    """Raised when a mutation targets protected workspace internals."""


PROTECTED_MUTATION_NAMES = {".git", ".venv", "__pycache__", ".mypy_cache", ".pytest_cache"}
PROTECTED_MUTATION_SUFFIXES = {".pyc", ".pyo"}


def resolve_workspace_path(
    path: str,
    *,
    cwd: Path,
    workspace_dir: Path,
    workspace_only: bool = True,
) -> Path:
    candidate = Path(path)
    if not candidate.is_absolute():
        candidate = cwd / candidate

    resolved = candidate.resolve()
    if not workspace_only:
        return resolved

    workspace = workspace_dir.resolve()
    try:
        resolved.relative_to(workspace)
    except ValueError as exc:
        raise WorkspacePathError(f"path escapes workspace: {path}") from exc
    return resolved


def ensure_workspace_mutation_allowed(path: Path, *, workspace_dir: Path) -> None:
    workspace = workspace_dir.resolve()
    resolved = path.resolve()
    try:
        relative = resolved.relative_to(workspace)
    except ValueError as exc:
        raise WorkspacePathError(f"path escapes workspace: {path}") from exc

    names = {part.lower() for part in relative.parts}
    protected = names.intersection(PROTECTED_MUTATION_NAMES)
    if protected:
        name = sorted(protected)[0]
        raise WorkspaceMutationError(f"mutation blocked for protected workspace path: {name}")
    if resolved.suffix.lower() in PROTECTED_MUTATION_SUFFIXES:
        raise WorkspaceMutationError(f"mutation blocked for generated file type: {resolved.suffix}")