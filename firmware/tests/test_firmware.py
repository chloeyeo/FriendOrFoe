"""실제 프로토콜 헤더/.ino를 호스트 대역으로 실행하는 회귀 시험."""
import hashlib
import hmac
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent


class FirmwareTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix="fof-tests-")
        cls.addClassCleanup(cls.directory.cleanup)
        compiler = os.environ.get("CXX") or shutil.which("clang++") or shutil.which("g++")
        if not compiler:
            raise RuntimeError("C++17 compiler required (CXX)")
        candidates = [os.environ.get("OPENSSL_PREFIX"), "/opt/homebrew/opt/openssl@3",
                      "/usr/local/opt/openssl@3", "/usr"]
        prefix = next((Path(p) for p in candidates if p and (Path(p) / "include/openssl/hmac.h").exists()), None)
        if prefix is None:
            raise RuntimeError("OpenSSL development library required (OPENSSL_PREFIX)")
        platform_flags = []
        if sys.platform == "darwin":
            sdk = Path(subprocess.check_output(["xcrun", "--show-sdk-path"], text=True).strip())
            # 일부 Command Line Tools는 libc++ 헤더를 SDK 안에만 제공합니다.
            cpp_headers = sdk / "usr/include/c++/v1"
            if cpp_headers.exists():
                platform_flags = ["-isystem", str(cpp_headers)]
        cls.executables = {}
        for source in ("protocol", "reader_cycle", "drone_cycle", "ble_framing"):
            output = Path(cls.directory.name) / source
            subprocess.run([
                compiler, "-std=c++17", "-Wall", "-Wextra", "-Werror",
                "-Wno-deprecated-declarations", "-fsanitize=address,undefined",
                *platform_flags,
                "-I", str(ROOT / "host"), "-I", str(ROOT.parent / "iff/src"),
                "-I", str(prefix / "include"), str(ROOT / f"{source}.cpp"),
                "-L", str(prefix / "lib"), f"-Wl,-rpath,{prefix / 'lib'}",
                "-lcrypto", "-o", str(output),
            ], check=True)
            cls.executables[source] = output

    def test_protocol_against_independent_python_vectors(self):
        lines = subprocess.check_output([self.executables["protocol"]], text=True).splitlines()
        key = bytes(range(32))  # 공개된 시험용 키이며 실제 보드용 키가 아닙니다.
        nonce = bytes(range(16))
        expected = []
        for kind, domain in ((1, b"CHALLENGE"), (2, b"RESPONSE")):
            body = struct.pack(">BBHH16s", 1, kind, 0x1234, 0x5678, nonce)
            tag = hmac.new(key, domain + body, hashlib.sha256).digest()[:16]
            expected.append((body + tag).hex())
        self.assertEqual(lines, expected)

    def test_reader_authentication_three_second_lock_and_duplicate_response(self):
        output = subprocess.check_output([self.executables["reader_cycle"], "--serial"], text=True)
        events = [json.loads(line) for line in output.splitlines() if line.startswith('{')]
        sent = {e['query']: e for e in events if e['event'] == 'TX_CHALLENGE'}
        received = [e for e in events if e['event'] == 'RX_RESPONSE']
        self.assertTrue(sent)
        self.assertTrue(received)
        self.assertEqual({e['result'] for e in received}, {
            'AUTH_FAILED', 'INVALID_MESSAGE', 'IGNORED_NONCE_MISMATCH', 'FRIEND',
            'IGNORED_NOT_WAITING', 'IGNORED_LATE_RESPONSE',
        })
        key = bytes(range(32))
        results = [e for e in events if e['event'] == 'RESULT' and not e['replay']]
        self.assertEqual(len(results), len({e['request_id'] for e in results}))
        self.assertTrue(any(e.get('reason') == 'BUSY' for e in events))
        self.assertTrue(any(e.get('reason') == 'COMMAND_TOO_LONG' for e in events))
        for event in events:
            self.assertNotIn('key', event)
            if 'raw' not in event:
                continue
            self.assertTrue(event['request_id'])
            raw = bytes.fromhex(event['raw'])
            self.assertEqual(len(raw), event['bytes'])
            if len(raw) != 38:
                self.assertNotIn('nonce', event)
                continue
            self.assertEqual(event['nonce'], raw[6:22].hex())
            self.assertEqual(event['tag'], raw[22:38].hex())
            if event['event'] == 'TX_CHALLENGE' or event['result'] == 'FRIEND':
                domain = b'CHALLENGE' if raw[1] == 1 else b'RESPONSE'
                self.assertEqual(raw[22:], hmac.new(key, domain + raw[:22], hashlib.sha256).digest()[:16])
            if event['result'] == 'FRIEND':
                self.assertEqual(event['nonce'], sent[event['query']]['nonce'])
                self.assertNotEqual(event['tag'], sent[event['query']]['tag'])

    def test_reader_key_missing_and_radio_initialization_failure(self):
        for option in ("--no-key", "--no-radio"):
            subprocess.run([self.executables["reader_cycle"], option], check=True)

    def test_ble_whole_message_advertisement_bounds_and_tampering(self):
        subprocess.run([self.executables["ble_framing"]], check=True)

    def test_drone_authentication_duplicate_and_silence(self):
        output = subprocess.check_output([self.executables["drone_cycle"]], text=True)
        events = [json.loads(line) for line in output.splitlines() if line.startswith('{')]
        self.assertTrue(any(e['event'] == 'STATUS' and e['role'] == 'drone' for e in events))
        received = [e for e in events if e['event'] == 'RX_CHALLENGE']
        self.assertTrue(any(e['result'] == 'AUTH_FAILED' for e in received))
        self.assertTrue(any(e['result'] == 'AUTHENTICATED' for e in received))
        for event in events:
            if 'raw' in event:
                raw = bytes.fromhex(event['raw'])
                self.assertEqual(event['bytes'], len(raw))
                self.assertEqual(event['nonce'], raw[6:22].hex())
                self.assertEqual(event['tag'], raw[22:].hex())


if __name__ == "__main__":
    unittest.main(verbosity=2)
