#!/usr/bin/env python3
"""Fetch the small pinned public test models into a local ignored cache."""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.request


def verified(path, model):
    if not path.is_file() or path.stat().st_size != model['bytes']:
        return False
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest() == model['sha256']


def fetch(folder):
    manifest = json.loads(Path(__file__).with_name('fixtures').joinpath('models.json').read_text())
    folder.mkdir(parents=True, exist_ok=True)
    for model in manifest['models']:
        path = folder / model['name']
        if not verified(path, model):
            partial = path.with_suffix('.part')
            try:
                with urllib.request.urlopen(model['url'], timeout=60) as source, partial.open('wb') as output:
                    size = 0
                    while block := source.read(1024 * 1024):
                        size += len(block)
                        if size > model['bytes']:
                            raise ValueError('Download exceeds pinned size')
                        output.write(block)
                if not verified(partial, model):
                    raise ValueError('Pinned model size or SHA-256 mismatch')
                partial.replace(path)
            finally:
                partial.unlink(missing_ok=True)
        print(f"VERIFIED {path} bytes={model['bytes']} sha256={model['sha256']}")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, default=Path.home() / '.cache/hellfire-ass/predevice/models')
    fetch(parser.parse_args().directory)
