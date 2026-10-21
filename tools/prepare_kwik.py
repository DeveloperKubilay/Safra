import pathlib
import subprocess
import sys

root = pathlib.Path(__file__).resolve().parents[1]
target = pathlib.Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else root.parent / "kwik"
repository = "https://github.com/DeveloperKubilay/kwik.git"
revision = "47c9557879bbac8873d8b17940fac5884805371c"


def git(*args, check=True):
    return subprocess.run(["git", "-C", str(target), *args], check=check, capture_output=True, text=True)


if not target.exists():
    subprocess.run(["git", "clone", "--filter=blob:none", "--no-checkout", "--single-branch", "--branch", "v0.12.1-maintenance.1", repository, str(target)], check=True)
    git("checkout", "--detach", revision)
if git("rev-parse", "--show-toplevel").stdout.strip().replace("\\", "/").lower() != target.as_posix().lower():
    raise SystemExit("Destination is not the expected Git checkout")
if git("status", "--porcelain").stdout.strip() or git("rev-parse", "HEAD").stdout.strip() != revision:
    raise SystemExit("Kwik sources differ from the pinned release; files were preserved. Use a fresh target directory or select the pinned revision.")
print(f"Kwik 0.12.1-maintenance.1 is ready at {revision}: {target}")
