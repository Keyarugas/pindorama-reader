"""Offline reference fixtures. Run explicitly; never run by the application/tests.

Requires the reference libargon2 shared library and Python cryptography (OpenSSL).
ALL passwords, salts, nonces and payloads here are public synthetic test data.
Fixed randomness is only for reproducing these fixtures, never for production.
"""
import ctypes
import hashlib
import pathlib
import struct

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

root = pathlib.Path(__file__).resolve().parent
argon = ctypes.CDLL("libargon2.so.1")
argon.argon2id_hash_raw.argtypes = [
    ctypes.c_uint32, ctypes.c_uint32, ctypes.c_uint32,
    ctypes.c_void_p, ctypes.c_size_t, ctypes.c_void_p, ctypes.c_size_t,
    ctypes.c_void_p, ctypes.c_size_t,
]
argon.argon2id_hash_raw.restype = ctypes.c_int
password = "Senha sintética 🔒\u0000 e\u0301".encode("utf-8")
for name, payload, start in [
    ("binary-v1", bytes(range(256)) + b"Synthetic opaque payload\x00\xff", 0),
    ("empty-v1", b"", 32),
]:
    salt = bytes(range(start, start + 16))
    nonce = bytes(range(start + 16, start + 28))
    key = ctypes.create_string_buffer(32)
    assert argon.argon2id_hash_raw(3, 65536, 4, password, len(password), salt, len(salt), key, 32) == 0
    header = struct.pack(
        ">8sHHBBBBIIIQ16s12s", b"PINDOBK\0", 1, 64, 1, 1, 0x13, 0,
        65536, 3, 4, len(payload), salt, nonce,
    )
    envelope = header + AESGCM(key.raw).encrypt(nonce, payload, header)
    (root / (name + ".pindobk")).write_bytes(envelope)
    (root / (name + ".payload")).write_bytes(payload)
    print(name, "sha256=" + hashlib.sha256(envelope).hexdigest())
