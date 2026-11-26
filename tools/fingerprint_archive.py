"""Administrator maintenance for a directly attached R503Pro, with the ESP32 disconnected.
Install pyfingerprint==1.5 and cryptography. Use the same roster and compatible sensor firmware
for recovery. The institution label must match on backup and restore. Archives preserve both
template slots for every enrolled student. The archive password is entered at the terminal.
Example: python fingerprint_archive.py backup COM4 institution-name fingerprint.spaf
"""
import argparse
import base64
import getpass
import json
import os
import tempfile
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.scrypt import Scrypt

MAGIC = b'SPAF0001'


def archive_key(password, salt):
    return Scrypt(salt=salt, length=32, n=2**15, r=8, p=1).derive(password.encode())


def occupied(sensor, capacity):
    slots = set()
    for page in range((min(601, capacity) + 255) // 256):
        bits = sensor.getTemplateIndex(page)
        if len(bits) != 256:
            raise ValueError('Incomplete sensor slot index')
        for i, used in enumerate(bits):
            slot = page * 256 + i
            if used and 1 <= slot < min(601, capacity):
                slots.add(slot)
    return slots


def read_slot(sensor, slot):
    sensor.loadTemplate(slot)
    data = bytes(sensor.downloadCharacteristics())
    if not 1 <= len(data) <= 8192:
        raise ValueError('Invalid template length')
    return data


def backup(sensor, institution, password, path):
    _, system_id, capacity, *_ = sensor.getSystemParameters()
    slots = sorted(occupied(sensor, capacity))
    if not slots:
        raise ValueError('No enrolled student slots')
    templates = {slot: base64.b64encode(read_slot(sensor, slot)).decode() for slot in slots}
    payload = json.dumps(dict(institution=institution, system_id=system_id, templates=templates)).encode()
    salt, nonce = os.urandom(16), os.urandom(12)
    ciphertext = AESGCM(archive_key(password, salt)).encrypt(nonce, payload, MAGIC)
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as f:
        tmp = Path(f.name)
    try:
        with tmp.open('wb') as f:
            f.write(MAGIC + salt + nonce + ciphertext)
            f.flush()
            os.fsync(f.fileno())
        os.link(tmp, path)  # Publish only a complete archive and preserve existing backups.
    finally:
        tmp.unlink()
    return len(slots)


def restore(sensor, institution, password, path):
    raw = path.read_bytes()
    if not raw.startswith(MAGIC):
        raise ValueError('Unrecognized archive')
    payload = json.loads(AESGCM(archive_key(password, raw[8:24])).decrypt(raw[24:36], raw[36:], MAGIC))
    _, system_id, capacity, *_ = sensor.getSystemParameters()
    if payload['institution'] != institution or payload['system_id'] != system_id:
        raise ValueError('Institution or sensor system ID differs')
    slots = occupied(sensor, capacity)
    pending = {}
    for key, encoded in payload['templates'].items():
        slot = int(key)
        data = base64.b64decode(encoded, validate=True)
        if not 1 <= slot < min(601, capacity) or not 1 <= len(data) <= 8192:
            raise ValueError('Incompatible archive slot or data length')
        if slot in slots:
            if read_slot(sensor, slot) != data:
                raise ValueError(f'Conflicting occupied slot {slot}. Recovery stopped.')
        else:
            pending[slot] = data
    for slot, data in pending.items():
        if not sensor.uploadCharacteristics(characteristicsData=list(data)):
            raise ValueError(f'Failed to verify buffer for slot {slot}')
        sensor.storeTemplate(slot)
        if read_slot(sensor, slot) != data:
            raise ValueError(f'Read-back verification failed for slot {slot}')
    return len(pending)


def main():
    from pyfingerprint.pyfingerprint import PyFingerprint

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('backup','restore'))
    parser.add_argument('port')
    parser.add_argument('institution')
    parser.add_argument('archive', type=Path)
    args = parser.parse_args()
    password = getpass.getpass('Archive passphrase: ')
    if args.operation == 'backup' and (len(password) < 12 or password != getpass.getpass('Confirm passphrase: ')):
        parser.error('Enter a matching passphrase of at least 12 characters')
    sensor = PyFingerprint(args.port, 57600)
    if not sensor.verifyPassword():
        parser.error('Sensor authentication failed')
    operation = backup if args.operation == 'backup' else restore
    print(f'{operation(sensor, args.institution, password, args.archive)} template slots processed.')


if __name__ == '__main__':
    main()
