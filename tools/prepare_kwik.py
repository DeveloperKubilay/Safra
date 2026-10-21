import pathlib
import subprocess
import sys

root = pathlib.Path(__file__).resolve().parents[1]
target = pathlib.Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else root.parent / "kwik"
repository = "https://github.com/DeveloperKubilay/kwik.git"
revision = "23bb551b2d5e89a1ab313fb830e981d65eb51f2a"


def git(*args, check=True):
    return subprocess.run(["git", "-C", str(target), *args], check=check, capture_output=True, text=True)


if not target.exists():
    subprocess.run(["git", "clone", "--filter=blob:none", "--no-checkout", "--single-branch", "--branch", "v0.11.0-safra.2", repository, str(target)], check=True)
    git("checkout", "--detach", revision)
if git("rev-parse", "--show-toplevel").stdout.strip().replace("\\", "/").lower() != target.as_posix().lower():
    raise SystemExit("Destination is not the expected Git checkout")
if git("status", "--porcelain").stdout.strip() or git("rev-parse", "HEAD").stdout.strip() != revision:
    raise SystemExit("Kwik sources differ from the pinned release; files were preserved. Use a fresh target directory or select the pinned revision.")
print(f"Kwik 0.11.0-safra.2 is ready at {revision}: {target}")
