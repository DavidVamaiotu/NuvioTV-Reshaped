"""Host checks for the detached decoder: colour math and C/C++ syntax, without an Android SDK."""
import ctypes
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT / "app/src/main/cpp/seekpreview"
MPV_ABI_PIN = json.loads((NATIVE / "third_party/mpv-ffmpeg-abi.json").read_text())


class NativePreviewTest(unittest.TestCase):
    def test_mpv_dependency_and_header_abi_pin(self):
        gradle = (ROOT / "app/build.gradle.kts").read_text()
        version = re.search(r'io[.]github[.]abdallahmehiz:mpv-android-lib:([^"\s]+)', gradle)
        self.assertIsNotNone(version)
        self.assertEqual(MPV_ABI_PIN["dependency_version"], version.group(1),
                         "MPV updates require an audited FFmpeg ABI/header pin")
        headers = NATIVE / "third_party" / MPV_ABI_PIN["headers"]
        for library, expected in MPV_ABI_PIN["libraries"].items():
            text = "\n".join(p.read_text() for p in (headers / library).glob("*version*.h"))
            stem = library.upper()
            for component, value in expected.items():
                actual = re.search(r"#define\s+" + stem + "_VERSION_" + component.upper() + r"\s+(\d+)", text)
                self.assertIsNotNone(actual, (library, component))
                self.assertEqual(value, int(actual.group(1)), (library, component))
        self.assertIn("third_party/" + MPV_ABI_PIN["headers"], (NATIVE / "CMakeLists.txt").read_text())
        self.assertIn(MPV_ABI_PIN["native_marker"], (NATIVE / "thumb_backend_mpv.cpp").read_text())
        # When Gradle has resolved the artifact, verify its audited fingerprint too.
        # The dependency/header contract above also runs on SDK-free offline hosts.
        gradle_home = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
        artifacts = gradle_home / "caches/modules-2/files-2.1/io.github.abdallahmehiz/mpv-android-lib-android" / version.group(1)
        for artifact in artifacts.glob("*/*.aar"):
            self.assertEqual(MPV_ABI_PIN["artifact_sha256"], hashlib.sha256(artifact.read_bytes()).hexdigest())
            with zipfile.ZipFile(artifact) as native:
                for abi in ["armeabi-v7a", "arm64-v8a", "x86", "x86_64"]:
                    self.assertIn(MPV_ABI_PIN["runtime_version"].encode(), native.read("jni/" + abi + "/libavutil.so"))
                    self.assertIn("jni/" + abi + "/libmpv.so", native.namelist())
                    self.assertIn("jni/" + abi + "/libplayer.so", native.namelist())

    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.directory = Path(cls.temp.name)
        output = cls.directory / "colour.so"
        subprocess.run(["cc", "-std=c11", "-shared", "-fPIC", str(NATIVE / "thumbcolor.c"),
                        str(NATIVE / "thumbdovi.c"), "-lm", "-o", str(output)], check=True)
        cls.lib = ctypes.CDLL(str(output))

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_pq_reference_values(self):
        self.lib.td_pq_to_nits.argtypes = [ctypes.c_double]
        self.lib.td_pq_to_nits.restype = ctypes.c_double
        self.assertAlmostEqual(0, self.lib.td_pq_to_nits(0), places=6)
        self.assertAlmostEqual(100, self.lib.td_pq_to_nits(0.5080784215), places=3)
        self.assertAlmostEqual(10000, self.lib.td_pq_to_nits(1), places=3)

    def test_hdr_gray_ramp_stays_neutral_and_monotonic(self):
        class Params(ctypes.Structure):
            _fields_ = [("transfer", ctypes.c_int), ("peak", ctypes.c_float),
                        ("white", ctypes.c_float), ("bt2020", ctypes.c_int)]
        self.lib.tc_create.argtypes = [ctypes.POINTER(Params)]
        self.lib.tc_create.restype = ctypes.c_void_p
        self.lib.tc_free.argtypes = [ctypes.c_void_p]
        plane_type = ctypes.c_uint16 * 33
        source = plane_type(*(round(i * 65535 / 32) for i in range(33)))
        output = (ctypes.c_uint8 * (33 * 4))()
        self.lib.tc_convert.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_uint16), ctypes.c_int,
            ctypes.POINTER(ctypes.c_uint16), ctypes.c_int, ctypes.POINTER(ctypes.c_uint16), ctypes.c_int,
            ctypes.c_int, ctypes.c_int, ctypes.POINTER(ctypes.c_uint8), ctypes.c_int, ctypes.c_int]
        for transfer in (1, 2):
            handle = self.lib.tc_create(ctypes.byref(Params(transfer, 1000, 203, 1)))
            self.assertTrue(handle)
            try:
                self.lib.tc_convert(handle, source, 33, source, 33, source, 33, 33, 1, output, 132, 0)
                previous = -1
                for i in range(33):
                    r, g, b, alpha = output[i * 4:i * 4 + 4]
                    self.assertLessEqual(max(r, g, b) - min(r, g, b), 2)
                    self.assertGreaterEqual(r, previous)
                    self.assertEqual(255, alpha)
                    previous = r
            finally:
                self.lib.tc_free(handle)

    def test_decoder_allocation_limits(self):
        source = self.directory / "limits.cpp"
        source.write_text('#include "thumb_limits.h"\nextern "C" long long pixels(int w,int h) { return thumb::preview_max_pixels(w,h); }\n')
        output = self.directory / "limits.so"
        subprocess.run(["c++", "-std=c++17", "-shared", "-fPIC", "-I" + str(NATIVE), str(source), "-o", str(output)], check=True)
        limits = ctypes.CDLL(str(output))
        limits.pixels.argtypes = [ctypes.c_int, ctypes.c_int]
        limits.pixels.restype = ctypes.c_int64
        self.assertEqual(1920 * 1088, limits.pixels(1920, 1080))
        self.assertEqual(3840 * 2176, limits.pixels(3840, 2160))
        for width, height in [(0, 1080), (-1, 1080), (7680, 4320), (2147483647, 2147483647)]:
            self.assertEqual(0, limits.pixels(width, height))

    def test_native_backends_and_jni_syntax(self):
        includes = self.directory / "include"
        (includes / "android").mkdir(parents=True)
        (includes / "sys").mkdir()
        (includes / "android/log.h").write_text("#include <stddef.h>\n#define ANDROID_LOG_WARN 5\n#define ANDROID_LOG_INFO 4\nextern \"C\" int __android_log_print(int,const char*,const char*,...);\n")
        (includes / "sys/system_properties.h").write_text("#define PROP_VALUE_MAX 92\nextern \"C\" int __system_property_get(const char*,char*);\n")
        (includes / "android/bitmap.h").write_text("""#include <stdint.h>
struct AndroidBitmapInfo { uint32_t width,height,stride; int32_t format; uint32_t flags; };
#define ANDROID_BITMAP_RESULT_SUCCESS 0
#define ANDROID_BITMAP_FORMAT_RGBA_8888 1
#define ANDROID_BITMAP_FORMAT_RGB_565 4
extern "C" int AndroidBitmap_getInfo(JNIEnv*,jobject,AndroidBitmapInfo*);
extern "C" int AndroidBitmap_lockPixels(JNIEnv*,jobject,void**);
extern "C" int AndroidBitmap_unlockPixels(JNIEnv*,jobject);
""")
        # Syntax-only JNI declarations on JRE-only hosts. Android ABI compilation is checked by CI/NDK.
        (includes / "jni.h").write_text("""#include <stdint.h>
#include <stddef.h>
#define JNIEXPORT
#define JNICALL
#define JNI_TRUE 1
#define JNI_FALSE 0
#define JNI_ABORT 2
typedef unsigned char jboolean;
typedef signed char jbyte;
typedef int jint;
typedef int jsize;
typedef int64_t jlong;
typedef float jfloat;
typedef void* jobject;
typedef void* jclass;
typedef void* jstring;
typedef void* jbyteArray;
typedef void* jintArray;
struct JNIEnv {
jboolean ExceptionCheck();
const char* GetStringUTFChars(jstring,void*);
void ReleaseStringUTFChars(jstring,const char*);
jsize GetArrayLength(jbyteArray);
jbyte* GetByteArrayElements(jbyteArray,void*);
void ReleaseByteArrayElements(jbyteArray,jbyte*,int);
jstring NewStringUTF(const char*);
void GetByteArrayRegion(jbyteArray,int,int,jbyte*);
void SetIntArrayRegion(jintArray,int,int,const jint*);
void* GetDirectBufferAddress(jobject);
jlong GetDirectBufferCapacity(jobject);
};
""")
        common = ["c++", "-std=c++17", "-fsyntax-only", "-Wno-deprecated-declarations", "-I" + str(includes)]
        for file, headers in [("thumb_backend_ff60.cpp", "ffmpeg-n6.0"), ("thumb_backend_mpv.cpp", MPV_ABI_PIN["headers"])]:
            subprocess.run(common + ["-I" + str(NATIVE / "third_party" / headers), str(NATIVE / file)], check=True)
        subprocess.run(common + [str(NATIVE / "thumb_shim.cpp")], check=True)


if __name__ == "__main__":
    unittest.main()
