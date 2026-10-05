"""Independent synthetic GZIP/Protobuf .pindobk fixture; never used by production.

libargon2 reference implementation + cryptography/OpenSSL. Fixed entropy and public
password are exclusively for a reproducible test. No real user data is read.
"""
import ctypes
import gzip
import hashlib
import pathlib
import struct

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

# Hand-encoded existing schema: Backup.manga { source=1, url, title, isPrivate=true }.
manga = b"\x08\x01\x12\x0a/synthetic\x1a\x17Private synthetic title\xc0\x3e\x01"
protobuf = bytes([10, len(manga)]) + manga
payload = gzip.compress(protobuf, mtime=0)
password = "Senha sintética 🔒\u0000 e\u0301".encode("utf-8")
salt, nonce = bytes(range(16)), bytes(range(16, 28))
argon = ctypes.CDLL("libargon2.so.1")
argon.argon2id_hash_raw.argtypes = [
    ctypes.c_uint32, ctypes.c_uint32, ctypes.c_uint32,
    ctypes.c_void_p, ctypes.c_size_t, ctypes.c_void_p, ctypes.c_size_t,
    ctypes.c_void_p, ctypes.c_size_t,
]
argon.argon2id_hash_raw.restype = ctypes.c_int
key = ctypes.create_string_buffer(32)
assert argon.argon2id_hash_raw(3, 65536, 4, password, len(password), salt, 16, key, 32) == 0
header = struct.pack(
    ">8sHHBBBBIIIQ16s12s", b"PINDOBK\0", 1, 64, 1, 1, 0x13, 0,
    65536, 3, 4, len(payload), salt, nonce,
)
file = header + AESGCM(key.raw).encrypt(nonce, payload, header)
pathlib.Path(__file__).with_name("private-gzip-v1.pindobk").write_bytes(file)
print(hashlib.sha256(file).hexdigest())
