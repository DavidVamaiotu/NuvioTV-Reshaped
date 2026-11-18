"""Compile the production DV result header and exercise its JNI bit contract on the host."""
import ctypes
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]

class DoviProcessResultTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        root = Path(cls.temp.name)
        source = root / "result.cpp"
        source.write_text('#include "dovi_process_result.h"\nextern "C" int64_t result(int32_t w, uint16_t c, uint16_t s) { return packProcessResult(w,c,s); }\n')
        binary = root / "result.so"
        subprocess.run(["c++", "-std=c++17", "-shared", "-fPIC", "-I" + str(ROOT / "app/src/main/cpp"), str(source), "-o", str(binary)], check=True)
        cls.lib = ctypes.CDLL(str(binary))
        cls.lib.result.argtypes = [ctypes.c_int32, ctypes.c_uint16, ctypes.c_uint16]
        cls.lib.result.restype = ctypes.c_int64

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def unpack(self, written, calls, successes):
        packed = self.lib.result(written, calls, successes)
        return ctypes.c_int32(packed).value, (packed >> 32) & 0xffff, (packed >> 48) & 0xffff

    def test_signed_capacity_and_length_survive_maximum_counters(self):
        for written in [-2147483648, -8192, 0, 8192, 2147483647]:
            self.assertEqual((written, 65535, 65535), self.unpack(written, 65535, 65535))

    def test_attempted_conversion_is_distinct_from_successful_conversion(self):
        self.assertEqual((0, 3, 0), self.unpack(0, 3, 0))
        self.assertEqual((2048, 3, 2), self.unpack(2048, 3, 2))
        self.assertEqual((2048, 0, 0), self.unpack(2048, 0, 0))

    def test_capacity_retry_reports_no_conversion_until_successful_write(self):
        self.assertEqual((-8192, 0, 0), self.unpack(-8192, 0, 0))
        self.assertEqual((8192, 1, 1), self.unpack(8192, 1, 1))

if __name__ == "__main__":
    unittest.main()
