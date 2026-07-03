"""Shell command classification and guard decisions."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

from openclaw.tools.shell.parser import ShellCommandAst, ShellCommandSegment, parse_shell_command

CommandSafety = Literal["readonly", "mutation", "dangerous", "unknown"]

READONLY_COMMANDS = {
    "cat",
    "cd",
    "dir",
    "echo",
    "find",
    "findstr",
    "git",
    "grep",
    "head",
    "ls",
    "more",
    "pwd",
    "rg",
    "select-string",
    "tail",
    "type",
    "where",
    "where.exe",
    "whoami",
}

MUTATION_COMMANDS = {
    "apply_patch",
    "copy",
    "cp",
    "echo",  # echo becomes mutation when redirected.
    "git",
    "mkdir",
    "move",
    "mv",
    "new-item",
    "npm",
    "pnpm",
    "pip",
    "python",
    "py",
    "rename-item",
    "set-content",
    "tee",
    "write-output",  # mutation when redirected.
}

DANGEROUS_COMMANDS = {
    "del",
    "erase",
    "format",
    "git",
    "reg",
    "remove-item",
    "rm",
    "rmdir",
    "shutdown",
}

READONLY_GIT_SUBCOMMANDS = {
    "branch",
    "diff",
    "log",
    "merge-base",
    "remote",
    "rev-parse",
    "show",
    "status",
}

MUTATION_GIT_SUBCOMMANDS = {
    "add",
    "am",
    "apply",
    "checkout",
    "cherry-pick",
    "clean",
    "commit",
    "merge",
    "mv",
    "pull",
    "push",
    "rebase",
    "reset",
    "restore",
    "rm",
    "switch",
}

DANGEROUS_GIT_ARGS = {"--hard", "--force", "-f", "-fd", "-fdx", "--force-with-lease"}

DANGEROUS_POWERSHELL_FLAGS = {"-recurse", "-force"}
DANGEROUS_RM_FLAGS = {"-rf", "-fr", "-r", "-f"}
DANGEROUS_DEL_FLAGS = {"/f", "/s", "/q"}


@dataclass(frozen=True)
class ShellCommandClassification:
    safety: CommandSafety
    reasons: tuple[str, ...]
    ast: ShellCommandAst
    requires_approval: bool


def classify_shell_command(command: str, *, dialect: str | None = None) -> ShellCommandClassification:
    ast = parse_shell_command(command, dialect=dialect)  # type: ignore[arg-type]
    reasons: list[str] = list(ast.parse_errors)
    segment_safety: list[CommandSafety] = []

    if not ast.segments:
        return ShellCommandClassification("dangerous", ("empty command",), ast, requires_approval=False)

    for segment in ast.segments:
        safety, reason = classify_segment(segment)
        segment_safety.append(safety)
        reasons.append(reason)
        if segment.redirects and safety == "readonly":
            safety = "mutation"
            segment_safety[-1] = safety
            reasons.append("stdout/stderr redirection can mutate files")

    safety = combine_safety(segment_safety)
    if ast.has_control_flow and safety == "readonly":
        reasons.append("compound readonly command")
    requires_approval = safety in {"mutation", "dangerous", "unknown"}
    return ShellCommandClassification(safety, tuple(reasons), ast, requires_approval=requires_approval)


def classify_segment(segment: ShellCommandSegment) -> tuple[CommandSafety, str]:
    if not segment.argv:
        return "unknown", "empty command segment"
    command = normalize_command_name(segment.argv[0])
    args = tuple(arg.lower() for arg in segment.argv[1:])

    if command == "git":
        return classify_git(args)
    if command in {"rm", "rmdir", "del", "erase", "remove-item"}:
        if has_dangerous_delete_args(command, args):
            return "dangerous", f"{command} uses recursive/force deletion flags"
        return "mutation", f"{command} mutates files"
    if command in {"format", "shutdown"}:
        return "dangerous", f"{command} is a destructive system command"
    if command == "reg" and args[:1] == ("delete",):
        return "dangerous", "reg delete mutates registry state"
    if command in {"set-content", "new-item", "rename-item", "copy", "cp", "move", "mv", "mkdir", "tee"}:
        return "mutation", f"{command} mutates filesystem state"
    if command in {"pip", "npm", "pnpm"}:
        return "mutation", f"{command} can install or modify dependencies"
    if command in {"python", "py"} and any(arg in {"-c", "-m"} for arg in args):
        return "unknown", f"{command} dynamic execution needs approval"
    if command in READONLY_COMMANDS:
        return "readonly", f"{command} is recognized as readonly"
    if command in MUTATION_COMMANDS:
        return "mutation", f"{command} is recognized as mutating"
    if command in DANGEROUS_COMMANDS:
        return "dangerous", f"{command} is recognized as dangerous"
    return "unknown", f"{command} is not in the shell safety catalog"


def classify_git(args: tuple[str, ...]) -> tuple[CommandSafety, str]:
    subcommand = next((arg for arg in args if not arg.startswith("-")), "")
    if any(arg in DANGEROUS_GIT_ARGS for arg in args):
        return "dangerous", "git command uses force/hard flags"
    if subcommand in READONLY_GIT_SUBCOMMANDS:
        return "readonly", f"git {subcommand} is readonly"
    if subcommand in MUTATION_GIT_SUBCOMMANDS:
        return "mutation", f"git {subcommand} mutates repository state"
    return "unknown", "git subcommand is unknown"


def has_dangerous_delete_args(command: str, args: tuple[str, ...]) -> bool:
    arg_set = set(args)
    if command == "remove-item":
        return DANGEROUS_POWERSHELL_FLAGS.issubset(arg_set)
    if command == "rm":
        joined = " ".join(args)
        return any(flag in arg_set for flag in DANGEROUS_RM_FLAGS) or "-rf" in joined or "-fr" in joined
    if command in {"del", "erase"}:
        return bool(arg_set.intersection(DANGEROUS_DEL_FLAGS))
    if command == "rmdir":
        return "/s" in arg_set or "-r" in arg_set or "-recurse" in arg_set
    return False


def combine_safety(values: list[CommandSafety]) -> CommandSafety:
    if "dangerous" in values:
        return "dangerous"
    if "unknown" in values:
        return "unknown"
    if "mutation" in values:
        return "mutation"
    return "readonly"


def normalize_command_name(value: str) -> str:
    normalized = value.strip().strip('"').strip("'").lower().replace("\\", "/")
    name = normalized.rsplit("/", 1)[-1]
    if name.endswith(".exe"):
        name = name[:-4]
    return name