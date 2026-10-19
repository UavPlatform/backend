#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Project sync gate.

Canonical home: docs/tools/gate.py (backend/tools/gate.py is a verified copy).
Bootstrap copy may live at the workspace root before the docs repo is synced.

Commands:
  sync [--all] [--yes]      back up local changes, fetch, align to origin/<default>
  check [--allow-dirty]     verify the working tree is based on the synced remote
  scope --base <ref>        list changed paths since the merge base
  check-ci --base <ref>     CI: require <ref> to be an ancestor of HEAD
  install                   install git hooks and the git wrapper
  git <args...>             wrapper entry: gate read commands when stale
  hook <name>               git hook entry
  selftest                  exercise the gate against temporary repositories
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path

REPO_NAMES = ("backend", "docs")
STAMP_MAX_AGE_SECONDS = 12 * 60 * 60
WRAPPER_MARKER = b"DRONE_GATE_WRAPPER"
SENSITIVE_PATTERNS = tuple(
    re.compile(p)
    for p in (
        r"(^|/)application-dev\.ya?ml$",
        r"(^|/)\.env(\..*)?$",
        r".*\.key$",
        r".*\.pem$",
        r".*\.p12$",
        r".*\.pfx$",
    )
)
# Commands that may run while the tree is stale: they either diagnose or update refs.
STALE_ALLOWED_SUBCOMMANDS = {
    "fetch",
    "status",
    "rev-parse",
    "remote",
    "branch",
    "symbolic-ref",
    "show-ref",
    "for-each-ref",
    "config",
    "version",
    "help",
    "ls-remote",
}
GLOBAL_OPTIONS_WITH_VALUE = {"-C", "-c", "--git-dir", "--work-tree", "--namespace", "--super-prefix"}


class GateError(RuntimeError):
    pass


def find_workspace() -> Path:
    here = Path(__file__).resolve().parent
    for candidate in [here, *here.parents]:
        if any((candidate / name / ".git").exists() for name in REPO_NAMES):
            return candidate
    # A standalone clone keeps the gate inside the repository itself.
    if (here.parent / ".git").exists():
        return here.parent.parent
    override = os.environ.get("DRONE_WORKSPACE")
    if override:
        return Path(override).resolve()
    raise GateError("cannot locate workspace root (expected a backend/ or docs/ git repository)")


WORKSPACE = find_workspace()


def _is_wrapper(path: Path) -> bool:
    try:
        return WRAPPER_MARKER in path.read_bytes()[:512]
    except OSError:
        return False


def real_git() -> str:
    configured = os.environ.get("DRONE_REAL_GIT")
    if configured and Path(configured).exists():
        return configured
    suffixes = [".exe", ""] if os.name == "nt" else [""]
    for directory in os.environ.get("PATH", "").split(os.pathsep):
        if not directory:
            continue
        for suffix in suffixes:
            candidate = Path(directory) / f"git{suffix}"
            if candidate.is_file() and not _is_wrapper(candidate):
                return str(candidate)
    raise GateError("real git executable not found (set DRONE_REAL_GIT)")


def git(repo: Path, *args: str, check: bool = False, env: dict | None = None) -> subprocess.CompletedProcess:
    command = [real_git(), "-C", str(repo), *args]
    result = subprocess.run(
        command,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        env=env,
    )
    if check and result.returncode != 0:
        raise GateError(f"git {' '.join(args)} failed in {repo}:\n{result.stdout}{result.stderr}")
    return result


def git_text(repo: Path, *args: str) -> str:
    return git(repo, *args, check=True).stdout.strip()


def repo_path(name: str) -> Path:
    return WORKSPACE / name


def existing_repos() -> list[Path]:
    return [repo_path(name) for name in REPO_NAMES if (repo_path(name) / ".git").exists()]


def default_branch(repo: Path) -> str:
    result = git(repo, "symbolic-ref", "--short", "refs/remotes/origin/HEAD")
    if result.returncode == 0 and result.stdout.strip():
        return result.stdout.strip().split("/", 1)[-1]
    for candidate in ("main", "master"):
        if git(repo, "rev-parse", "--verify", f"refs/remotes/origin/{candidate}").returncode == 0:
            return candidate
    raise GateError(f"{repo.name}: cannot determine default branch")


def current_branch(repo: Path) -> str | None:
    result = git(repo, "rev-parse", "--abbrev-ref", "HEAD")
    if result.returncode != 0:
        return None
    name = result.stdout.strip()
    return None if name == "HEAD" else name


def rev_parse(repo: Path, ref: str) -> str:
    return git_text(repo, "rev-parse", ref)


def is_dirty(repo: Path) -> bool:
    return bool(git_text(repo, "status", "--porcelain"))


def ahead_count(repo: Path, upstream: str) -> int:
    result = git(repo, "rev-list", "--count", f"{upstream}..HEAD")
    if result.returncode != 0:
        return 0
    try:
        return int(result.stdout.strip() or "0")
    except ValueError:
        return 0


def stamp_path(repo: Path) -> Path:
    return repo / ".git" / "gate" / "approved.json"


def load_stamp(repo: Path) -> dict | None:
    path = stamp_path(repo)
    if not path.is_file():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def write_stamp(repo: Path, data: dict) -> None:
    path = stamp_path(repo)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def clear_stamp(repo: Path) -> None:
    try:
        stamp_path(repo).unlink()
    except OSError:
        pass


def fetch(repo: Path, attempts: int = 3) -> None:
    last = None
    for attempt in range(1, attempts + 1):
        result = git(repo, "fetch", "origin", "--prune")
        if result.returncode == 0:
            return
        last = result
        print(f"[gate] fetch attempt {attempt}/{attempts} failed for {repo.name}", file=sys.stderr)
        if attempt < attempts:
            time.sleep(2)
    detail = (last.stderr or last.stdout or "").strip() if last else ""
    raise GateError(f"{repo.name}: git fetch failed after {attempts} attempts.\n{detail}")


def sensitive_files(paths: list[str]) -> list[str]:
    return [path for path in paths if any(pattern.search(path) for pattern in SENSITIVE_PATTERNS)]


def backup_local_state(repo: Path) -> str | None:
    """Create a local backup branch containing the current HEAD and worktree."""
    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    branch = f"backup/{repo.name}-{timestamp}"
    original = current_branch(repo)
    result = git(repo, "checkout", "-b", branch)
    if result.returncode != 0:
        raise GateError(f"{repo.name}: cannot create backup branch {branch}:\n{result.stderr}")
    try:
        staged = git(repo, "add", "-A")
        if staged.returncode != 0:
            raise GateError(f"{repo.name}: git add failed:\n{staged.stderr}")
        names = [n for n in git_text(repo, "diff", "--cached", "--name-only").splitlines() if n]
        blocked = sensitive_files(names)
        if blocked:
            raise GateError(
                f"{repo.name}: refusing to back up secret-bearing files: {', '.join(blocked)}"
            )
        if names:
            commit = git(repo, "commit", "-m", "backup: local changes before sync")
            if commit.returncode != 0:
                raise GateError(f"{repo.name}: backup commit failed:\n{commit.stderr}")
        return branch
    except Exception:
        # Restore the index and the original branch; working tree changes are untouched.
        git(repo, "reset")
        if original:
            git(repo, "checkout", original)
        else:
            git(repo, "checkout", "--detach", "HEAD")
        clear_stamp(repo)
        raise


def sync_repo(repo: Path, assume_yes: bool) -> dict:
    print(f"[gate] syncing {repo.name} ...")
    fetch(repo)
    branch = default_branch(repo)
    remote_ref = f"origin/{branch}"
    remote_sha = rev_parse(repo, remote_ref)
    head_sha = rev_parse(repo, "HEAD")
    dirty = is_dirty(repo)
    ahead = ahead_count(repo, remote_ref)

    backup_branch = None
    if dirty or ahead:
        if not assume_yes:
            raise GateError(
                f"{repo.name}: local changes or unpushed commits detected "
                f"(dirty={dirty}, ahead={ahead}). Re-run with --yes to back them up "
                f"to backup/{repo.name}-<timestamp> and reset to {remote_ref}."
            )
        print(
            f"[gate]   backing up local state to backup/{repo.name}-<timestamp> "
            f"before resetting {head_sha[:12]} -> {remote_sha[:12]}"
        )
        backup_branch = backup_local_state(repo)

    checkout = git(repo, "checkout", "-f", "-B", branch, remote_ref)
    if checkout.returncode != 0:
        raise GateError(f"{repo.name}: cannot switch to {remote_ref}:\n{checkout.stderr}")
    reset = git(repo, "reset", "--hard", remote_ref)
    if reset.returncode != 0:
        raise GateError(f"{repo.name}: reset --hard failed:\n{reset.stderr}")

    head_after = rev_parse(repo, "HEAD")
    if head_after != remote_sha:
        raise GateError(f"{repo.name}: HEAD ({head_after}) != {remote_ref} ({remote_sha}) after reset")
    if is_dirty(repo):
        raise GateError(f"{repo.name}: working tree still dirty after reset")

    stamp = {
        "repo": repo.name,
        "head": head_after,
        "origin_default": remote_sha,
        "branch": branch,
        "backup_branch": backup_branch,
        "synced_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    }
    write_stamp(repo, stamp)
    print(
        f"[gate]   {repo.name}: synced to {branch}@{head_after[:12]}"
        + (f" (backup: {backup_branch})" if backup_branch else "")
    )
    return stamp


def check_repo(repo: Path, allow_dirty: bool) -> tuple[bool, str]:
    stamp = load_stamp(repo)
    if not stamp:
        return False, "stamp missing (run: python docs/tools/gate.py sync)"
    try:
        synced_at = datetime.fromisoformat(stamp["synced_at"])
    except (KeyError, ValueError):
        return False, "stamp is malformed (run sync again)"
    age = (datetime.now(timezone.utc) - synced_at).total_seconds()
    if age > STAMP_MAX_AGE_SECONDS:
        return False, f"stamp is {age / 3600:.1f}h old (limit {STAMP_MAX_AGE_SECONDS // 3600}h)"
    branch = default_branch(repo)
    remote_ref = f"origin/{branch}"
    result = git(repo, "rev-parse", "--verify", remote_ref)
    if result.returncode != 0:
        return False, f"cannot resolve {remote_ref}"
    remote_sha = result.stdout.strip()
    if remote_sha != stamp.get("origin_default"):
        return False, f"{remote_ref} advanced since sync ({stamp.get('origin_default', '?')[:12]} -> {remote_sha[:12]})"
    head = rev_parse(repo, "HEAD")
    base = stamp.get("head", "")
    if not base or git(repo, "merge-base", "--is-ancestor", base, head).returncode != 0:
        return False, f"current HEAD {head[:12]} is not based on the synced commit {base[:12]}"
    if not allow_dirty and is_dirty(repo):
        return False, "working tree has uncommitted changes"
    return True, "ok"


def report_blocked(repo: Path, reason: str) -> None:
    stamp = load_stamp(repo) or {}
    branch = default_branch(repo) if (repo / ".git").exists() else "main"
    remote = git(repo, "rev-parse", "--short", f"origin/{branch}")
    head = git(repo, "rev-parse", "--short", "HEAD")
    print(
        f"[gate] BLOCKED: {repo.name} 未同步到最新代码。\n"
        f"  HEAD        = {head.stdout.strip() or '?'}\n"
        f"  origin/{branch:<6}= {remote.stdout.strip() or '?'}\n"
        f"  stamp       = {stamp.get('synced_at', 'missing')} ({reason})\n"
        f"请先运行: python {Path(__file__).as_posix()} sync --all\n"
        f"本地未提交改动会先保存到 backup/{repo.name}-<timestamp>，不会丢失。",
        file=sys.stderr,
    )


def cmd_sync(args: argparse.Namespace) -> int:
    repos = existing_repos()
    if not repos:
        print("[gate] no repositories found", file=sys.stderr)
        return 1
    failed = []
    for repo in repos:
        try:
            sync_repo(repo, assume_yes=args.yes)
        except GateError as error:
            failed.append(str(error))
            print(f"[gate] FAILED: {error}", file=sys.stderr)
    if failed:
        return 1
    print("[gate] all repositories synced")
    return 0


def cmd_check(args: argparse.Namespace) -> int:
    repos = existing_repos()
    problems = []
    for repo in repos:
        ok, reason = check_repo(repo, allow_dirty=args.allow_dirty)
        if not ok:
            problems.append(repo)
            report_blocked(repo, reason)
        else:
            print(f"[gate] {repo.name}: ok")
    return 2 if problems else 0


def cwd_repo() -> Path:
    cwd = Path.cwd().resolve()
    for name in REPO_NAMES:
        candidate = (WORKSPACE / name).resolve()
        try:
            cwd.relative_to(candidate)
            return candidate
        except ValueError:
            continue
    if (WORKSPACE / ".git").exists():
        return WORKSPACE
    return existing_repos()[0]


def cmd_scope(args: argparse.Namespace) -> int:
    repo = cwd_repo()
    base = args.base
    merge_base = git_text(repo, "merge-base", base, "HEAD")
    committed = [p for p in git_text(repo, "diff", "--name-only", f"{merge_base}..HEAD").splitlines() if p]
    staged = [p for p in git_text(repo, "diff", "--cached", "--name-only").splitlines() if p]
    unstaged = [p for p in git_text(repo, "diff", "--name-only").splitlines() if p]
    untracked = [p for p in git_text(repo, "ls-files", "--others", "--exclude-standard").splitlines() if p]
    payload = {
        "repo": repo.name,
        "base": base,
        "merge_base": merge_base,
        "committed": committed,
        "staged": staged,
        "unstaged": unstaged,
        "untracked": untracked,
    }
    if args.json:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        for key in ("committed", "staged", "unstaged", "untracked"):
            for path in payload[key]:
                print(f"{key:10} {path}")
    return 0


def cmd_check_ci(args: argparse.Namespace) -> int:
    repo = cwd_repo()
    result = git(repo, "merge-base", "--is-ancestor", args.base, "HEAD")
    if result.returncode != 0:
        print(
            f"[gate] BLOCKED: branch is not based on {args.base}; "
            f"rebase or merge the latest default branch first.",
            file=sys.stderr,
        )
        return 2
    print(f"[gate] {args.base} is an ancestor of HEAD")
    return 0


def protected_repo_for(path: Path) -> Path | None:
    resolved = path.resolve()
    for name in REPO_NAMES:
        candidate = (WORKSPACE / name).resolve()
        try:
            resolved.relative_to(candidate)
            return candidate
        except ValueError:
            continue
    return None


def parse_git_args(argv: list[str]) -> tuple[Path | None, str | None, list[str]]:
    directory = None
    index = 0
    while index < len(argv):
        arg = argv[index]
        if arg in GLOBAL_OPTIONS_WITH_VALUE and index + 1 < len(argv):
            if arg == "-C":
                directory = Path(argv[index + 1])
            index += 2
            continue
        if arg.startswith("-"):
            index += 1
            continue
        return directory, arg, argv
    return directory, None, argv


def cmd_git(args: argparse.Namespace) -> int:
    argv = list(args.git_args)
    if argv and argv[0] == "--":
        argv = argv[1:]
    directory, subcommand, _ = parse_git_args(argv)
    cwd = (directory or Path.cwd()).resolve()
    repo = protected_repo_for(cwd)
    if repo is not None and subcommand not in STALE_ALLOWED_SUBCOMMANDS:
        ok, reason = check_repo(repo, allow_dirty=True)
        if not ok:
            report_blocked(repo, reason)
            return 2
    command = [real_git()]
    if directory is not None:
        command.extend(["-C", str(directory)])
    command.extend(argv)
    return subprocess.call(command)


def install_hooks(repo: Path) -> None:
    hooks_dir = repo / ".githooks"
    hooks_dir.mkdir(parents=True, exist_ok=True)
    local_gate = repo / "tools" / "gate.py"
    gate_path = local_gate.resolve() if local_gate.is_file() else Path(__file__).resolve()
    for hook_name in ("pre-commit", "pre-push"):
        hook = hooks_dir / hook_name
        content = (
            "#!/bin/sh\n"
            "# DRONE_GATE_HOOK\n"
            f'exec python "{gate_path.as_posix()}" hook {hook_name}\n'
        )
        hook.write_text(content, encoding="utf-8", newline="\n")
        try:
            hook.chmod(0o755)
        except OSError:
            pass
    result = git(repo, "config", "core.hooksPath", ".githooks")
    if result.returncode != 0:
        raise GateError(f"{repo.name}: cannot set core.hooksPath:\n{result.stderr}")


def install_wrapper() -> Path:
    bin_dir = WORKSPACE / "tools" / "gate-bin"
    bin_dir.mkdir(parents=True, exist_ok=True)
    gate_path = Path(__file__).resolve()
    sh_wrapper = bin_dir / "git"
    sh_wrapper.write_text(
        "#!/bin/sh\n"
        "# DRONE_GATE_WRAPPER\n"
        'PYTHON="${PYTHON:-python}"\n'
        f'exec "$PYTHON" "{gate_path.as_posix()}" git "$@"\n',
        encoding="utf-8",
        newline="\n",
    )
    try:
        sh_wrapper.chmod(0o755)
    except OSError:
        pass
    cmd_wrapper = bin_dir / "git.cmd"
    cmd_wrapper.write_text(
        "@echo off\r\n"
        "rem DRONE_GATE_WRAPPER\r\n"
        f'python "{gate_path.as_posix()}" git %*\r\n',
        encoding="utf-8",
        newline="",
    )
    return bin_dir


def cmd_install(_args: argparse.Namespace) -> int:
    for repo in existing_repos():
        install_hooks(repo)
        print(f"[gate] installed hooks in {repo.name}")
    bin_dir = install_wrapper()
    print(f"[gate] wrapper installed at {bin_dir}")
    print(f"[gate] prepend it to PATH: export PATH=\"{bin_dir.as_posix()}:$PATH\"")
    return 0


def cmd_hook(args: argparse.Namespace) -> int:
    name = args.hook_name
    if name in ("pre-commit",):
        return cmd_check(argparse.Namespace(allow_dirty=True))
    if name == "pre-push":
        repos = existing_repos()
        for repo in repos:
            try:
                fetch(repo)
            except GateError as error:
                print(f"[gate] BLOCKED: {error}", file=sys.stderr)
                return 2
            branch = default_branch(repo)
            remote_ref = f"origin/{branch}"
            if git(repo, "merge-base", "--is-ancestor", remote_ref, "HEAD").returncode != 0:
                print(
                    f"[gate] BLOCKED: {repo.name} is behind {remote_ref}. "
                    f"Run: python {Path(__file__).as_posix()} sync --all",
                    file=sys.stderr,
                )
                return 2
            ok, reason = check_repo(repo, allow_dirty=True)
            if not ok:
                report_blocked(repo, reason)
                return 2
        return 0
    # post-checkout / post-merge / post-rewrite: stamps are ancestry-based, nothing to do.
    return 0


def cmd_selftest(_args: argparse.Namespace) -> int:
    failures = []

    def check(name: str, condition: bool, detail: str = "") -> None:
        print(f"[selftest] {'PASS' if condition else 'FAIL'} {name}" + (f" ({detail})" if detail and not condition else ""))
        if not condition:
            failures.append(name)

    with tempfile.TemporaryDirectory(prefix="drone-gate-selftest-") as temp:
        root = Path(temp)
        origin = root / "origin.git"
        seed = root / "seed"
        work = root / "work"

        def run(cwd: Path, *command: str) -> subprocess.CompletedProcess:
            return subprocess.run(
                command,
                cwd=str(cwd),
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
            )

        git_exe = real_git()
        run(root, git_exe, "init", "--bare", "-b", "main", str(origin))
        run(root, git_exe, "init", "-b", "main", str(seed))
        run(seed, git_exe, "config", "user.email", "selftest@example.com")
        run(seed, git_exe, "config", "user.name", "Gate Selftest")
        (seed / "file.txt").write_text("one\n", encoding="utf-8")
        run(seed, git_exe, "add", ".")
        run(seed, git_exe, "commit", "-m", "initial")
        run(seed, git_exe, "remote", "add", "origin", str(origin))
        run(seed, git_exe, "push", "-u", "origin", "main")
        run(root, git_exe, "clone", str(origin), str(work))
        run(work, git_exe, "config", "user.email", "selftest@example.com")
        run(work, git_exe, "config", "user.name", "Gate Selftest")

        # 1. dirty worktree is backed up and the tree is reset to origin/main
        (work / "file.txt").write_text("local change\n", encoding="utf-8")
        (work / "new.txt").write_text("untracked\n", encoding="utf-8")
        stamp = sync_repo(work, assume_yes=True)
        branches = run(work, git_exe, "branch", "--list", "backup/*").stdout
        check("backup branch created", "backup/work-" in branches, branches)
        check("head equals origin/main", rev_parse(work, "HEAD") == rev_parse(work, "origin/main"))
        check("worktree clean after sync", not is_dirty(work))
        check("stamp written", bool(stamp.get("head")))
        backup_branch = stamp.get("backup_branch")
        if backup_branch:
            show = run(work, git_exe, "show", f"{backup_branch}:file.txt").stdout.strip()
            check("backup contains local change", show == "local change", show)

        # 2. an advanced remote invalidates the stamp
        (seed / "file.txt").write_text("two\n", encoding="utf-8")
        run(seed, git_exe, "add", ".")
        run(seed, git_exe, "commit", "-m", "remote update")
        run(seed, git_exe, "push")
        fetch(work)
        ok, reason = check_repo(work, allow_dirty=True)
        check("advanced remote blocks check", not ok and "advanced" in reason, reason)

        # 3. a failing fetch never writes a stamp
        clear_stamp(work)
        run(work, git_exe, "remote", "set-url", "origin", str(root / "missing.git"))
        try:
            fetch(work, attempts=1)
            fetch_failed = False
        except GateError:
            fetch_failed = True
        check("fetch failure raises", fetch_failed)
        check("no stamp after fetch failure", load_stamp(work) is None)

        # 4. sensitive files are refused during backup
        run(work, git_exe, "remote", "set-url", "origin", str(origin))
        (work / "application-dev.yml").write_text("secret: value\n", encoding="utf-8")
        try:
            backup_local_state(work)
            refused = False
        except GateError:
            refused = True
        check("sensitive file refused", refused)
        run(work, git_exe, "checkout", "main")
        run(work, git_exe, "reset", "--hard")

        # 5. stale subcommand policy
        check("diff blocked when stale", "diff" not in STALE_ALLOWED_SUBCOMMANDS)
        check("status allowed when stale", "status" in STALE_ALLOWED_SUBCOMMANDS)

    if failures:
        print(f"[selftest] {len(failures)} failure(s): {', '.join(failures)}", file=sys.stderr)
        return 1
    print("[selftest] all checks passed")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="gate.py", description="Project sync gate")
    sub = parser.add_subparsers(dest="command", required=True)

    sync = sub.add_parser("sync", help="back up local changes and align to origin/<default>")
    sync.add_argument("--all", action="store_true", help="sync all repositories (default)")
    sync.add_argument("--yes", action="store_true", help="confirm backup and reset")
    sync.set_defaults(func=cmd_sync)

    check = sub.add_parser("check", help="verify the tree is based on the synced remote")
    check.add_argument("--allow-dirty", action="store_true", help="ignore uncommitted changes")
    check.set_defaults(func=cmd_check)

    scope = sub.add_parser("scope", help="list changed paths since a merge base")
    scope.add_argument("--base", required=True)
    scope.add_argument("--json", action="store_true")
    scope.set_defaults(func=cmd_scope)

    ci = sub.add_parser("check-ci", help="CI: require the base ref to be an ancestor of HEAD")
    ci.add_argument("--base", required=True)
    ci.set_defaults(func=cmd_check_ci)

    install = sub.add_parser("install", help="install git hooks and the git wrapper")
    install.set_defaults(func=cmd_install)

    wrapper = sub.add_parser("git", help="git wrapper entry")
    wrapper.add_argument("git_args", nargs=argparse.REMAINDER)
    wrapper.set_defaults(func=cmd_git)

    hook = sub.add_parser("hook", help="git hook entry")
    hook.add_argument("hook_name")
    hook.set_defaults(func=cmd_hook)

    selftest = sub.add_parser("selftest", help="run gate self-tests")
    selftest.set_defaults(func=cmd_selftest)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return int(args.func(args) or 0)
    except GateError as error:
        print(f"[gate] ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
