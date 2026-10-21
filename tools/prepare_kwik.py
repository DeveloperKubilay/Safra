import pathlib
import subprocess
import sys

root = pathlib.Path(__file__).resolve().parents[1]
target = pathlib.Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else root.parent / "kwik"
revision = "4d5d40f540d70a1b50ea59651f9d36740e77f165"
patch = root / "gradle" / "kwik-adaptive.patch"


def git(*args, check=True):
    return subprocess.run(["git", "-C", str(target), *args], check=check, capture_output=True, text=True)


if not target.exists():
    subprocess.run(["git", "clone", "--branch", "v0.11", "--single-branch", "https://github.com/ptrd/kwik.git", str(target)], check=True)
if git("rev-parse", "--show-toplevel").stdout.strip().replace("\\", "/").lower() != target.as_posix().lower():
    raise SystemExit("Destination is not the expected Git checkout")
git("merge-base", "--is-ancestor", revision, "HEAD")
if git("apply", "--reverse", "--check", str(patch), check=False).returncode == 0:
    print(f"Kwik is ready: {target}")
elif git("status", "--porcelain").stdout.strip() or git("rev-parse", "HEAD").stdout.strip() != revision:
    raise SystemExit("Existing Kwik checkout differs; its files were preserved")
else:
    git("apply", "--check", str(patch))
    git("apply", str(patch))
    print(f"Prepared Kwik 0.11.0-safra.1: {target}")
