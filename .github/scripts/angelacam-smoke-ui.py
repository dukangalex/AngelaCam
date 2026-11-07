#!/usr/bin/env python3
import hashlib
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "com.dukangalex.angelacam"
PREFS_DIR = f"/data/data/{PACKAGE}/shared_prefs"
PREFS_PATH = f"{PREFS_DIR}/angelacam_settings.xml"
UPDATE_VERSION = "9.9.9"


def adb(*args, check=True):
    command = ["adb", *map(str, args)]
    print("$ " + " ".join(command), flush=True)
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    if result.stdout.strip():
        print(result.stdout.rstrip(), flush=True)
    if check and result.returncode:
        raise RuntimeError(f"command failed ({result.returncode}): {' '.join(command)}")
    return result.stdout


def wait_for_device(timeout=120):
    deadline = time.time() + timeout
    while time.time() < deadline:
        state = subprocess.run(["adb", "get-state"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        if state.returncode == 0 and state.stdout.strip() == "device":
            boot = subprocess.run(["adb", "shell", "getprop", "sys.boot_completed"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
            if boot.returncode == 0 and boot.stdout.strip() == "1":
                return
        time.sleep(2)
    raise TimeoutError("ADB did not return to the online, boot-complete state")


def seed_update_preferences():
    adb("root", check=False)
    wait_for_device()
    adb("shell", "pm", "grant", PACKAGE, "android.permission.CAMERA", check=False)
    adb("shell", "pm", "grant", PACKAGE, "android.permission.RECORD_AUDIO", check=False)
    xml = (
        '<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map>'
        '<boolean name="auto_update_check" value="false" />'
        f'<string name="available_update_version">{UPDATE_VERSION}</string>'
        '<string name="available_update_notes">smoke test</string>'
        f'<string name="available_update_download_url">https://github.com/dukangalex/AngelaCam/releases/download/v{UPDATE_VERSION}/app-release.apk</string>'
        f'<string name="available_update_release_url">https://github.com/dukangalex/AngelaCam/releases/tag/v{UPDATE_VERSION}</string>'
        '</map>'
    )
    local = Path("smoke-update-prefs.xml")
    local.write_text(xml, encoding="utf-8")
    adb("push", local, "/data/local/tmp/angelacam-smoke-prefs.xml")
    adb("shell", "mkdir", "-p", PREFS_DIR)
    adb("shell", "cp", "/data/local/tmp/angelacam-smoke-prefs.xml", PREFS_PATH)
    package_info = adb("shell", "pm", "list", "packages", "-U", PACKAGE)
    match = re.search(r"uid:(\d+)", package_info)
    if not match:
        raise RuntimeError("Could not resolve AngelaCam app uid for smoke preferences")
    uid = match.group(1)
    adb("shell", "chown", f"{uid}:{uid}", PREFS_PATH)
    adb("shell", "chmod", "600", PREFS_PATH)
    adb("shell", "restorecon", "-RF", PREFS_DIR, check=False)


def dump_ui(name, attempts=3):
    remote = f"/sdcard/{name}"
    last_error = ""
    for attempt in range(1, attempts + 1):
        # ADB can briefly lose the emulator transport between UI dump attempts.
        try:
            wait_for_device(timeout=60)
        except TimeoutError as exc:
            last_error = str(exc)
            if attempt < attempts:
                print(f"UI_DUMP_DEVICE_RETRY {attempt}/{attempts}: {last_error}", flush=True)
                continue
            break
        # UiAutomation can sporadically return a null root after waiting for idle.
        remove_output = adb("shell", "rm", "-f", remote, check=False)
        if "transport endpoint is not connected" in remove_output.lower():
            last_error = remove_output.strip()
            if attempt < attempts:
                print(f"UI_DUMP_DEVICE_RETRY {attempt}/{attempts}: {last_error}", flush=True)
                continue
            break
        output = adb("shell", "uiautomator", "dump", remote, check=False)
        if "UI hierchary dumped to:" not in output:
            last_error = output.strip() or "uiautomator produced no success marker"
        else:
            try:
                adb("pull", remote, name)
                return ET.parse(name).getroot()
            except (RuntimeError, ET.ParseError) as exc:
                last_error = str(exc)
        if attempt < attempts:
            print(f"UI_DUMP_RETRY {attempt}/{attempts}: {last_error}", flush=True)
            time.sleep(2)
    raise RuntimeError(f"UI hierarchy dump failed after {attempts} attempts: {last_error}")


def iter_nodes(root):
    return list(root.iter("node"))


def find_node(root, suffix):
    return next((n for n in iter_nodes(root) if n.get("resource-id", "").endswith(suffix)), None)


def recover_system_anr(root):
    nodes = iter_nodes(root)
    title = next((n for n in nodes if "isn't responding" in n.get("text", "").lower()), None)
    if title is None:
        return False
    wait = next((n for n in nodes if n.get("resource-id") == "android:id/aerr_wait"), None)
    if wait is None:
        raise RuntimeError("System UI ANR dialog appeared without its Wait button")
    bounds = [int(x) for x in re.findall(r"\d+", wait.get("bounds", ""))]
    if len(bounds) != 4:
        raise RuntimeError("Could not read bounds for System UI ANR Wait button")
    x, y = (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2
    print("System UI ANR dialog detected; tapping Wait to recover the emulator UI", flush=True)
    adb("shell", "input", "tap", x, y)
    time.sleep(10)
    return True


def click_view(resource_suffix, dump_name):
    for attempt in range(1, 7):
        root = dump_ui(dump_name)
        if recover_system_anr(root):
            continue
        node = find_node(root, "/" + resource_suffix)
        if node is not None:
            bounds = [int(x) for x in re.findall(r"\d+", node.get("bounds", ""))]
            if len(bounds) != 4:
                raise RuntimeError(f"Missing bounds for {resource_suffix}")
            x, y = (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2
            print(f"Clicking {resource_suffix} at {x},{y}", flush=True)
            adb("shell", "input", "tap", x, y)
            time.sleep(4)
            return
        labels = [(n.get("resource-id", ""), n.get("text", "")) for n in iter_nodes(root) if n.get("resource-id") or n.get("text")]
        print(f"Attempt {attempt}: {resource_suffix} not present; visible nodes: {labels[:30]}", flush=True)
        time.sleep(5)
    raise RuntimeError(f"Could not find UI control {resource_suffix}")


def save_screenshot(path):
    with open(path, "wb") as output:
        result = subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=output, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError("Could not capture emulator screenshot: " + result.stderr.decode(errors="replace"))


def main():
    apk = Path(sys.argv[1])
    if not apk.exists() or apk.stat().st_size < 1_000_000:
        raise RuntimeError("Release APK missing or too small; refusing to smoke-test an error page")
    with apk.open("rb") as f:
        digest = hashlib.sha256(f.read()).hexdigest()
    print(f"SMOKE_APK size={apk.stat().st_size} sha256={digest}", flush=True)
    with __import__("zipfile").ZipFile(apk) as archive:
        assert "AndroidManifest.xml" in archive.namelist()
        assert archive.testzip() is None

    main_source = Path("app/src/main/java/com/dukangalex/angelacam/MainActivity.kt").read_text(encoding="utf-8")
    installer_source = Path("app/src/main/java/com/dukangalex/angelacam/UpdateInstaller.kt").read_text(encoding="utf-8")
    gallery_source = Path("app/src/main/java/com/dukangalex/angelacam/GalleryActivity.kt").read_text(encoding="utf-8")
    external_view_source = Path("app/src/main/java/com/dukangalex/angelacam/ExternalViewActivity.kt").read_text(encoding="utf-8")
    manifest_source = Path("app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    assert "UpdateInstaller.openApkDownload(this, info)" in main_source
    assert "Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl))" in installer_source
    assert "Intent.ACTION_GET_CONTENT" in gallery_source
    assert "CaptureStore.list(this)" in gallery_source
    assert "FLAG_GRANT_READ_URI_PERMISSION" in gallery_source
    assert "MediaStore.ACTION_IMAGE_CAPTURE" in main_source
    assert "MediaStore.ACTION_VIDEO_CAPTURE" in main_source
    assert "MediaStore.EXTRA_OUTPUT" in main_source
    assert "android.media.action.IMAGE_CAPTURE_SECURE" not in manifest_source
    assert 'android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32"' in manifest_source
    assert 'android.permission.READ_MEDIA_IMAGES' in manifest_source
    assert 'android.permission.READ_MEDIA_VIDEO' in manifest_source
    assert 'android.permission.READ_MEDIA_VISUAL_USER_SELECTED' in manifest_source
    assert 'showMediaPermissionDisclosure' in gallery_source
    assert 'ContextCompat.checkSelfPermission' in gallery_source
    assert 'queryDeviceMedia(access)' in gallery_source
    assert 'Intent.ACTION_PICK' in gallery_source
    assert 'segments.indexOf("images")' in gallery_source
    assert 'segments.getOrNull(imagesIndex + 1) == "media"' in gallery_source
    assert 'setOf("content", "file")' in external_view_source
    assert 'setOnErrorListener' in external_view_source
    assert 'takeIf { it.isNotEmpty() }' in external_view_source
    print("BUILT_IN_PICKER_AND_SAFE_URI_SOURCE_OK", flush=True)
    print("DIRECT_APK_ACTION_SOURCE_OK", flush=True)

    adb("install", "-r", apk)
    seed_update_preferences()
    adb("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")
    time.sleep(20)
    root = dump_ui("smoke-home.xml")
    if recover_system_anr(root):
        time.sleep(5)
    save_screenshot("smoke-launch.png")

    click_view("btnSettings", "smoke-home.xml")
    click_view("btnAdvancedSettings", "smoke-quick-controls.xml")
    root = dump_ui("settings-ui.xml")
    if recover_system_anr(root):
        root = dump_ui("settings-ui.xml")

    gradle = Path("app/build.gradle.kts").read_text(encoding="utf-8")
    version_match = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
    if not version_match:
        raise RuntimeError("Could not determine installed version from Gradle config")
    current_version = version_match.group(1)
    container = find_node(root, "/updateStatusContainer")
    if container is None:
        raise RuntimeError("Settings UI does not expose updateStatusContainer")
    label = container.get("content-desc", "")
    if container.get("clickable") != "true":
        raise RuntimeError("Update status row is not clickable")
    if f"v{current_version}" not in label or UPDATE_VERSION not in label:
        raise RuntimeError(f"Row accessibility label does not report both versions: {label!r}")
    if "tap to download" not in label.lower() and "点击直接下载" not in label:
        raise RuntimeError(f"Row label does not promise a direct APK download: {label!r}")
    print("INSTALLED_VERSION_OK", f"v{current_version}", flush=True)
    print("UPDATE_ROW_VISIBLE_CLICKABLE_DIRECT_DOWNLOAD_OK", label, flush=True)
    save_screenshot("smoke-settings.png")

    for mime in ("image/jpeg", "video/mp4"):
        result = adb("shell", "cmd", "package", "query-activities", "--brief", "-a", "android.intent.action.VIEW", "-t", mime)
        if PACKAGE not in result:
            raise RuntimeError(f"AngelaCam VIEW intent filter missing for {mime}")
        print("VIEW_INTENT_OK", mime, flush=True)

    get_content = adb("shell", "cmd", "package", "query-activities", "--brief",
                      "-a", "android.intent.action.GET_CONTENT", "-t", "image/jpeg")
    if PACKAGE not in get_content:
        raise RuntimeError("AngelaCam image GET_CONTENT picker is missing")
    print("IMAGE_GET_CONTENT_INTENT_OK", flush=True)

    pick = adb("shell", "cmd", "package", "query-activities", "--brief",
               "-a", "android.intent.action.PICK", "-t", "image/jpeg")
    if PACKAGE not in pick:
        raise RuntimeError("AngelaCam image ACTION_PICK handler is missing")
    pick_collection = adb("shell", "cmd", "package", "query-activities", "--brief",
                          "-a", "android.intent.action.PICK",
                          "-d", "content://media/external/images/media")
    if PACKAGE not in pick_collection:
        raise RuntimeError("AngelaCam ACTION_PICK did not match the standard MediaStore image collection URI")
    print("IMAGE_PICK_INTENT_OK", flush=True)

    api_level = int(adb("shell", "getprop", "ro.build.version.sdk").strip())
    capture_actions = (
        "android.media.action.IMAGE_CAPTURE",
        "android.media.action.VIDEO_CAPTURE",
    )
    for action in capture_actions:
        result = adb("shell", "cmd", "package", "query-activities", "--brief", "-a", action)
        if api_level >= 30 and PACKAGE in result:
            raise RuntimeError(f"Android {api_level} incorrectly exposes AngelaCam for implicit {action}")
        if api_level <= 29 and PACKAGE not in result:
            raise RuntimeError(f"Android {api_level} should offer AngelaCam for {action}")
        print("CAMERA_INTENT_POLICY_OK", api_level, action, flush=True)

    secure_capture = adb("shell", "cmd", "package", "query-activities", "--brief",
                         "-a", "android.media.action.IMAGE_CAPTURE_SECURE")
    if PACKAGE in secure_capture:
        raise RuntimeError("AngelaCam must not advertise secure camera capture")
    print("SECURE_CAPTURE_NOT_ADVERTISED_OK", flush=True)

    if api_level >= 30:
        explicit = adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity",
                       "-a", "android.media.action.IMAGE_CAPTURE")
        if "Status: ok" not in explicit:
            raise RuntimeError("Explicit caller-targeted image capture entry did not start")
        print("EXPLICIT_CAMERA_INTENT_ENTRY_OK", api_level, flush=True)

    gallery_launch = adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.GalleryActivity",
                         "-a", "android.intent.action.GET_CONTENT", "-t", "image/jpeg",
                         "-c", "android.intent.category.OPENABLE")
    if "Status: ok" not in gallery_launch:
        raise RuntimeError("AngelaCam image picker activity did not start")
    time.sleep(3)
    permission_ui = dump_ui("gallery-permission-disclosure.xml")
    visible_text = " ".join(node.get("text", "") for node in iter_nodes(permission_ui))
    if ("To select device photos in AngelaCam's built-in gallery" not in visible_text and
            "为了在 AngelaCam 内置相册中选择设备照片" not in visible_text):
        raise RuntimeError("Image picker did not show its media-access disclosure before requesting permission")

    if api_level >= 34:
        permission_names = (
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
        )
    else:
        permission_names = ("android.permission.READ_EXTERNAL_STORAGE",)
    permission_dump = adb("shell", "dumpsys", "package", PACKAGE)
    for permission in permission_names:
        states = [
            line.strip().lower()
            for line in permission_dump.splitlines()
            if line.strip().startswith(f"{permission}:") and "granted=" in line
        ]
        if not states:
            raise RuntimeError(f"Could not determine runtime permission state: {permission}")
        if any("granted=true" in state for state in states):
            raise RuntimeError(f"Media permission was granted before user consent: {permission}")
        print("MEDIA_PERMISSION_NOT_GRANTED_OK", api_level, permission, flush=True)
    print("MEDIA_DISCLOSURE_BEFORE_RUNTIME_PERMISSION_OK", api_level, flush=True)


if __name__ == "__main__":
    main()
