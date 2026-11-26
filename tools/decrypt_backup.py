#!/usr/bin/env python3
"""Decrypt a Smart Prayer Attendance .spab archive on a trusted computer.
Install dependency: python -m pip install cryptography
Example: python tools/decrypt_backup.py backup.spab recovered_backup.json
Do not share the recovered JSON; it contains identifiable student records.
"""
import argparse
import getpass
import gzip
import json
from pathlib import Path
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
from cryptography.hazmat.primitives import hashes

MAGIC=b"SPABK001"

def decrypt(data, password):
    if len(data)<8+16+12+16 or not data.startswith(MAGIC):
        raise ValueError('Not a valid Smart Prayer .spab backup')
    salt=data[8:24]
    nonce=data[24:36]
    ciphertext=data[36:]
    kdf=PBKDF2HMAC(algorithm=hashes.SHA256(),length=32,salt=salt,iterations=210000)
    key=kdf.derive(password.encode('utf-8'))
    try:
        return json.loads(gzip.decompress(AESGCM(key).decrypt(nonce,ciphertext,None)))
    except Exception as e:
        raise ValueError('Wrong recovery password or corrupted backup') from e

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('backup',type=Path,help='Downloaded .spab file')
    parser.add_argument('output',type=Path,help='Destination JSON file (keep private)')
    args=parser.parse_args()
    if args.output.exists():
        parser.error('Output file already exists; choose a new path')
    password=getpass.getpass('Backup recovery password: ')
    result=decrypt(args.backup.read_bytes(),password)
    if result.get('format')!='smart-prayer-attendance-v1':
        raise ValueError('Unrecognized backup schema')
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print('Backup decrypted successfully. Contains:')
    for key,rows in result.get('tables',{}).items():
        print(f'  {key}: {len(rows)} records')
    print(f'Saved private JSON: {args.output}')
    print('NOTE: This is a recovery/export tool; it does not restore the data into the Android app.')

if __name__=='__main__':
    main()
