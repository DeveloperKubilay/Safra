import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
VARIANTS = ("1_20", "1_20_2", "1_20_3")
JAVA_HOME = Path(os.environ["JAVA_HOME"]) if "JAVA_HOME" in os.environ else None
JAVAC = str(JAVA_HOME / "bin" / "javac.exe") if JAVA_HOME else shutil.which("javac")
JAVA = str(JAVA_HOME / "bin" / "java.exe") if JAVA_HOME else shutil.which("java")
PARAMETERS = "Screen parent, Minecraft client, ServerAddress address, ServerData data, boolean quickPlay"
ARGUMENT_CHECK = """
        if (parent != expectedParent || client != expectedClient || address != expectedAddress
            || data != expectedData || !quickPlay) throw new AssertionError("Arguments changed");
        called = true;
"""
CALLBACK = f"""
    private static void safra$rewriteP2pConnection({PARAMETERS}, CallbackInfo callback) {{
        calledCallback = true;
    }}
"""
STUBS = {
    "net/minecraft/client/Minecraft.java": "package net.minecraft.client; public class Minecraft {}",
    "net/minecraft/client/gui/screens/Screen.java": "package net.minecraft.client.gui.screens; public class Screen {}",
    "net/minecraft/client/multiplayer/ServerData.java": "package net.minecraft.client.multiplayer; public class ServerData {}",
    "net/minecraft/client/multiplayer/resolver/ServerAddress.java": "package net.minecraft.client.multiplayer.resolver; public class ServerAddress {}",
    "com/mojang/blaze3d/platform/Window.java": "package com.mojang.blaze3d.platform; public class Window {}",
}
SCREEN_TEMPLATE = """
package net.minecraft.client.gui.screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
public class ConnectScreen {
    public static boolean called;
    public static boolean calledCallback;
    public static final Screen expectedParent = new Screen();
    public static final Minecraft expectedClient = new Minecraft();
    public static final ServerAddress expectedAddress = new ServerAddress();
    public static final ServerData expectedData = new ServerData();
    private static class CallbackInfo {}
    public static class TransferState {}
    %s
}
"""
RUNNER_TEMPLATE = """
import net.minecraft.client.gui.screens.ConnectScreen;
import org.developerkubilay.safra.client.p2p.ForgeVersionCompat;
public class DispatchTest {
    public static void main(String[] args) {
        boolean rejected = false;
        try {
            ForgeVersionCompat.startConnect(ConnectScreen.expectedParent, ConnectScreen.expectedClient,
                ConnectScreen.expectedAddress, ConnectScreen.expectedData, true);
        } catch (IllegalStateException exception) {
            if (!exception.getMessage().equals("Could not find a compatible ConnectScreen.startConnecting method")) {
                throw exception;
            }
            rejected = true;
        }
        if (rejected != %s || ConnectScreen.called != %s || ConnectScreen.calledCallback) {
            throw new AssertionError("Wrong connection method selected: rejected=" + rejected
                + ", called=" + ConnectScreen.called + ", callback=" + ConnectScreen.calledCallback);
        }
    }
}
"""


class ForgeConnectDispatchTest(unittest.TestCase):
    def run_case(self, variant, methods, should_connect):
        build = ROOT / "build" / "forge-dispatch-tests"
        build.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=build) as directory:
            directory = Path(directory)
            sources = dict(STUBS)
            helper = ROOT / "forge/src/main/versionSplit/ForgeVersionCompat" / variant / "org/developerkubilay/safra/client/p2p/ForgeVersionCompat.java"
            sources["org/developerkubilay/safra/client/p2p/ForgeVersionCompat.java"] = helper.read_text(encoding="utf-8")
            sources["net/minecraft/client/gui/screens/ConnectScreen.java"] = SCREEN_TEMPLATE % methods
            sources["DispatchTest.java"] = RUNNER_TEMPLATE % (
                str(not should_connect).lower(), str(should_connect).lower()
            )
            paths = []
            for relative, content in sources.items():
                path = directory / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
                paths.append(str(path))
            classes = directory / "classes"
            subprocess.run([JAVAC, "--release", "17", "-encoding", "UTF-8", "-d", str(classes), *paths], check=True)
            subprocess.run([JAVA, "-cp", str(classes), "DispatchTest"], check=True)

    def test_public_entrypoint_with_mixin_callback(self):
        for variant in VARIANTS:
            for name in ("startConnecting", "m_278792_"):
                for sixth in (False, True):
                    with self.subTest(variant=variant, name=name, sixth=sixth):
                        extra = ", TransferState state" if sixth else ""
                        check = 'if (state != null) throw new AssertionError("Non-null transfer state");' if sixth else ""
                        entrypoint = f"public static void {name}({PARAMETERS}{extra}) {{{ARGUMENT_CHECK}{check}}}"
                        self.run_case(variant, CALLBACK + entrypoint, True)

    def test_callback_is_not_an_entrypoint(self):
        for variant in VARIANTS:
            with self.subTest(variant=variant):
                self.run_case(variant, CALLBACK, False)

    def test_rejects_wrong_return_type(self):
        for variant in VARIANTS:
            with self.subTest(variant=variant):
                self.run_case(variant, f"public static int startConnecting({PARAMETERS}) {{ return 0; }}", False)

    def test_rejects_instance_method(self):
        for variant in VARIANTS:
            with self.subTest(variant=variant):
                self.run_case(variant, f"public void startConnecting({PARAMETERS}) {{ called = true; }}", False)

    def test_rejects_primitive_sixth_parameter(self):
        for variant in VARIANTS:
            with self.subTest(variant=variant):
                self.run_case(variant, f"public static void startConnecting({PARAMETERS}, int state) {{ called = true; }}", False)


if __name__ == "__main__":
    if not JAVA or not JAVAC:
        raise SystemExit("Set JAVA_HOME to a JDK 17 or newer")
    unittest.main()
