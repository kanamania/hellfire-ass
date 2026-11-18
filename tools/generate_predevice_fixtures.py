#!/usr/bin/env python3
"""Synthetic fixtures with a fixed seed; contains no personal data or credentials."""
import argparse
import json
import math
from pathlib import Path
import random
import struct
import wave


def dataset():
    rng = random.Random(18112026)
    finance = []
    for lang in ('en', 'sw'):
        for index, (sender, provider) in enumerate((('M-PESA', 'MPESA'), ('Airtel', 'AIRTEL'), ('CRDB', 'CRDB'), ('NMB', 'NMB'))):
            amount = rng.randint(1, 1000) * 100
            reference = f'SYNTH{lang.upper()}{index:03d}'
            verb = 'Paid' if lang == 'en' else 'Umelipa'
            finance.append(dict(id=f'finance-{lang}-{index}', language=lang, sender=sender,
                text=f'{verb} TZS {amount:,} ref {reference}. Synthetic fixture.',
                expected=dict(provider=provider, kind='EXPENSE', amount=amount, fee=0, reference=reference)))
    for lang, text in [('en', 'Payment failed. Paid TZS 5,000 ref SYNTH0001'),
                       ('sw', 'Muamala umeshindwa. Umelipa TZS 5,000 ref SYNTH0002'),
                       ('en', 'Paid TZS 1.25 ref SYNTH0003'), ('sw', 'Umelipa TZS 0 ref SYNTH0004')]:
        finance.append(dict(id=f'reject-{len(finance)}', language=lang, sender='M-PESA', text=text, expected=None))
    queries = []
    for en, sw, kinds, categories, date in [
        ('Find my travel videos last year', 'Tafuta video zangu za safari mwaka jana', ['VIDEO'], ['travel'], 'last-year'),
        ('Show pictures of food today', 'Nionyeshe picha za chakula leo', ['IMAGE'], ['food'], 'today'),
        ('Show my audio yesterday', 'Nionyeshe sauti yangu jana', ['AUDIO'], [], 'yesterday'),
        ('Find documents last week', 'Tafuta nyaraka wiki iliyopita', ['DOCUMENT'], ['document'], 'last-week'),
        ('Find family pictures', 'Tafuta picha za familia', ['IMAGE'], ['family'], None),
        ('Find music', 'Tafuta muziki', ['AUDIO'], ['music'], None),
    ]:
        for language, text in [('en', en), ('sw', sw)]:
            queries.append(dict(id=f'query-{len(queries)}', language=language, text=text,
                expected=dict(kinds=kinds, categories=categories, date=date)))
    return dict(schema=1, synthetic=True, seed=18112026, now='2026-11-18T12:00:00+03:00',
        finance=finance, queries=queries,
        media=dict(sample_rate=16000, seconds=8, bpm=120,
            fast_schedule_ms=[0, 10000, 20000, 30000], balanced_schedule_ms=[0, 5000, 7000, 10000]),
        evaluation_limits=['Parser goldens are not model accuracy measurements.',
            'Synthetic tones do not validate speech recognition or speaker identity.',
            'No GPU, NPU, codec, battery or thermal result is implied.'])


def write_fixture(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(dataset(), ensure_ascii=False, indent=2) + '\n')


def write_media(folder):
    folder.mkdir(parents=True, exist_ok=True)
    rate = 16000
    with wave.open(str(folder / 'tone-440hz.wav'), 'wb') as output:
        output.setparams((1, 2, rate, 0, 'NONE', 'not compressed'))
        output.writeframes(b''.join(struct.pack('<h', round(8192 * math.sin(2 * math.pi * 440 * i / rate))) for i in range(rate * 2)))
    (folder / 'truncated.wav').write_bytes((folder / 'tone-440hz.wav').read_bytes()[:30])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parents[1] / 'android/app/src/test/resources/predevice/golden.json')
    parser.add_argument('--media', type=Path)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    if args.check:
        assert json.loads(args.output.read_text()) == dataset(), 'Fixture differs from deterministic generator'
        print('PASS: deterministic bilingual financial, search and media golden fixtures')
    else:
        write_fixture(args.output)
    if args.media:
        write_media(args.media)
