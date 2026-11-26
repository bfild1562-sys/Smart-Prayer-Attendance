#!/usr/bin/env python3
"""Recover central Google Drive encrypted snapshot to JSON. Never commit decoded data."""
import os,sys
from pathlib import Path
from cryptography.fernet import Fernet
if len(sys.argv)!=3:
    sys.exit('Usage: SPA_TOKEN_KEY=... python decrypt_central_backup.py encrypted.spabackup decoded.json')
key=os.environ.get('SPA_TOKEN_KEY')
if not key:sys.exit('SPA_TOKEN_KEY environment variable required')
Path(sys.argv[2]).write_bytes(Fernet(key.encode()).decrypt(Path(sys.argv[1]).read_bytes()))
print('Decrypted; handle output as confidential school attendance records.')
